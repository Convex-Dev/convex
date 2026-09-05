package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.exceptions.StoreException;
import convex.core.store.AStore;
import convex.core.util.Shutdown;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.RootComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;

/**
 * Hosts one authoritative lattice value and its durable root.
 *
 * <p>{@code NodeServer} owns application-state merge, root publication,
 * persistence and update notification. It does not own peer sets, connection
 * trust, protocol decoding, missing-cell
 * acquisition, ingress filtering, publication filtering, sockets or transport
 * lifecycle. Those are policies and resources of {@link LatticePropagator} and
 * application-owned transports such as {@link LatticeListener}.</p>
 *
 * <p>The calling application constructs and completely configures each
 * propagator before attaching it with {@link #addPropagator(LatticePropagator)}.
 * Attaching establishes only the authoritative merge target. It does not copy
 * the node lattice, merge context, transport key or filters into the group. A
 * node with no propagators is a valid local, store-backed lattice host.</p>
 *
 * <p>The calling application separately owns transport composition. It may
 * register one or more attached groups with a shared {@link LatticeListener},
 * give groups independent transports, or run this node without networking.
 * Transports must be closed before this server so no new ingress reaches groups
 * while they drain.</p>
 *
 * <p><b>Failure isolation.</b> Authoritative root publication is the mandatory
 * part of a root sync. Propagator initialisation, notification, worker and
 * shutdown failures are contained and logged independently; one propagation
 * policy group cannot fail a cursor sync, the server maintenance loop or another
 * group. Only failures of the authoritative store are surfaced by this class;
 * transport failures remain visible to their application owner.</p>
 *
 * @param <V> authoritative lattice value type
 */
public class NodeServer<V extends ACell> implements Closeable {

	private static final Logger log=LoggerFactory.getLogger(NodeServer.class);

	private final ALattice<V> lattice;
	private final AStore store;
	private final NodeConfig config;
	private final RootLatticeCursor<V> cursor;
	private final RootComponent<V> rootComponent;
	private final List<LatticePropagator> propagators=new ArrayList<>();
	private final Set<LatticePropagator> attachedPropagators=ConcurrentHashMap.newKeySet();

	/** Serialises all authoritative cell announcement, root-pointer and flush work. */
	private final Object persistenceLock=new Object();
	private boolean storeDirty;

	private LatticeContext mergeContext;
	private boolean rootPublicationConfigured;
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
	 * Creates a local lattice host. Propagation policy groups, if any, must be
	 * supplied separately by the calling application before launch.
	 *
	 * @param lattice authoritative merge semantics
	 * @param store authoritative cell store
	 * @param config authoritative persistence and validation configuration, or {@code null}
	 */
	public NodeServer(ALattice<V> lattice,AStore store,NodeConfig config) {
		if (lattice==null) throw new IllegalArgumentException("Lattice must not be null");
		if (store==null) throw new IllegalArgumentException("Store must not be null");
		this.lattice=lattice;
		this.store=store;
		this.config=(config==null) ? NodeConfig.create() : config;
		this.mergeContext=LatticeContext.EMPTY.withMaxFutureTimestampSkew(
			this.config.getMaxFutureTimestampSkew());
		this.cursor=Cursors.createLattice(lattice,lattice.zero(),mergeContext);
		this.rootComponent=new RootComponent<>(cursor,store);
	}

	/**
	 * Creates a lattice host with default persistence and validation configuration.
	 *
	 * @param lattice authoritative merge semantics
	 * @param store authoritative cell store
	 */
	public NodeServer(ALattice<V> lattice,AStore store) {
		this(lattice,store,null);
	}

