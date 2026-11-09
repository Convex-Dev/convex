package convex.lattice;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.exceptions.StoreException;
import convex.core.store.AStore;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;

/**
 * Generic host for lattice application components, with optional persistence.
 *
 * <p>A host holds published state, an optional store and publication policy.
 * It is the generic host persistence boundary; specialised domain components may
 * separately own stores for their own data. Standalone roots publish their current
 * value as the store root on {@link #sync()}. Network infrastructure may configure
 * a replication pipeline while attached. Neither operation
 * performs a durability barrier or owns the store lifecycle.</p>
 *
 * <p>The host is the root of the component tree. Its behaviour for an incoming value
 * is delivery: every value is handed to the configured applications, which take what
 * they want. The host knows no lattice or path structure and never independently
 * merges incoming state. Without applications, incoming values are ignored.
 * Applications decide when to publish into the host. Concurrent publications during
 * host persistence stay pending for the next sync.</p>
 *
 * <p>A host fork, {@link #fork()}, is a detached copy of the root with applications
 * and a store of its own. It behaves as a second node would without the networking:
 * its sync exchanges roots with its origin through ordinary delivery.</p>
 *
 * <p>With a null store, the host is purely in memory: persistence and flush are
 * no-ops, and sync returns the current snapshot unless a publication policy is
 * configured. The root cursor holds the value directly: no MemoryStore, NullStore,
 * default store or thread-local store is created or substituted. {@link #store()}
 * returns null. A store-backed host can also be hosted by a networked NodeServer.</p>
 *
 * <p>A service that accepts delayed durability can have the host publish and flush
 * on a schedule, with {@link #autoSync(Duration, Runnable)} and
 * {@link #autoFlush(Duration)}; nothing runs on a timer unless it asks.
 * {@link #close()} is the service's shutdown step for host state: it stops those
 * workers and saves a final checkpoint. The store stays open for the service that
 * owns it.</p>
 *
 * <p><b>Threading.</b> Syncs, flushes, checkpoints and incoming deliveries may run
 * on any thread; publication is serialised. The service adds applications at
 * setup and closes the host at shutdown, one call after another.</p>
 *
 * @param <V> Root lattice value type
 */
public final class LatticeHost<V extends ACell> extends ALatticeComponent<V> implements Closeable {

	private final AStore store;
	private final LatticeHost<V> origin;
	private final ReentrantLock syncLock=new ReentrantLock();
	private Function<V,V> publisher;
	private volatile AutoSync autoSync;
	private volatile AutoFlush autoFlush;
	private boolean closed;
	private volatile List<ALatticeComponent<V>> applications=List.of();

	/**
	 * Creates a host around existing atomic storage, optionally backed by a store.
	 *
	 * <p>Use {@link #cursor()} as the destination of hosted components. Their sync
	 * updates this host's working state; it does not persist or replicate it.
	 * Call this component's {@link #sync()} for host publication. The cursor
	 * remains ordinary atomic storage with no publication callbacks.</p>
	 *
	 * @param cursor Root cursor holding the hosted value
	 * @param store Host store, or null for purely in-memory operation
	 */
	public LatticeHost(Root<V> cursor, AStore store) {
		this(cursor,store,null);
	}

	private LatticeHost(Root<V> cursor, AStore store, LatticeHost<V> origin) {
		super(cursor);
		this.store=store;
		this.origin=origin;
	}

	/** Creates an empty keyed root held directly in memory, with no store allocated. */
	public static LatticeHost<Index<Keyword,ACell>> create() {
		return create(null);
	}

	/** Creates a fresh global host with an empty keyed root over a store that holds no root yet. */
	public static LatticeHost<Index<Keyword,ACell>> create(AStore store) {
		return create(Index.none(),store);
	}

