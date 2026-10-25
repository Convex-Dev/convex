package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.exceptions.StoreException;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.Shutdown;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;

/**
 * Replicates one host's published root and keeps it durable.
 *
 * <p>{@code NodeServer} is replication only. It hands incoming values to its
 * {@link LatticeHost}, which delegates them to the applications configured to
 * receive them, and it publishes the host's root to its propagation groups. It
 * holds no lattice, no merge policy and no applications of its own: assemble the
 * host and its applications first, for example with {@link RelayHost}, then
 * construct the node from the host. The node owns host-root publication,
 * persistence and update notification. It does not own peer sets, connection
 * trust, protocol decoding, missing-cell acquisition, publication filtering,
 * sockets or transport lifecycle. Those are policies and
 * resources of {@link LatticePropagator} and application-owned transports such as
 * {@link LatticeListener}.</p>
 *
 * <p>The calling application explicitly adds each
 * propagator with {@link #addPropagator(LatticePropagator)}.
 * Attaching connects the group to this node for publication and inbound
 * delivery. It does not copy a transport key or filters into the group. A node
 * with no propagators is a valid local, store-backed node. Groups may be added,
 * configured and removed while running.</p>
 *
 * <p>The calling application separately owns transport composition. It may
 * register one or more attached groups with a shared {@link LatticeListener},
 * give groups independent transports, or run this node without networking.
 * Transports must be closed before this server so no new ingress reaches groups
 * while they drain.</p>
 *
 * <p><b>Failure isolation.</b> Host-root publication is the mandatory part of a
 * host sync. Propagator initialisation, notification, worker and shutdown
 * failures are contained and logged independently; one propagation policy group
 * cannot fail a host sync, the server maintenance loop or another group. Only
 * failures of the node store are surfaced by this class; transport failures
 * remain visible to their application owner.</p>
 *
 * @param <V> host root value type
 */
public class NodeServer<V extends ACell> implements Closeable {

	private static final Logger log=LoggerFactory.getLogger(NodeServer.class);

	private final AStore store;
	private final NodeConfig config;
	private final AForkableCursor<V> cursor;
	private final LatticeHost<V> host;
	/** Immutable snapshots let publication proceed independently of group removal. */
	private volatile List<LatticePropagator> propagators=List.of();
	private final Set<LatticePropagator> attachedPropagators=ConcurrentHashMap.newKeySet();

	/** Serialises all authoritative cell announcement, root-pointer and flush work. */
	private final Object persistenceLock=new Object();
	private boolean storeDirty;

	private final Function<V,V> publisher=this::publishApplicationRoot;
	private Thread maintenanceThread;
	private final Object maintenanceSignal=new Object();
	private final Runnable shutdownHook=this::shutdownPersist;
	private boolean shutdownHookRegistered;

	/** Complete lifecycle for resources owned by the node host. */
	enum LifecycleState {
		NEW, STARTING, RUNNING, STOPPING, STOPPED
	}

	private volatile LifecycleState lifecycleState=LifecycleState.NEW;

	/**
	 * Replicates an already-assembled host. Its state, policy and applications
	 * remain as supplied; launch does not reload the store. Open or create the host
	 * and configure its applications first; {@link RelayHost} assembles the common
	 * case of a host with one live application over a whole lattice. The node
	 * installs its publication pipeline at launch and owns attached propagation
	 * resources, not the store. Closing or failed launch detaches its publication
	 * callback, leaving the host available for local use or another server.
	 *
	 * @param host assembled host with a non-null cell store
	 * @param config node persistence configuration, or {@code null} for defaults
	 */
	public NodeServer(LatticeHost<V> host,NodeConfig config) {
		this.host=Objects.requireNonNull(host,"Host must not be null");
		this.store=Objects.requireNonNull(host.store(),"NodeServer requires a host cell store");
		this.cursor=host.cursor();
		this.config=(config==null) ? NodeConfig.create() : config;
	}

	/** Replicates an already-assembled host with default node configuration. */
	public NodeServer(LatticeHost<V> host) {
		this(host,null);
	}