	/**
	 * Restores and publishes the authoritative root, starts every attached policy
	 * group independently, then starts authoritative persistence maintenance.
	 *
	 * @throws IOException if authoritative store initialisation fails
	 */
	public synchronized void launch() throws IOException,InterruptedException {
		validateLaunchRequest();
		lifecycleState=LifecycleState.STARTING;
		try {
			configurePublicationPipeline();
			restorePersistedRoot();
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
		if (rootPublicationConfigured) return;
		rootComponent.setPublicationPolicy(this::publishApplicationRoot);
		rootComponent.freezePublicationPolicy();
		rootPublicationConfigured=true;
	}

	@SuppressWarnings("unchecked")
	private void restorePersistedRoot() {
		if (!config.isRestore() || !store.isPersistent()) return;
		try {
			ACell restored=store.getRootData();
			if (restored!=null) {
				cursor.set((V)restored);
				log.info("Restored authoritative lattice value from node store");
			}
		} catch (IOException e) {
			log.warn("Unable to restore authoritative lattice root",e);
		}
	}

	@SuppressWarnings("unchecked")
	private void seedPropagationViews() throws IOException {
		ACell announced=publishAuthoritativeRoot(cursor.get(),false);
		cursor.set((V)announced);
		for (LatticePropagator propagator:List.copyOf(propagators)) {
			runIsolated(propagator,"initial view materialisation",
				() -> propagator.processSnapshot(announced));
		}
		if (config.isPersist()) checkpoint();
	}

	private void startPropagationServices() {
		for (LatticePropagator propagator:List.copyOf(propagators)) {
			runIsolated(propagator,"launch",propagator::start);
		}
	}

	private void abortFailedLaunch(Throwable failure) {
		lifecycleState=LifecycleState.STOPPING;
		stopMaintenance();
		for (LatticePropagator propagator:List.copyOf(propagators)) {
			try {
				propagator.close();
			} catch (Throwable cleanupFailure) {
				addCleanupFailure(failure,cleanupFailure);
			}
		}
		lifecycleState=LifecycleState.STOPPED;
		removeShutdownHook();
	}

	private static void addCleanupFailure(Throwable failure,Throwable cleanupFailure) {
		if (failure!=cleanupFailure) failure.addSuppressed(cleanupFailure);
	}

	/**
	 * Pulls a complete root through an explicitly chosen propagation group and
	 * merges it into the authoritative node.
	 *
	 * @param source group whose serving store receives acquired cells
	 * @param peer manager-owned peer client to query through {@code source}
	 * @return future containing the authoritative value after merge and sync
	 */
	public CompletableFuture<V> pull(LatticePropagator source,Convex peer) {
		requireAttached(source);
		return source.pull(peer).thenApply(acquired -> {
			mergePulledValue(acquired);
			cursor.sync();
			return cursor.get();
		});
	}

	/**
	 * Pulls one path through an explicitly chosen propagation group.
	 *
	 * @param source group whose serving store receives acquired cells
	 * @param peer manager-owned peer client to query through {@code source}
	 * @param path path within remote and local roots; empty selects the root
	 * @return future containing the authoritative value at {@code path}
	 */
	public CompletableFuture<ACell> pullPath(LatticePropagator source,Convex peer,ACell... path) {
		requireAttached(source);
		return source.pullPath(peer,path).thenApply(acquired -> {
			ALatticeCursor<ACell> target=cursor.path(path);
			if (acquired!=null) mergeIncoming(target,acquired);
			cursor.sync();
			return target.get();
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
	 * @return future containing the authoritative value after merge and sync
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
	 * @return future containing the authoritative value at {@code path}
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
	 * propagation group and publishes the combined authoritative value.
	 *
	 * @param source application-selected attached group
	 * @return {@code true} if every pull completed successfully
	 */
	public boolean pull(LatticePropagator source) {
		requireAttached(source);
		try {
			for (ACell acquired:source.pullAll().get(30,TimeUnit.SECONDS)) {
				mergePulledValue(acquired);
			}
			cursor.sync();
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
	 * @return future containing the authoritative value after merge and sync
	 */
	@Deprecated
	public CompletableFuture<V> syncWithPeer(Convex peer) {
		return pull(peer);
	}

	@SuppressWarnings("unchecked")
	private void mergePulledValue(ACell acquired) {
		if (acquired==null) return;
		cursor.updateAndGet(current -> lattice.merge(mergeContext,current,(V)acquired));
	}

	/**
	 * Merges an application-supplied value into memory. Call {@link ALatticeCursor#sync()}
	 * when the update should be published and persisted.
	 *
	 * @param receivedValue complete foreign lattice value
	 * @return merged value, or {@code null} if the fast foreign-value check rejects it
	 */
	public V mergeValue(V receivedValue) {
		if (receivedValue==null || !lattice.checkForeign(receivedValue)) return null;
		return cursor.merge(receivedValue);
	}

	/**
	 * Returns the current authoritative in-memory value.
	 *
	 * @return current authoritative value
	 */
	public V getLocalValue() {
		return cursor.get();
	}

	/**
	 * Returns the authoritative root cursor.
	 *
	 * @return authoritative cursor
	 */
	public ALatticeCursor<V> getCursor() {
		return cursor;
	}

	/**
	 * Returns the application-facing root component hosted by this node.
	 *
	 * @return hosted root component
	 */
	public RootComponent<V> getRootComponent() {
		return rootComponent;
	}

	/**
	 * Configures only the authoritative node merge context. Calling applications
	 * configure each propagator's view context independently before attaching it.
	 *
	 * @param context authoritative validation and merge context
	 */
	public synchronized void setMergeContext(LatticeContext context) {
		if (context==null) throw new IllegalArgumentException(
			"Use LatticeContext.EMPTY instead of null");
		requireNewLifecycle("setMergeContext");
		long configuredSkew=config.getMaxFutureTimestampSkew();
		long effectiveSkew=config.getMap().containsKey(NodeConfig.MAX_FUTURE_TIMESTAMP_SKEW)
			? configuredSkew : context.getMaxFutureTimestampSkew(configuredSkew);
		mergeContext=context.withMaxFutureTimestampSkew(effectiveSkew);
		cursor.setContext(mergeContext);
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
	 * Returns the authoritative lattice definition.
	 *
	 * @return authoritative lattice
	 */
	public ALattice<V> getLattice() {
		return lattice;
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
		return propagators.isEmpty() ? null : propagators.get(0);
	}

	/**
	 * Returns an immutable snapshot of attached propagation policy groups.
	 *
	 * @return attached groups in attachment order
	 */
	public List<LatticePropagator> getPropagators() {
		return List.copyOf(propagators);
	}

	/**
	 * Attaches an already-configured propagation policy group.
	 *
	 * <p>This operation deliberately performs no policy configuration. In
	 * particular it does not supply a lattice, merge context, identity, filters,
	 * handlers or peer routes. The application must set those directly on the
	 * propagator before calling this method. Attachment transfers lifecycle
	 * ownership: the node starts and closes the group with its own resources.</p>
	 *
	 * @param propagator lattice-configured policy group to attach
	 */
	public synchronized void addPropagator(LatticePropagator propagator) {
		if (propagator==null) throw new IllegalArgumentException("Propagator must not be null");
		requireNewLifecycle("addPropagator");
		if (attachedPropagators.contains(propagator)) {
			throw new IllegalArgumentException("Propagator is already attached to this node");
		}
		propagator.attach(this);
		propagators.add(propagator);
		attachedPropagators.add(propagator);
	}

	private void requireAttached(LatticePropagator propagator) {
		if (propagator==null || !attachedPropagators.contains(propagator)) {
			throw new IllegalArgumentException("Propagation group is not attached to this node");
		}
	}

	private void requireNewLifecycle(String operation) {
		if (lifecycleState!=LifecycleState.NEW) {
			throw new IllegalStateException(
				operation+" is configuration-only and must precede first launch");
		}
	}

	private V publishApplicationRoot(V value) {
		try {
			@SuppressWarnings("unchecked")
			V announced=(V)publishAuthoritativeRoot(value,true);
			return announced;
		} catch (IOException e) {
			throw new StoreException("NodeServer sync failed: persistence error",e);
		}
	}

	/** Serialises the sole authoritative root write. Root-cursor sync callbacks are
	 * themselves ordered, while this lock also excludes explicit persistence and
	 * checkpoint operations. */
	@SuppressWarnings("unchecked")
	private ACell publishAuthoritativeRoot(ACell value,boolean notify) throws IOException {
		ACell announced;
		synchronized (persistenceLock) {
			V candidate=(V)value;
			announced=Cells.announce(candidate,ignored -> {},store);
			if (config.isPersist()) {
				store.setRootData(announced);
				storeDirty=true;
			}
		}
		if (notify) notifyPropagators(announced);
		return announced;
	}

	private void notifyPropagators(ACell value) {
		for (LatticePropagator propagator:List.copyOf(propagators)) {
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

	/** Result of one complete foreign-value merge at the authoritative boundary. */
	static record MergeOutcome(boolean accepted,boolean changed) {
		static final MergeOutcome REJECTED=new MergeOutcome(false,false);
	}

	/**
	 * Merges a complete value supplied by a propagator. No connection, message,
	 * acquisition or filtering concern crosses this boundary.
	 */
	MergeOutcome mergeInbound(ACell[] path,ACell value) {
		ALatticeCursor<ACell> target;
		try {
			target=cursor.path(path);
		} catch (RuntimeException | StackOverflowError e) {
			log.debug("Rejected invalid inbound lattice path",e);
			return MergeOutcome.REJECTED;
		}
		ACell before=target.get();
		if (!mergeIncoming(target,value)) return MergeOutcome.REJECTED;
		ACell after=target.get();
		boolean changed=before!=after && (before==null || !before.equals(after));
		if (changed) cursor.sync();
		return new MergeOutcome(true,changed);
	}

	@SuppressWarnings("unchecked")
	<T extends ACell> boolean mergeIncoming(ALatticeCursor<T> target,ACell value) {
		try {
			target.merge((T)value);
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
	 * Publishes and durably flushes an authoritative snapshot without notifying
	 * propagation groups.
	 *
	 * @param value authoritative snapshot to persist; {@code null} is ignored
	 * @throws IOException if publication or the durability barrier fails
	 */
	public void persistSnapshot(ACell value) throws IOException {
		if (!config.isPersist() || value==null) return;
		publishAuthoritativeRoot(value,false);
		checkpoint();
	}

	/**
	 * Completes a durability barrier for a dirty persistent authoritative store.
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
	 * Drains each group independently, publishes the
	 * final authoritative root and completes the node-store durability barrier.
	 *
	 * @throws IOException if final authoritative publication or persistence fails
	 */
	@Override
	public synchronized void close() throws IOException {
		if (lifecycleState==LifecycleState.STOPPED) return;
		if (lifecycleState==LifecycleState.NEW) {
			// Attachment transfers lifecycle ownership even before launch. This matters
			// when an application has already supplied manager-owned peer connections.
			lifecycleState=LifecycleState.STOPPING;
			for (LatticePropagator propagator:List.copyOf(propagators)) {
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

		for (LatticePropagator propagator:List.copyOf(propagators)) {
			runIsolated(propagator,"ingress drain",propagator::stopIngress);
		}

		IOException nodeFailure=null;
		ACell finalRoot=cursor.get();
		try {
			finalRoot=publishAuthoritativeRoot(finalRoot,true);
		} catch (IOException e) {
			nodeFailure=new IOException("Unable to publish final authoritative lattice root",e);
		}

		ACell snapshot=finalRoot;
		for (LatticePropagator propagator:List.copyOf(propagators)) {
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