	/**
	 * Creates a fresh host with an explicit initial value, including non-map local
	 * roots. The store, if any, must hold no root yet: a retained root is adopted with
	 * {@link #open(ACell, AStore)} or discarded explicitly with
	 * {@link #replace(ACell, AStore)}, never overwritten by accident.
	 *
	 * @throws IllegalStateException if the store already holds a root
	 */
	public static <V extends ACell> LatticeHost<V> create(V initialValue, AStore store) {
		if (store!=null && retainedRoot(store)!=null) {
			throw new IllegalStateException("Store already holds a root: open it, or replace it explicitly");
		}
		return new LatticeHost<>(Cursors.root(initialValue),store);
	}

	/**
	 * Starts a fresh host over a store, discarding any retained root: the initial value
	 * is written as the store root at once. This is the one way to start again over a
	 * store that already holds state.
	 *
	 * @param initialValue Root the host starts from and the store now holds; not null
	 * @param store Store whose retained root is replaced, or null for in-memory operation
	 * @return Host over the initial value
	 */
	public static <V extends ACell> LatticeHost<V> replace(V initialValue, AStore store) {
		Objects.requireNonNull(initialValue,"Replacement root must not be null");
		if (store!=null) {
			try {
				store.setRootData(initialValue);
			} catch (IOException e) {
				throw new StoreException("Lattice host could not replace the retained root",e);
			}
		}
		return new LatticeHost<>(Cursors.root(initialValue),store);
	}

	private static <T extends ACell> T retainedRoot(AStore store) {
		try {
			return store.getRootData();
		} catch (IOException e) {
			throw new StoreException("Lattice host could not read the retained root",e);
		}
	}

	/** Opens the retained global root, or an empty keyed root if none is retained. */
	public static LatticeHost<Index<Keyword,ACell>> open(AStore store) throws IOException {
		return open(Index.<Keyword,ACell>none(),store);
	}

	/**
	 * Opens the retained root, using the supplied initial value only when none is
	 * retained. The caller supplies the value type; no merge or publication occurs.
	 * The caller owns the store lifecycle. A null store uses the supplied initial value.
	 */
	public static <V extends ACell> LatticeHost<V> open(V initialValue, AStore store) throws IOException {
		V retained=(store==null)?null:store.getRootData();
		return new LatticeHost<>(Cursors.root(retained==null?initialValue:retained),store);
	}

	/** Returns the host store, or null for in-memory operation. The caller owns its lifecycle. */
	public AStore store() {
		return store;
	}

	/** Returns the host this fork was taken from, or null for a host that is not a fork. */
	public LatticeHost<V> origin() {
		return origin;
	}

	/**
	 * Forks this host: a detached copy of the current root with no store. The fork
	 * starts with no applications; the caller builds its own over it and registers
	 * them, since applications are bound to the cursor they were built over. Its
	 * {@link #sync()} exchanges roots with this host through ordinary delivery, so
	 * the fork behaves as a second node would without the networking. There is no
	 * host-level merge: reconciliation is the applications' on each side.
	 *
	 * @return Detached host over a copy of the current root
	 */
	@Override
	public LatticeHost<V> fork() {
		return fork(null);
	}

	/**
	 * Forks this host with a store of its own, for staged work too large for the heap:
	 * components over the fork checkpoint into that store, and the fork's sync persists
	 * its root there before exchanging roots. The exchange carries each root's cells into
	 * the receiving side's store, so the fork's store can be discarded once it has synced.
	 * A fork may not share its origin's store, which its sync would otherwise overwrite.
	 *
	 * @param store Store for the fork, or null for an in-memory fork
	 * @return Detached host over a copy of the current root
	 * @throws IllegalArgumentException if the store is this host's own
	 */
	public LatticeHost<V> fork(AStore store) {
		if (store!=null && store==this.store) {
			throw new IllegalArgumentException("A fork must not share its origin's store");
		}
		return new LatticeHost<>(Cursors.root(cursor().get()),store,this);
	}

	/**
	 * Delegates incoming state to the configured applications, without a second
	 * host merge. Each application controls its working state and publication.
	 * Delivery is not atomic across applications: earlier successful merges remain
	 * if a later application fails. Retry is safe for lattice merges.
	 *
	 * @param other Incoming root value
	 * @return Current published root, which may not yet include the incoming state
	 */
	public V merge(V other) {
		List<ALatticeComponent<V>> configured=applications;
		for (ALatticeComponent<V> application:configured) application.merge(other);
		return cursor().get();
	}

