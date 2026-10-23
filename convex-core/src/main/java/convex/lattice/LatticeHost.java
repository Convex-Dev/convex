package convex.lattice;

import java.io.IOException;
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
 * a replication pipeline before freezing host configuration. Neither operation
 * performs a durability barrier or owns the store lifecycle.</p>
 *
 * <p>All incoming values are delegated to configured applications. The host knows
 * no lattice or path structure and never independently merges incoming state.
 * Without applications, incoming values are ignored. Applications decide when
 * to publish into the host. Concurrent publications during host persistence stay
 * pending for the next sync.</p>
 *
 * <p>With a null store, the host is purely in memory: persistence and flush are
 * no-ops, and sync returns the current snapshot unless a publication policy is
 * configured. The root cursor holds the value directly: no MemoryStore, NullStore,
 * default store or thread-local store is created or substituted. {@link #store()}
 * returns null. A store-backed host can also be hosted by a networked NodeServer.</p>
 *
 * @param <V> Root lattice value type
 */
public final class LatticeHost<V extends ACell> extends ALatticeComponent<V> {

	private final AStore store;
	private final ReentrantLock syncLock=new ReentrantLock();
	private volatile Function<V,V> publicationPolicy;
	private boolean publicationPolicyFrozen;
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
		super(cursor);
		this.store=store;
	}

	/** Creates an empty keyed root held directly in memory, with no store allocated. */
	public static LatticeHost<Index<Keyword,ACell>> create() {
		return create(null);
	}

	/** Creates a fresh global host with an empty keyed root. Does not restore the store. */
	public static LatticeHost<Index<Keyword,ACell>> create(AStore store) {
		return create(Index.none(),store);
	}

	/** Creates a fresh host with an explicit initial value, including non-map local roots. */
	public static <V extends ACell> LatticeHost<V> create(V initialValue, AStore store) {
		return new LatticeHost<>(Cursors.root(initialValue),store);
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
		return create(retained==null?initialValue:retained,store);
	}

	/** Returns the host store, or null for in-memory operation. The caller owns its lifecycle. */
	public AStore store() {
		return store;
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
	public synchronized <C extends ALatticeComponent<V>> C addApplication(C application) {
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
	 *
	 * @return Persisted current root value
	 * @throws IOException If persistence fails
	 */
	public V persist() throws IOException {
		return persist(cursor().get());
	}

	/** Publishes one snapshot, persisting it if a store is present. */
	@Override
	public V sync() {
		return sync(this::publishRoot);
	}

	/**
	 * Publishes through an explicit host operation, serialised with ordinary sync.
	 * The callback runs once outside atomic update callbacks. Concurrent writes
	 * remain pending; the returned value is the snapshot actually published.
	 * This is a host integration point, not a component-to-parent link.
	 */
	public V sync(Function<V,V> publish) {
		Objects.requireNonNull(publish);
		syncLock.lock();
		try {
			V snapshot=cursor().get();
			V published=publish.apply(snapshot);
			// Persisted references may replace the snapshot; concurrent edits stay pending.
			cursor().compareAndSet(snapshot,published);
			return published;
		} finally {
			syncLock.unlock();
		}
	}

	/**
	 * Configures the synchronous root publication policy.
	 *
	 * <p>This is a host integration point. The caller invokes this root's
	 * {@link #sync()} to persist or replicate its current state. Child component
	 * publication does not invoke this policy. The handler returns the published value to install
	 * back into the root cursor, usually with store-backed references.</p>
	 *
	 * <p>Until a policy is configured, sync publishes to this component's store,
	 * or returns the current snapshot if there is no store.
	 * Host infrastructure may install a replication pipeline during configuration
	 * and then call {@link #freezePublicationPolicy()} before application work begins.</p>
	 *
	 * @param policy Root publication policy
	 * @throws IllegalStateException If host publication configuration is frozen
	 */
	public synchronized void setPublicationPolicy(Function<V,V> policy) {
		if (publicationPolicyFrozen) {
			throw new IllegalStateException("Root publication policy is frozen");
		}
		if (policy==null) throw new IllegalArgumentException("Root publication policy must not be null");
		publicationPolicy=policy;
	}

	/**
	 * Freezes host publication configuration against later replacement.
	 * Application state may continue to sync normally after this boundary.
	 */
	public synchronized void freezePublicationPolicy() {
		publicationPolicyFrozen=true;
	}

	/**
	 * Flushes the underlying store using its own durability semantics; no-op without a store.
	 *
	 * @throws IOException If the store flush fails
	 */
	public void flush() throws IOException {
		if (store!=null) store.flush();
	}

	private V publishRoot(V value) {
		Function<V,V> policy=publicationPolicy;
		return (policy==null)?storeRoot(value):policy.apply(value);
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