	/**
	 * Publishes the host's current root, starts every attached policy group
	 * independently, then starts persistence maintenance.
	 *
	 * @throws IOException if node store initialisation fails
	 */
	public synchronized void launch() throws IOException,InterruptedException {
		validateLaunchRequest();
		lifecycleState=LifecycleState.STARTING;
		try {
			configurePublicationPipeline();
			seedPropagationViews();
			startPropagationServices();
			startMaintenanceService();
			registerShutdownPersistenceHook();
			lifecycleState=LifecycleState.RUNNING;
			log.debug("NodeServer started with {} propagation group(s)",
				propagators.size());
		} catch (IOException | RuntimeException | Error e) {
			abortFailedLaunch(e);
			throw e;
		}
	}

	private void validateLaunchRequest() {
		if (lifecycleState==LifecycleState.RUNNING || lifecycleState==LifecycleState.STARTING) {
			throw new IllegalStateException("NodeServer is already running");
		}
		if (lifecycleState==LifecycleState.STOPPING) {
			throw new IllegalStateException("NodeServer shutdown is incomplete");
		}
	}

	private void configurePublicationPipeline() {
		host.attachPublisher(publisher);
	}

	private void seedPropagationViews() throws IOException {
		ACell announced=syncSnapshot(false);
		for (LatticePropagator propagator:propagators) {
			runIsolated(propagator,"initial view materialisation",
				() -> propagator.processSnapshot(announced));
		}
		if (config.isPersist()) checkpoint();
	}

	private void startPropagationServices() {
		for (LatticePropagator propagator:propagators) {
			runIsolated(propagator,"launch",propagator::start);
		}
	}

	private void abortFailedLaunch(Throwable failure) {
		lifecycleState=LifecycleState.STOPPING;
		stopMaintenance();
		for (LatticePropagator propagator:propagators) {
			try {
				propagator.close();
			} catch (Throwable cleanupFailure) {
				addCleanupFailure(failure,cleanupFailure);
			}
		}
		lifecycleState=LifecycleState.STOPPED;
		removeShutdownHook();
		host.detachPublisher(publisher);
	}

	private static void addCleanupFailure(Throwable failure,Throwable cleanupFailure) {
		if (failure!=cleanupFailure) failure.addSuppressed(cleanupFailure);
	}

	/**
	 * Pulls a complete root through an explicitly chosen propagation group and
	 * delivers it to the host. Configured applications may keep their
	 * results private; only already-published host state is persisted and propagated.
	 *
	 * @param source group whose serving store receives acquired cells
	 * @param peer manager-owned peer client to query through {@code source}
	 * @return future containing host state after delivery and host sync; this does
	 *         not acknowledge publication of private application results
	 */
	public CompletableFuture<V> pull(LatticePropagator source,Convex peer) {
		requireAttached(source);
		return source.pull(peer).thenApply(acquired -> {
			requireAttached(source);
			mergePulledValue(acquired);
			host.sync();
			return cursor.get();
		});
	}

	/**
	 * Pulls one path through an explicitly chosen propagation group.
	 * Path selection and handling belong to configured applications. A successful
	 * pull does not force application publication.
	 *
	 * @param source group whose serving store receives acquired cells
	 * @param peer manager-owned peer client to query through {@code source}
	 * @param path path within remote and local roots; empty selects the root
	 * @return future containing the host value at {@code path}
	 */
	public CompletableFuture<ACell> pullPath(LatticePropagator source,Convex peer,ACell... path) {
		return pullPath(source,peer,false,path);
	}

	/** Pulls a path, optionally as background bootstrap rather than application activity. */
	public CompletableFuture<ACell> pullPath(LatticePropagator source,Convex peer,
			boolean background,ACell... path) {
		requireAttached(source);
		return source.pullPath(peer,background,path).thenApply(acquired -> {
			requireAttached(source);
			if (acquired!=null) mergeIncoming(acquired,path);
			host.sync();
			return RT.getIn(cursor.get(),path);
		});
	}

	/**
	 * Pulls through the first attached group for source compatibility.
	 * Applications with more than one policy group should retain and pass the
	 * intended group explicitly.
	 *
	 * @deprecated Retain the application-constructed group and use
	 * {@link #pull(LatticePropagator, Convex)}.
	 * @param peer manager-owned peer client to query
	 * @return future containing the host value after delivery and sync
	 */
	@Deprecated
	public CompletableFuture<V> pull(Convex peer) {
		LatticePropagator source=getPropagator();
		if (source==null) return CompletableFuture.failedFuture(
			new IllegalStateException("No propagator configured"));
		return pull(source,peer);
	}