	/**
	 * Delivers an incoming subtree to applications at a root-relative path. Each
	 * application's {@code mergeAt} decides: it walks the path through its child
	 * components, and the deepest that resolves merges the value, reduced to its path,
	 * with its own behaviour. No application sync is invoked.
	 * Delivery is sequential, with the same partial-failure semantics as
	 * {@link #merge}. Paths are passed unchanged and must not be mutated by handlers.
	 *
	 * @return Current published root, which may not include this delivery
	 */
	public V mergeAt(ACell value, ACell... path) {
		List<ALatticeComponent<V>> configured=applications;
		for (ALatticeComponent<V> application:configured) application.mergeAt(value,path);
		return cursor().get();
	}

	/**
	 * Adds an existing root application and returns that exact instance. Registration
	 * neither rebinds the component nor changes its policy, working state or publication.
	 * Applications receive incoming values in registration order and own their paths.
	 * Each delivery takes a stable snapshot of registrations, so additions during
	 * delivery receive subsequent deliveries only. Adding an application does not
	 * replay previous input or merge existing host state into it. Applications manage
	 * their own children; only root components register here.
	 */
	public <C extends ALatticeComponent<V>> C addApplication(C application) {
		Objects.requireNonNull(application,"Application must not be null");
		var updated=new ArrayList<>(applications);
		updated.add(application);
		applications=List.copyOf(updated);
		return application;
	}

	/**
	 * Persists a value through the host store without installing, publishing or
	 * moving any cursor. The returned value may contain store-backed references.
	 * Without a store, returns the supplied value unchanged.
	 *
	 * @param <T> Cell type
	 * @param value Value to persist
	 * @return Persisted value
	 * @throws IOException If persistence fails
	 */
	public <T extends ACell> T persist(T value) throws IOException {
		return (store==null)?value:Cells.persist(value,store);
	}

	/**
	 * Persists the current root value without changing the cursor or publishing.
	 * Unlike a component's working state, the root keeps its references until
	 * {@link #sync()} installs the retained snapshot.
	 *
	 * @return Persisted current root value
	 * @throws IOException If persistence fails
	 */
	@Override
	public V persist() throws IOException {
		return persist(cursor().get());
	}

	/**
	 * Publishes one snapshot, persisting it if a store is present. A fork then
	 * exchanges roots with its origin: the fork's root is delivered to the origin's
	 * applications and the origin's root to the fork's, each side taking what it
	 * wants, as two nodes do. Each root's cells are persisted into the receiving
	 * side's store first, so neither host depends on the other's store afterwards;
	 * root pointers move only on each host's own sync. Neither side's publication is
	 * implied, and the fork's own store receives the state the fork held before the
	 * exchange.
	 *
	 * @return The fork's current root after the exchange, or the published snapshot
	 */
	@Override
	public V sync() {
		V published=sync(this::publishRoot);
		if (origin==null) return published;
		origin.merge(persistInto(origin,published));
		merge(persistInto(this,origin.cursor().get()));
		return cursor().get();
	}

	/** Persists a value's cells into a host's store, without moving its root. */
	private static <V extends ACell> V persistInto(LatticeHost<V> host, V value) {
		try {
			return host.persist(value);
		} catch (IOException e) {
			throw new StoreException("Lattice host fork sync failed: persistence error",e);
		}
	}

	/**
	 * Retains a snapshot, then publishes through an explicit host operation,
	 * serialised with ordinary sync. The callback receives store-backed state and
	 * runs once outside atomic update callbacks. Concurrent writes
	 * remain pending; the returned value is the snapshot actually published.
	 * This is a host integration point, not a component-to-parent link.
	 * The publisher must preserve the snapshot's value; only its storage references
	 * may change, as with {@link #attachPublisher(Function)}.
	 */
	public V sync(Function<V,V> publish) {
		Objects.requireNonNull(publish);
		syncLock.lock();
		try {
			ensureOpen();
			V snapshot=cursor().get();
			V published=publish.apply(storeRoot(snapshot));
			// Persisted references may replace the snapshot; concurrent edits stay pending.
			cursor().compareAndSet(snapshot,published);
			return published;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Attaches one synchronous publisher, such as a node server. It must publish
	 * the supplied snapshot unchanged, though it may return equivalent store-backed
	 * references. Domain edits belong in components, before publication.
	 * Attachment and detachment are serialised with sync. The caller retains this
	 * exact function instance to detach it when its publication resources close.
	 *
	 * @param publish Snapshot publisher
	 * @throws IllegalStateException If a publisher is already attached
	 */
	public void attachPublisher(Function<V,V> publish) {
		Objects.requireNonNull(publish);
		syncLock.lock();
		try {
			ensureOpen();
			if (publisher!=null) throw new IllegalStateException("Host already has a publisher");
			publisher=publish;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Detaches this exact publisher, waiting for in-progress sync to finish.
	 * Later syncs use the host store directly, or are no-ops without a store.
	 * A stale detach cannot remove a replacement publisher.
	 */
	public void detachPublisher(Function<V,V> publish) {
		syncLock.lock();
		try {
			if (publisher==publish) publisher=null;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Flushes the underlying store using its own durability semantics; no-op without a store.
	 *
	 * @throws IOException If the store flush fails
	 */
	public void flush() throws IOException {
		syncLock.lock();
		try {
			ensureOpen();
			if (store!=null) store.flush();
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Retains and publishes one host snapshot, then completes its durability barrier.
	 * Does not sync applications or exchange a fork with its origin: a private host
	 * can be checkpointed without accepting its changes into the original host.
	 * Concurrent edits remain pending. A failed barrier does not undo publication;
	 * callers may retry. Without a store there is no restart durability guarantee.
	 *
	 * @return the snapshot retained and flushed, not any concurrent working edits
	 * @throws IOException if retaining the root or flushing fails
	 */
	public V checkpoint() throws IOException {
		syncLock.lock();
		try {
			V published=sync(this::publishRoot);
			flush();
			return published;
		} catch (StoreException e) {
			if (e.getCause() instanceof IOException cause) throw cause;
			throw e;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Starts scoped periodic host sync, for applications that write straight into the
	 * host root and so have nothing to sync: {@link #autoSync(Duration, Runnable)}
	 * with no application sync.
	 *
	 * @param interval Delay between syncs; positive
	 * @return The running worker, which the host also closes
	 * @throws IllegalStateException if the host is closed or already has one
	 */
	public AutoSync autoSync(Duration interval) {
		return startAutoSync(interval,null);
	}

	/**
	 * Starts scoped periodic sync, for a service that accepts delayed durability.
	 * Every interval the worker runs the application sync, such as
	 * {@code application::sync}, then syncs the host, retaining the root and handing
	 * it to any attached node. The host never chooses which applications sync: drafts
	 * of applications the service leaves out stay unpublished. Nothing is flushed on
	 * the interval: pair this with {@link #autoFlush(Duration)}, and use
	 * {@link #checkpoint()} for durable requests.
	 *
	 * <p>Close the returned resource, or the host, before the borrowed store. Closing
	 * the resource syncs the applications and checkpoints once more; closing the host
	 * syncs the applications once more before its final checkpoint. Only one such
	 * resource may be active on a host.</p>
	 *
	 * @param interval Delay between runs; positive
	 * @param applicationSync Syncs the service's applications with the host root;
	 *        runs on the worker, outside the host's publication lock
	 * @return The running worker, which the host also closes
	 * @throws IllegalStateException if the host is closed or already has one
	 */
	public AutoSync autoSync(Duration interval, Runnable applicationSync) {
		return startAutoSync(interval,Objects.requireNonNull(applicationSync,"Application sync must not be null"));
	}

	private AutoSync startAutoSync(Duration interval, Runnable applicationSync) {
		syncLock.lock();
		try {
			ensureOpen();
			if (autoSync!=null) throw new IllegalStateException("Host already has an automatic sync");
			autoSync=new AutoSync(this,interval,applicationSync);
			return autoSync;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Starts scoped periodic flushing of already-retained state. It syncs neither
	 * applications nor the host; {@link #autoSync(Duration, Runnable)} does.
	 * Close the returned resource, or the host, before the borrowed store. Closing the
	 * resource stops the worker and flushes once more; closing the host stops it
	 * before its final checkpoint. Only one such resource may be active on a host. Use
	 * {@link #checkpoint()} for durable requests.
	 */
	public AutoFlush autoFlush(Duration interval) {
		syncLock.lock();
		try {
			ensureOpen();
			if (autoFlush!=null) throw new IllegalStateException("Host already has an automatic flusher");
			autoFlush=new AutoFlush(this,interval);
			return autoFlush;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Closes the host: stops any automatic sync, which syncs the applications once
	 * more, and any automatic flushing, then retains and flushes a final snapshot
	 * exactly as {@link #checkpoint()} does. A failure of that last application sync
	 * is reported through the automatic sync's {@link AutoSync#failure()}, and the
	 * save still runs. A
	 * service with a node checkpoints first, so the node sends that checkpoint to its
	 * peers as it closes, then closes the node before the host:
	 * {@code try (store; host; node) { app.sync(); host.checkpoint(); }}; see
	 * convex-peer/docs/SHUTDOWN.md.
	 *
	 * <p>Beyond the automatic sync's application sync, closing does not sync
	 * applications, and like a checkpoint it does not exchange a fork with its
	 * origin. A publisher still attached receives the final snapshot. The store stays
	 * open for the service that owns it. Without a store there is nothing to retain,
	 * so closing only stops the automatic workers.</p>
	 *
	 * <p>Once closed, {@link #sync()}, {@link #sync(Function)}, {@link #checkpoint()},
	 * {@link #flush()}, {@link #autoSync(Duration, Runnable)}, {@link #autoFlush(Duration)}
	 * and {@link #attachPublisher(Function)} throw {@link IllegalStateException}. Reads
	 * still work. Incoming merges may still change the in-memory value but cannot be
	 * retained, so a node still running fails its syncs. Closing again does nothing.</p>
	 *
	 * @throws IOException if the final checkpoint fails; the host stays open and a
	 *         later close retries, without the automatic workers, which have stopped
	 * @throws IllegalStateException if called from an automatic worker's thread, such
	 *         as its failure callback, which closing must wait for
	 */
	@Override
	public void close() throws IOException {
		// Outside syncLock: the workers' steps take it, and stopping waits for them
		AutoSync syncer=autoSync;
		AutoFlush flusher=autoFlush;
		if ((syncer!=null && syncer.isWorker()) || (flusher!=null && flusher.isWorker())) {
			throw new IllegalStateException("Close the host from its owner, not an automatic worker's failure callback");
		}
		if (syncer!=null) syncer.closeForHost();
		if (flusher!=null) flusher.closeForHost();
		syncLock.lock();
		try {
			if (closed) return;
			if (store!=null) checkpoint();
			closed=true;
		} finally {
			syncLock.unlock();
		}
	}

	private void ensureOpen() {
		if (closed) throw new IllegalStateException("Lattice host is closed");
	}

	void releaseAutoSync(AutoSync resource) {
		syncLock.lock();
		try {
			if (autoSync==resource) autoSync=null;
		} finally {
			syncLock.unlock();
		}
	}

	void releaseAutoFlush(AutoFlush resource) {
		syncLock.lock();
		try {
			if (autoFlush==resource) autoFlush=null;
		} finally {
			syncLock.unlock();
		}
	}

	private V publishRoot(V value) {
		Function<V,V> policy=publisher;
		return (policy==null)?value:policy.apply(value);
	}

	private V storeRoot(V value) {
		if (store==null) return value;
		try {
			return store().setRootData(value).getValue();
		} catch (IOException e) {
			throw new StoreException("Lattice host sync failed: persistence error",e);
		}
	}
}