	/**
	 * Pulls one path through the first attached group.
	 *
	 * @deprecated Use {@link #pullPath(LatticePropagator, Convex, ACell...)}.
	 * @param peer manager-owned peer client to query
	 * @param path path within remote and local roots; empty selects the root
	 * @return future containing the host value at {@code path}
	 */
	@Deprecated
	public CompletableFuture<ACell> pullPath(Convex peer,ACell... path) {
		LatticePropagator source=getPropagator();
		if (source==null) return CompletableFuture.failedFuture(
			new IllegalStateException("No propagator configured"));
		return pullPath(source,peer,path);
	}

	/**
	 * Pulls roots from every current manager-owned peer client in an explicit
	 * propagation group and publishes the resulting host root.
	 *
	 * @param source application-selected attached group
	 * @return {@code true} if every pull completed successfully
	 */
	public boolean pull(LatticePropagator source) {
		requireAttached(source);
		try {
			for (ACell acquired:source.pullAll().get(30,TimeUnit.SECONDS)) {
				requireAttached(source);
				mergePulledValue(acquired);
			}
			host.sync();
			return true;
		} catch (Exception e) {
			log.warn("Pull through propagation group failed",e);
			return false;
		}
	}

	/**
	 * Pulls every route of the first attached group for source compatibility.
	 *
	 * @deprecated Use {@link #pull(LatticePropagator)}.
	 * @return {@code true} if every pull completed successfully, or no group exists
	 */
	@Deprecated
	public boolean pull() {
		LatticePropagator source=getPropagator();
		return source==null || pull(source);
	}

	/**
	 * Alias retained for source compatibility.
	 *
	 * @deprecated Use {@link #pull(Convex)}.
	 * @param peer manager-owned peer client to query
	 * @return future containing the host value after delivery and sync
	 */
	@Deprecated
	public CompletableFuture<V> syncWithPeer(Convex peer) {
		return pull(peer);
	}

	@SuppressWarnings("unchecked")
	private void mergePulledValue(ACell acquired) {
		if (acquired==null) return;
		host.merge((V)acquired);
	}

	/**
	 * Merges an application-supplied value into memory. Call {@link LatticeHost#sync()}
	 * when the update should be published and persisted.
	 *
	 * @param receivedValue complete foreign lattice value
	 * @return Current host value, which may exclude private application changes;
	 *         null for null input
	 */
	public V mergeValue(V receivedValue) {
		if (receivedValue==null) return null;
		return host.merge(receivedValue);
	}

	/**
	 * Delivers a value at a path within the host root to the host's configured
	 * applications; each walks the path through its child components, and the deepest
	 * that resolves merges the value, reduced to its path, with its own behaviour. Call
	 * {@link LatticeHost#sync()} on {@link #getHost()} when the update should be
	 * published and persisted.
	 *
	 * @param <T> value type at the path
	 * @param value value to merge
	 * @param path path within the host root
	 * @return Current host value at the path; application changes may remain private
	 */
	public <T extends ACell> T mergeAt(T value, ACell... path) {
		return RT.getIn(host.mergeAt(value,path),path);
	}

	/**
	 * Returns the host's current in-memory root value.
	 *
	 * @return current host root value
	 */
	public V getLocalValue() {
		return cursor.get();
	}

	/**
	 * Returns the host's root cursor.
	 *
	 * @return host root cursor
	 */
	public AForkableCursor<V> getCursor() {
		return cursor;
	}

	/**
	 * Returns the application-facing host hosted by this node.
	 *
	 * @return hosted host
	 */
	public LatticeHost<V> getHost() {
		return host;
	}

	/**
	 * Returns the caller-owned authoritative node store.
	 *
	 * @return authoritative store
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Returns the immutable configuration wrapper supplied to this node host.
	 *
	 * @return node configuration
	 */
	public NodeConfig getConfig() {
		return config;
	}


	/**
	 * Returns whether launch completed and shutdown has not begun.
	 *
	 * @return {@code true} while the node is running
	 */
	public boolean isRunning() {
		return lifecycleState==LifecycleState.RUNNING;
	}

	LifecycleState getLifecycleState() {
		return lifecycleState;
	}

	/**
	 * Returns the first attached group as a convenience for single-policy
	 * applications, or {@code null}. The application should normally retain the
	 * propagator reference it constructed.
	 *
	 * @deprecated Retain the group reference or use {@link #getPropagators()}.
	 * @return first attached group, or {@code null} if none exists
	 */
	@Deprecated
	public LatticePropagator getPropagator() {
		List<LatticePropagator> snapshot=propagators;
		return snapshot.isEmpty() ? null : snapshot.get(0);
	}

	/**
	 * Returns an immutable snapshot of attached propagation policy groups.
	 *
	 * @return attached groups in attachment order
	 */
	public List<LatticePropagator> getPropagators() {
		return propagators;
	}

	/**
	 * Attaches a propagation policy group, starting it if the server is running.
	 *
	 * <p>This operation deliberately performs no policy configuration. In
	 * particular it does not supply an identity, filters, handlers or peer routes.
	 * The application sets those directly on the propagator. Attachment transfers
	 * lifecycle ownership: the node starts and
	 * closes the group with its own resources.</p>
	 *
	 * @param propagator configured policy group to attach
	 */
	public synchronized void addPropagator(LatticePropagator propagator) {
		if (propagator==null) throw new IllegalArgumentException("Propagator must not be null");
		if (lifecycleState!=LifecycleState.NEW && lifecycleState!=LifecycleState.RUNNING) {
			throw new IllegalStateException("Cannot add a propagator while the node is starting or stopped");
		}
		if (attachedPropagators.contains(propagator)) {
			throw new IllegalArgumentException("Propagator is already attached to this node");
		}
		propagator.attach(this);
		List<LatticePropagator> updated=new ArrayList<>(propagators);
		updated.add(propagator);
		propagators=List.copyOf(updated);
		attachedPropagators.add(propagator);
		if (isRunning()) {
			// Seed and start under the host publication lock so no newer notification
			// can be lost between the initial snapshot and starting the worker.
			try {
				host.sync(snapshot -> {
					runIsolated(propagator,"initial view materialisation",() -> propagator.processSnapshot(snapshot));
					runIsolated(propagator,"launch",propagator::start);
					return publishApplicationRoot(snapshot);
				});
			} catch (RuntimeException | Error failure) {
				removePropagator(propagator);
				throw failure;
			}
		}
	}

	/**
	 * Removes and shuts down one propagation group without stopping the node.
	 *
	 * <p>Stops future node notifications and explicit pulls through this group,
	 * drains its accepted work and closes its routes, including assigned inbound
	 * sockets. Work already accepted may still merge while shutdown drains it;
	 * outstanding pulls that complete after removal are rejected before merge.
	 * Other groups and the host root are unaffected. The caller retains
	 * ownership of all stores; this operation neither closes nor erases them.</p>
	 *
	 * <p>Unregister the group from its application-owned listeners first with
	 * {@link LatticeListener#unregisterPropagator(LatticePropagator)}. A removed
	 * group is excluded from subsequent node launches. Shutdown failures are
	 * isolated and available through {@link LatticePropagator#getStatus()}.</p>
	 *
	 * <p>This blocking lifecycle operation must be called from application
	 * management code, outside group message handlers and publication callbacks.</p>
	 *
	 * @param propagator group to remove
	 * @return {@code true} if removed, or {@code false} if it was not attached
	 */
	public synchronized boolean removePropagator(LatticePropagator propagator) {
		if (propagator==null) throw new IllegalArgumentException("Propagator must not be null");
		if (lifecycleState==LifecycleState.STARTING || lifecycleState==LifecycleState.STOPPING) {
			throw new IllegalStateException("Cannot remove a group during node launch or shutdown");
		}
		if (!attachedPropagators.remove(propagator)) return false;
		List<LatticePropagator> updated=new ArrayList<>(propagators);
		updated.remove(propagator);
		propagators=List.copyOf(updated);
		runIsolated(propagator,"removal",propagator::close);
		return true;
	}

	private void requireAttached(LatticePropagator propagator) {
		if (propagator==null || !attachedPropagators.contains(propagator)) {
			throw new IllegalArgumentException("Propagation group is not attached to this node");
		}
	}

	private V publishApplicationRoot(V value) {
		return publishApplicationRoot(value,true);
	}

	private V publishApplicationRoot(V value,boolean notify) {
		try {
			@SuppressWarnings("unchecked")
			V announced=(V)publishAuthoritativeRoot(value,notify);
			return announced;
		} catch (IOException e) {
			throw new StoreException("NodeServer sync failed: persistence error",e);
		}
	}

	/** Publishes already-installed host state through the same ordered sync as
	 * explicit host publication. Private application state remains private. */
	private V syncSnapshot(boolean notify) throws IOException {
		try {
			return host.sync(current -> publishApplicationRoot(
				current,notify));
		} catch (StoreException e) {
			if (e.getCause() instanceof IOException cause) throw cause;
			throw e;
		}
	}

	/** Called within the host's ordered sync. The persistence lock also
	 * excludes checkpoints from cell announcement and the root-pointer write. */
	@SuppressWarnings("unchecked")
	private ACell publishAuthoritativeRoot(ACell value,boolean notify) throws IOException {
		ACell announced;
		synchronized (persistenceLock) {
			V candidate=(V)value;
			announced=Cells.announce(candidate,ignored -> {},store);
			// Persistence is the host's: a host with a store writes its root on every sync,
			// attached or not. The node's persist setting governs only checkpoints and
			// snapshot persistence.
			store.setRootData(announced);
			storeDirty=true;
		}
		if (notify) notifyPropagators(announced);
		return announced;
	}

	private void notifyPropagators(ACell value) {
		for (LatticePropagator propagator:propagators) {
			runIsolated(propagator,"update notification",
				() -> propagator.triggerBroadcast(value));
		}
	}

	private static void runIsolated(LatticePropagator propagator,String operation,
			ThrowingRunnable action) {
		try {
			action.run();
		} catch (VirtualMachineError e) {
			if (!(e instanceof StackOverflowError)) throw e;
			propagator.recordFailure(operation,e);
			log.warn("Contained propagation-group stack overflow during {}; isolated {}",
				operation,propagator);
			log.debug("Contained propagation-group stack overflow",e);
		} catch (Throwable e) {
			propagator.recordFailure(operation,e);
			log.warn("Propagation group failed during {}; isolated {}: {}: {}",
				operation,propagator,e.getClass().getSimpleName(),e.getMessage());
			log.debug("Isolated propagation-group failure",e);
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** Completed delivery; changed describes host state, not private application edits.
	 * An ignored value is still a completed delivery. Exceptions reject the delivery. */
	static record MergeOutcome(boolean accepted,boolean changed) {
		static final MergeOutcome REJECTED=new MergeOutcome(false,false);
	}

	/**
	 * Merges a complete value supplied by a propagator. No connection, message,
	 * acquisition or filtering concern crosses this boundary.
	 */
	MergeOutcome mergeInbound(ACell[] path,ACell value) {
		ACell before=cursor.get();
		if (!mergeIncoming(value,path)) return MergeOutcome.REJECTED;
		ACell after=cursor.get();
		boolean changed=before!=after && (before==null || !before.equals(after));
		if (changed) host.sync();
		return new MergeOutcome(true,changed);
	}

	boolean mergeIncoming(ACell value,ACell... path) {
		try {
			host.mergeAt(value,path);
			return true;
		} catch (ClassCastException e) {
			log.warn("Rejected inbound lattice value of wrong type: {}",
				(value==null) ? "null" : value.getClass().getSimpleName());
			return false;
		} catch (StackOverflowError e) {
			log.warn("Rejected inbound lattice value after stack overflow");
			return false;
		} catch (Exception e) {
			log.warn("Rejected inbound lattice value",e);
			return false;
		}
	}

	/**
	 * Delivers a snapshot through root merge, then publishes and durably flushes
	 * already-published host state without notifying propagation groups. Configured
	 * applications may retain the merged snapshot privately until their own sync;
	 * this method never invokes application publication. Without applications,
	 * current local state wins ties against the supplied snapshot.
	 *
	 * @param value snapshot candidate to persist; {@code null} is ignored
	 * @throws IOException if publication or the durability barrier fails
	 */
	@SuppressWarnings("unchecked")
	public void persistSnapshot(ACell value) throws IOException {
		if (!config.isPersist() || value==null) return;
		host.merge((V)value);
		syncSnapshot(false);
		checkpoint();
	}

	/**
	 * Completes a durability barrier for a dirty persistent node store.
	 *
	 * @return {@code true} if the store was flushed
	 * @throws IOException if the durability barrier fails
	 */
	public boolean checkpoint() throws IOException {
		if (!config.isPersist() || !store.isPersistent()) return false;
		synchronized (persistenceLock) {
			if (!storeDirty) return false;
			store.flush();
			storeDirty=false;
			return true;
		}
	}

	private void startMaintenanceService() {
		if (!config.isPersist() || !store.isPersistent() || config.getPersistInterval()<=0L) return;
		maintenanceThread=Thread.ofVirtual().name("NodeServer persistence maintenance")
			.start(this::maintenanceLoop);
	}

	private void maintenanceLoop() {
		long interval=config.getPersistInterval();
		while (lifecycleState==LifecycleState.STARTING || lifecycleState==LifecycleState.RUNNING) {
			try {
				synchronized (maintenanceSignal) {
					if (lifecycleState==LifecycleState.STARTING
							|| lifecycleState==LifecycleState.RUNNING) {
						maintenanceSignal.wait(interval);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			if (lifecycleState!=LifecycleState.STARTING && lifecycleState!=LifecycleState.RUNNING) return;
			try {
				checkpoint();
			} catch (IOException e) {
				log.warn("Periodic authoritative-store checkpoint failed; will retry",e);
			} catch (RuntimeException | StackOverflowError e) {
				log.warn("Contained authoritative-store maintenance failure; will retry",e);
			}
		}
	}

	private void stopMaintenance() {
		Thread maintenance=maintenanceThread;
		if (maintenance==null) return;
		synchronized (maintenanceSignal) {
			maintenanceSignal.notifyAll();
		}
		try {
			maintenance.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while stopping NodeServer persistence maintenance",e);
		}
		maintenanceThread=null;
	}

	private void registerShutdownPersistenceHook() {
		Shutdown.addHook(Shutdown.SERVER,shutdownHook);
		shutdownHookRegistered=true;
	}

	private void removeShutdownHook() {
		if (!shutdownHookRegistered) return;
		Shutdown.removeHook(Shutdown.SERVER,shutdownHook);
		shutdownHookRegistered=false;
	}

	private void shutdownPersist() {
		LifecycleState state=lifecycleState;
		if (state==LifecycleState.NEW || state==LifecycleState.STOPPED) return;
		try {
			close();
		} catch (IOException e) {
			log.warn("Error during NodeServer shutdown persistence",e);
		}
	}

	/**
	 * Drains each group independently, publishes the final host root and
	 * completes the node-store durability barrier.
	 *
	 * @throws IOException if final publication or persistence fails
	 */
	@Override
	public synchronized void close() throws IOException {
		try {
			closeResources();
		} finally {
			host.detachPublisher(publisher);
		}
	}

	private void closeResources() throws IOException {
		if (lifecycleState==LifecycleState.STOPPED) return;
		if (lifecycleState==LifecycleState.NEW) {
			// Attachment transfers lifecycle ownership even before launch. This matters
			// when an application has already supplied manager-owned peer connections.
			lifecycleState=LifecycleState.STOPPING;
			for (LatticePropagator propagator:propagators) {
				runIsolated(propagator,"pre-launch shutdown",propagator::close);
			}
			lifecycleState=LifecycleState.STOPPED;
			return;
		}
		if (lifecycleState==LifecycleState.STARTING) {
			throw new IllegalStateException(
				"Cannot close NodeServer re-entrantly while launch is in progress");
		}
		lifecycleState=LifecycleState.STOPPING;
		stopMaintenance();

		for (LatticePropagator propagator:propagators) {
			runIsolated(propagator,"ingress drain",propagator::stopIngress);
		}

		IOException nodeFailure=null;
		ACell finalRoot=cursor.get();
		try {
			finalRoot=syncSnapshot(true);
		} catch (IOException e) {
			nodeFailure=new IOException("Unable to publish final host root",e);
		}

		ACell snapshot=finalRoot;
		for (LatticePropagator propagator:propagators) {
			runIsolated(propagator,"shutdown",() -> propagator.triggerAndClose(snapshot));
		}

		try {
			checkpoint();
		} catch (IOException e) {
			if (nodeFailure==null) nodeFailure=e;
			else nodeFailure.addSuppressed(e);
		}

		lifecycleState=LifecycleState.STOPPED;
		removeShutdownHook();
		log.debug("NodeServer closed");
		if (nodeFailure!=null) throw nodeFailure;
	}
}
