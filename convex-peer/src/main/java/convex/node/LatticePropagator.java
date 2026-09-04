package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.message.AConnection;
import convex.core.store.AStore;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.lattice.cursor.Root;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.RootLatticeCursor;

/**
 * Isolated connection and publication policy group for one lattice node.
 *
 * <p>A propagator owns every external route assigned to the group, its bounded
 * CAD036 protocol endpoint, serving store, ingress and publication filters,
 * novelty tracking and broadcast worker. A node normally has one group, while
 * multiple groups may expose different filtered views without sharing queues,
 * connection state or acquisition capability.</p>
 *
 * <p>Complete inbound values are decoded, acquired and projected inside this
 * boundary before being offered to {@link NodeServer}. The node alone performs
 * the authoritative lattice merge and writes its durable store root. Once that
 * commit succeeds the node notifies every propagator independently; failure in
 * one group is contained and cannot prevent another group from seeing the update.</p>
 *
 * <p>The group's store is a serving and novelty cache. DATA_REQUEST responses can
 * resolve only cells materialised in this exact store. A standalone propagator
 * may still opt into a local cached root for compatibility, but an attached node
 * always remains the sole authoritative root writer.</p>
 */
public class LatticePropagator implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LatticePropagator.class.getName());

	/**
	 * Interval between root-only sync broadcasts (milliseconds).
	 * Provides a lightweight periodic sync mechanism for divergence detection.
	 */
	public static final long ROOT_SYNC_INTERVAL = 30_000L;

	/**
	 * Serving store for novelty tracking and peer data resolution. Missing data
	 * requests for an announced value are resolved only from this store. An
	 * unattached compatibility instance may also retain its own root here.
	 */
	private final AStore store;

	/**
	 * Connection manager for outbound peer connections and broadcast.
	 */
	private final LatticeConnectionManager connectionManager;

	/** Limits and timing selected by the calling application for this group. */
	private final LatticePropagatorConfig config;

	/** Lattice used to reconcile this propagator's own subset with new snapshots. */
	private ALattice<ACell> lattice;

	/** Context used only to reconcile this group's served view. */
	private LatticeContext mergeContext = LatticeContext.EMPTY;

	/** Projection applied before any value crosses this propagator's store boundary. */
	private LatticeFilter<ACell> filter = value -> value;

	/**
	 * This propagator's current logical view. It may contain accepted inbound values
	 * which have not yet appeared in a later projection from the authoritative root.
	 */
	private RootLatticeCursor<ACell> workingCursor;

	/** Authoritative merge target supplied by the owning node before launch. */
	private NodeServer<?> node;

	/** Connection-facing endpoint created when this group is attached to a node. */
	private LatticeProtocolEndpoint endpoint;

	/** Endpoint configuration retained until attachment. */
	private AKeyPair transportKeyPair;
	private LatticeIngressFilter ingressFilter=(path,value) -> value;
	private Predicate<Message> applicationMessageHandler;
	private InboundLatticeListener inboundLatticeListener;

	/** Background publication worker. */
	private Thread propagationThread;

	/** Whether this group's workers are running. */
	private volatile boolean running = false;

	/** Serialises observable lifecycle and failure reporting. */
	private final Object statusLock=new Object();
	private volatile Status status=new Status(State.NEW,0L,null);
	private volatile CompletableFuture<Failure> nextFailureFuture=new CompletableFuture<>();

	/**
	 * Queue for receiving lattice values to process.
	 * Uses LatestUpdateQueue which only stores the most recent value,
	 * coalescing rapid updates into a single processing of the latest state.
	 * Safe because lattice values are monotonic (V2 >= V1 implies V1 is subsumed).
	 */
	private final LatestUpdateQueue<ACell> triggerQueue = new LatestUpdateQueue<>();

	/** Whether a standalone propagator caches its view as the store root. */
	private volatile boolean persistenceEnabled = true;

	/** Maximum encoded body size for one outbound delta or DATA-ahead chunk. */
	private volatile int maxDeltaMessageSize = LatticePropagatorConfig.DEFAULT_MAX_MESSAGE_SIZE;

	/** Maximum combined encoded bodies materialised for one eager delta broadcast. */
	private volatile int maxDeltaBroadcastSize =
		LatticePropagatorConfig.DEFAULT_MAX_DELTA_BROADCAST_SIZE;

	/** Whether root publication has changed the persistent store since its last checkpoint. */
	private boolean dirty;

	/**
	 * Cursor holding the last value announced to this propagator's store.
	 *
	 * <p>This is the propagator's cached view of what it has published — the
	 * store-backed snapshot it most recently announced. LATTICE_QUERY
	 * responses are served from this cursor, so peers only see data this
	 * propagator has actually committed. NodeServer exposes this view only to
	 * connections assigned to this propagator; reverse DATA_REQUEST resolution
	 * uses the same store.</p>
	 *
	 * <p>Each propagator owns its own announced cursor. This keeps query and data
	 * access scoped to one store. Filtering and working-view reconciliation complete
	 * before this cursor advances.</p>
	 */
	private final Root<ACell> announcedCursor = new Root<>();

	/**
	 * Timestamp of last broadcast. Volatile because explicit standalone publication
	 * and the background propagation worker may both update it.
	 */
	private volatile long lastBroadcastTime = 0L;

	/**
	 * Timestamp of last root sync broadcast (background thread only).
	 */
	private long lastRootSyncTime = 0L;

	/**
	 * Count of broadcasts sent. Atomic because both the caller's thread and
	 * the background thread may increment.
	 */
	private final java.util.concurrent.atomic.AtomicLong broadcastCount = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Count of root sync broadcasts sent
	 */
	private long rootSyncCount = 0L;

	/**
	 * Serialises this group's working view, serving-store materialisation and
	 * announcement cursor. Attached groups never write the authoritative node root;
	 * their only store-root writes are disabled at attachment. The lock also keeps
	 * the optional standalone compatibility root ordered with announcements.
	 */
	private final Object writeLock = new Object();

	/**
	 * Creates a propagation group with default limits and no lattice projection.
	 * The application must supply a lattice through another constructor before
	 * attaching the group to a node.
	 *
	 * @param store serving store for delta tracking and data resolution
	 * @param connectionManager connection manager for outbound routes
	 * @deprecated Use a lattice-configured constructor before attachment.
	 */
	@Deprecated
	public LatticePropagator(AStore store, LatticeConnectionManager connectionManager) {
		this(store,connectionManager,LatticePropagatorConfig.create());
	}

	/**
	 * Compatibility constructor accepting the former combined configuration.
	 *
	 * @param store serving store for delta tracking and data resolution
	 * @param connectionManager connection manager for outbound routes
	 * @param config legacy combined configuration
	 * @deprecated Supply {@link LatticePropagatorConfig} explicitly.
	 */
	@Deprecated
	public LatticePropagator(AStore store,LatticeConnectionManager connectionManager,
			NodeConfig config) {
		this(store,connectionManager,legacyConfig(config));
	}

	/**
	 * Creates a propagation group with application-supplied limits and no lattice
	 * projection. Such a standalone compatibility group cannot be attached until
	 * a lattice is supplied through a lattice-configured constructor.
	 *
	 * @param store serving store for delta tracking and data resolution
	 * @param connectionManager connection manager for outbound routes
	 * @param config queue, transport and publication limits
	 */
	public LatticePropagator(AStore store,LatticeConnectionManager connectionManager,
			LatticePropagatorConfig config) {
		if (store == null) throw new IllegalArgumentException("Store must not be null");
		if (connectionManager == null) throw new IllegalArgumentException("ConnectionManager must not be null");
		if (config==null) throw new IllegalArgumentException("Propagation config must not be null");
		this.store = store;
		this.connectionManager = connectionManager;
		this.config=config;
		// Fail while the application is composing the group, before attachment can
		// open any endpoint resource.
		config.getInboundShutdownTimeout();
		this.maxDeltaMessageSize=config.getMaxDeltaMessageSize();
		this.maxDeltaBroadcastSize=config.getMaxDeltaBroadcastSize();
		connectionManager.setMaxDesiredPeers(config.getMaxDesiredPeers());
		connectionManager.setInboundMessageLimits(
			config.getMaxMessageSize(),config.getMaxTrustedMessageSize());
	}

	/**
	 * Creates a propagation group with a new connection manager, default limits
	 * and no lattice projection.
	 *
	 * @param store serving store for delta tracking and peer data resolution
	 * @deprecated Use a lattice-configured constructor before attachment.
	 */
	@Deprecated
	public LatticePropagator(AStore store) {
		this(store,new LatticeConnectionManager(store),LatticePropagatorConfig.create());
	}

	/**
	 * Creates a propagator which owns a lattice projection and reconciles later
	 * snapshots with its current view using current/propagator state as {@code own}.
	 *
	 * @param store serving store for this group
	 * @param lattice merge semantics for the served view
	 * @param filter publication projection
	 * @param <V> served lattice value type
	 */
	@SuppressWarnings("unchecked")
	public <V extends ACell> LatticePropagator(AStore store, ALattice<V> lattice,
			LatticeFilter<V> filter) {
		this(store,new LatticeConnectionManager(store),lattice,filter,
			LatticePropagatorConfig.create());
	}

	/**
	 * Creates a lattice-configured propagation group with a new connection manager
	 * and application-supplied limits.
	 *
	 * @param store serving store for this group
	 * @param lattice merge semantics for the served view
	 * @param filter publication projection
	 * @param config legacy combined configuration
	 * @param <V> served lattice value type
	 * @deprecated Supply {@link LatticePropagatorConfig} explicitly.
	 */
	@Deprecated
	public <V extends ACell> LatticePropagator(AStore store,ALattice<V> lattice,
			LatticeFilter<V> filter,NodeConfig config) {
		this(store,new LatticeConnectionManager(store),lattice,filter,legacyConfig(config));
	}

	/**
	 * Creates a lattice-configured propagation group with a new connection manager
	 * and application-supplied limits.
	 *
	 * @param store serving store for this group
	 * @param lattice merge semantics for the served view
	 * @param filter publication projection
	 * @param config queue, transport and publication limits
	 * @param <V> served lattice value type
	 */
	public <V extends ACell> LatticePropagator(AStore store,ALattice<V> lattice,
			LatticeFilter<V> filter,LatticePropagatorConfig config) {
		this(store,new LatticeConnectionManager(store),lattice,filter,config);
	}

	/**
	 * Creates a lattice-configured propagation group with an explicit connection
	 * manager and default limits.
	 *
	 * @param store serving store for this group
	 * @param connectionManager connection manager for outbound routes
	 * @param lattice merge semantics for the served view
	 * @param filter publication projection
	 * @param <V> served lattice value type
	 */
	@SuppressWarnings("unchecked")
	public <V extends ACell> LatticePropagator(AStore store,
			LatticeConnectionManager connectionManager, ALattice<V> lattice,
			LatticeFilter<V> filter) {
		this(store,connectionManager,lattice,filter,LatticePropagatorConfig.create());
	}

	/**
	 * Creates a lattice-configured propagation group with an explicit connection
	 * manager and limits. Identity, admission and application hooks remain optional
	 * application policy configured before attachment.
	 *
	 * @param store serving store for this group
	 * @param connectionManager connection manager for outbound routes
	 * @param lattice merge semantics for the served view
	 * @param filter publication projection
	 * @param config legacy combined configuration
	 * @param <V> served lattice value type
	 * @deprecated Supply {@link LatticePropagatorConfig} explicitly.
	 */
	@Deprecated
	@SuppressWarnings("unchecked")
	public <V extends ACell> LatticePropagator(AStore store,
			LatticeConnectionManager connectionManager,ALattice<V> lattice,
			LatticeFilter<V> filter,NodeConfig config) {
		this(store,connectionManager,lattice,filter,legacyConfig(config));
	}

	/**
	 * Creates a lattice-configured propagation group with an explicit connection
	 * manager and limits. Identity, admission and application hooks remain optional
	 * application policy configured before attachment.
	 *
	 * @param store serving store for this group
	 * @param connectionManager connection manager for outbound routes
	 * @param lattice merge semantics for the served view
	 * @param filter publication projection
	 * @param config queue, transport and publication limits
	 * @param <V> served lattice value type
	 */
	@SuppressWarnings("unchecked")
	public <V extends ACell> LatticePropagator(AStore store,
			LatticeConnectionManager connectionManager,ALattice<V> lattice,
			LatticeFilter<V> filter,LatticePropagatorConfig config) {
		this(store,connectionManager,config);
		if (lattice == null) throw new IllegalArgumentException("Lattice must not be null");
		if (filter == null) throw new IllegalArgumentException("Lattice filter must not be null");
		this.lattice = (ALattice<ACell>) lattice;
		this.filter = (LatticeFilter<ACell>) filter;
	}

	private static LatticePropagatorConfig legacyConfig(NodeConfig config) {
		if (config==null) throw new IllegalArgumentException("Propagation config must not be null");
		return LatticePropagatorConfig.from(config);
	}

	/** Observable lifecycle of one propagation policy group. */
	public enum State {
		/** Constructed and not started. */
		NEW,
		/** Group resources are starting. */
		STARTING,
		/** Group workers are active. */
		RUNNING,
		/** Group resources are shutting down. */
		STOPPING,
		/** Group resources have stopped or failed to start. */
		STOPPED
	}

	/**
	 * One contained propagation-group failure.
	 *
	 * @param sequence monotonically increasing failure sequence
	 * @param operation operation which failed
	 * @param cause contained failure
	 * @param timestamp wall-clock time in milliseconds
	 */
	public record Failure(long sequence,String operation,Throwable cause,long timestamp) {}

	/**
	 * Immutable operational snapshot for one propagation policy group.
	 *
	 * @param state current lifecycle state
	 * @param failureCount contained failures since construction
	 * @param lastFailure most recent contained failure, or {@code null}
	 */
	public record Status(State state,long failureCount,Failure lastFailure) {
		/**
		 * Returns whether the group's workers are active.
		 *
		 * @return {@code true} while the group is running
		 */
		public boolean isOperational() {
			return state==State.RUNNING;
		}

		/**
		 * Returns whether this group has reported a contained failure.
		 *
		 * @return {@code true} after at least one contained failure
		 */
		public boolean hasFailures() {
			return failureCount>0L;
		}
	}

	/**
	 * Returns the current lifecycle and failure snapshot.
	 *
	 * @return immutable status snapshot
	 */
	public Status getStatus() {
		return status;
	}

	/**
	 * Returns a future for the next contained failure after this call.
	 *
	 * @return next failure signal
	 */
	public CompletableFuture<Failure> nextFailure() {
		return nextFailureFuture;
	}

	private void transition(State state) {
		synchronized (statusLock) {
			Status current=status;
			status=new Status(state,current.failureCount(),current.lastFailure());
		}
	}

	/** Records a contained component failure without throwing into the node host. */
	final void recordFailure(String operation,Throwable cause) {
		if (cause==null) return;
		CompletableFuture<Failure> signal;
		Failure failure;
		synchronized (statusLock) {
			Status current=status;
			Failure previous=current.lastFailure();
			if (previous!=null && previous.cause()==cause
					&& java.util.Objects.equals(previous.operation(),operation)) return;
			long sequence=current.failureCount()+1L;
			failure=new Failure(sequence,operation,cause,Utils.getCurrentTimestamp());
			status=new Status(current.state(),sequence,failure);
			signal=nextFailureFuture;
			nextFailureFuture=new CompletableFuture<>();
		}
		try {
			signal.complete(failure);
		} catch (RuntimeException | StackOverflowError observerFailure) {
			// Health observers are application policy too. Reporting degradation must
			// not turn it into a NodeServer failure.
			log.warn("Propagation failure observer failed",observerFailure);
		}
	}

	/**
	 * Attaches this already-configured policy group to its authoritative node.
	 * Attachment supplies only the merge destination and creates the group's own
	 * protocol endpoint; it does not inherit policy from the node.
	 */
	@SuppressWarnings("unchecked")
	void attach(NodeServer<?> node) {
		synchronized (writeLock) {
			if (running) throw new IllegalStateException("Cannot attach a running propagator");
			if (node==null) throw new IllegalArgumentException("Owning node must not be null");
			if (this.node!=null && this.node!=node) {
				throw new IllegalStateException("Propagator is already attached to another node");
			}
			if (lattice==null) {
				throw new IllegalStateException("Calling application must configure the propagator lattice");
			}
			this.node=node;
			// NodeServer is the sole writer of its authoritative store root.
			this.persistenceEnabled=false;
			if (endpoint==null) endpoint=new LatticeProtocolEndpoint(this,node,config);
			endpoint.setTransportKeyPair(transportKeyPair);
			endpoint.setIngressFilter(ingressFilter);
			endpoint.setApplicationMessageHandler(applicationMessageHandler);
			endpoint.setInboundLatticeListener(inboundLatticeListener);
		}
	}

	// ========== Configuration ==========

	/**
	 * Enables or disables root retention for a standalone propagator. Announcement
	 * still runs when disabled because it provides serving references and novelty.
	 * Attached groups cannot enable root retention because the node owns that root.
	 *
	 * @param enabled {@code true} to retain the served view as this store's root
	 */
	public void setPersistenceEnabled(boolean enabled) {
		if (node!=null && enabled) {
			throw new IllegalStateException("An attached propagator cannot own the node store root");
		}
		this.persistenceEnabled = enabled;
	}

	/**
	 * Sets the lattice context used only to reconcile this group's filtered view.
	 * Authoritative foreign-value validation remains inside the attached NodeServer.
	 *
	 * @param context view reconciliation context; {@code null} selects an empty context
	 */
	public void setMergeContext(LatticeContext context) {
		requireUnattachedPolicy("Merge context");
		this.mergeContext=(context==null) ? LatticeContext.EMPTY : context;
		if (workingCursor!=null) workingCursor.setContext(this.mergeContext);
	}

	/**
	 * Sets the key used by this group's challenge/response endpoint.
	 *
	 * @param keyPair transport identity, or {@code null} for unverified outbound routes
	 */
	public void setTransportKeyPair(AKeyPair keyPair) {
		requireUnattachedPolicy("Transport identity");
		this.transportKeyPair=keyPair;
		if (endpoint!=null) endpoint.setTransportKeyPair(keyPair);
		connectionManager.setKeyPair(keyPair);
	}

	/**
	 * Sets complete-value admission and projection for this group.
	 *
	 * @param filter inbound policy; returning {@code null} rejects a value
	 */
	public void setIngressFilter(LatticeIngressFilter filter) {
		requireUnattachedPolicy("Ingress policy");
		if (filter==null) throw new IllegalArgumentException("Ingress filter must not be null");
		this.ingressFilter=filter;
		if (endpoint!=null) endpoint.setIngressFilter(filter);
	}

	/**
	 * Sets the projection applied before this group materialises or broadcasts a view.
	 *
	 * @param filter publication projection
	 * @param <V> projected lattice value type
	 */
	@SuppressWarnings("unchecked")
	public <V extends ACell> void setPublicationFilter(LatticeFilter<V> filter) {
		requireUnattachedPolicy("Publication policy");
		if (filter==null) throw new IllegalArgumentException("Publication filter must not be null");
		this.filter=(LatticeFilter<ACell>)filter;
	}

	/**
	 * Sets the handler for complete application messages arriving through this group.
	 *
	 * @param handler application handler, or {@code null} to reject extension messages
	 */
	public void setApplicationMessageHandler(Predicate<Message> handler) {
		requireUnattachedPolicy("Application handler");
		this.applicationMessageHandler=handler;
		if (endpoint!=null) endpoint.setApplicationMessageHandler(handler);
	}

	/**
	 * Sets the post-merge observer for values received through this group.
	 *
	 * @param listener observer, or {@code null} for none
	 */
	public void setInboundLatticeListener(InboundLatticeListener listener) {
		requireUnattachedPolicy("Inbound listener");
		this.inboundLatticeListener=listener;
		if (endpoint!=null) endpoint.setInboundLatticeListener(listener);
	}

	private void requireUnattachedPolicy(String policy) {
		if (node!=null || running) {
			throw new IllegalStateException(
				policy+" must be configured by the application before attachment");
		}
	}

	/**
	 * Configures the encoded body limit for outbound delta chunks.
	 *
	 * @param limit maximum encoded bytes per chunk
	 */
	public void setMaxDeltaMessageSize(int limit) {
		if (limit<1 || limit>CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("Delta message limit must be between 1 and "
				+CPoSConstants.MAX_MESSAGE_LENGTH+": "+limit);
		}
		this.maxDeltaMessageSize=limit;
		if (maxDeltaBroadcastSize<limit) maxDeltaBroadcastSize=limit;
	}

	/**
	 * Returns the encoded body limit for outbound delta chunks.
	 *
	 * @return maximum encoded bytes per chunk
	 */
	public int getMaxDeltaMessageSize() {
		return maxDeltaMessageSize;
	}

	/**
	 * Configures the total encoded working-set limit for one eager delta.
	 *
	 * @param limit maximum combined encoded bytes
	 */
	public void setMaxDeltaBroadcastSize(int limit) {
		if (limit<maxDeltaMessageSize || limit>CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("Delta broadcast limit must be between "
				+maxDeltaMessageSize+" and "+CPoSConstants.MAX_MESSAGE_LENGTH+": "+limit);
		}
		this.maxDeltaBroadcastSize=limit;
	}

	/**
	 * Returns the encoded working-set limit for one eager delta.
	 *
	 * @return maximum combined encoded bytes
	 */
	public int getMaxDeltaBroadcastSize() {
		return maxDeltaBroadcastSize;
	}

	// ========== Accessors ==========

	/**
	 * Returns the connection manager owned by this propagator.
	 *
	 * @return connection manager owned by this group
	 */
	public LatticeConnectionManager getConnectionManager() {
		return connectionManager;
	}

	/**
	 * Returns the serving store owned by this propagator.
	 *
	 * @return serving, acquisition and novelty store
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Assigns a physically inbound connection to this propagation policy group.
	 * Assignment permits this endpoint to process the connection; it does not
	 * authenticate the remote node or add an outbound route.
	 */
	public void attachInboundConnection(AConnection connection) {
		requireEndpoint().attachInbound(connection);
	}

	/** Returns whether this group owns the supplied physical inbound connection. */
	boolean ownsInboundConnection(AConnection connection) {
		return endpoint!=null && endpoint.ownsInbound(connection);
	}

	/**
	 * Delivers one transport message without blocking the transport event loop.
	 * Custom inbound transports call this only after assigning the message's
	 * physical connection with {@link #attachInboundConnection(AConnection)}.
	 *
	 * @param message inbound message carrying its physical connection
	 * @return backpressure predicate, or {@code null} when delivery was accepted
	 */
	public Predicate<Message> deliverIncomingMessage(Message message) {
		return requireEndpoint().deliver(message);
	}

	/**
	 * Releases all state held by this group for a closed physical connection.
	 * Custom transports must call this when a previously assigned connection
	 * disconnects.
	 *
	 * @param connection disconnected physical connection
	 */
	public void removeInboundConnection(AConnection connection) {
		if (endpoint!=null) endpoint.removeConnection(connection);
	}

	/** Package-visible maintenance hook for deterministic endpoint tests. */
	void sweepClosedInboundConnections() {
		if (endpoint!=null) endpoint.sweepClosedConnections();
	}

	/**
	 * Starts non-blocking possession verification for an assigned inbound
	 * connection. The expected key must already be in this group's bounded
	 * desired-peer set. On success, the full-duplex socket becomes an authenticated
	 * outbound route; callers may await
	 * {@link LatticeConnectionManager#whenInboundConnectionUpgraded(AccountKey)}.
	 *
	 * @param connection assigned physical inbound connection
	 * @param expectedKey expected remote transport key
	 */
	public void authenticateInboundRoute(AConnection connection,AccountKey expectedKey) {
		requireEndpoint().authenticateInbound(connection,expectedKey);
	}

	/**
	 * Returns aggregate ingress counters for this attached policy group.
	 *
	 * @return immutable counter snapshot
	 */
	public InboundStats getInboundStats() {
		LatticeProtocolEndpoint.InboundStats stats=requireEndpoint().getInboundStats();
		return new InboundStats(stats.connections(),stats.messagesReceived(),stats.mergesAccepted(),
			stats.mergesRejected(),stats.decodeErrors());
	}

	/**
	 * Immutable aggregate of connection-scoped ingress counters.
	 *
	 * @param connections connections for which statistics are retained
	 * @param messagesReceived messages accepted by the endpoint dispatcher
	 * @param mergesAccepted complete values accepted at the merge boundary
	 * @param mergesRejected complete values rejected at the merge boundary
	 * @param decodeErrors messages rejected during decoding or acquisition
	 */
	public record InboundStats(long connections,long messagesReceived,long mergesAccepted,
		long mergesRejected,long decodeErrors) {}

	/** Synchronous protocol hook retained for deterministic package tests. */
	void handleIncomingMessage(Message message) {
		requireEndpoint().handle(message);
	}

	/** Package-visible size-policy probe for endpoint tests. */
	boolean withinInboundSizeLimit(ACell value) {
		return ACell.getMemorySize(value)<=config.getMaxInboundValueSize();
	}

	private LatticeProtocolEndpoint requireEndpoint() {
		LatticeProtocolEndpoint result=endpoint;
		if (result==null) throw new IllegalStateException("Propagator is not attached to a NodeServer");
		return result;
	}

	// ========== Peer Management ==========

	/**
	 * Submits an existing outbound peer client for admission under a known
	 * identity. The connection manager applies challenge policy and binds admitted
	 * data access to this group's serving store.
	 *
	 * @param peerKey expected remote node key
	 * @param peer connected client to admit
	 * @return future completed with the admitted client after any required identity
	 *         challenge, or exceptionally when admission fails
	 */
	public CompletableFuture<Convex> addPeer(AccountKey peerKey, Convex peer) {
		return connectionManager.addPeer(peerKey, peer);
	}

	/**
	 * Removes a peer by identity, closing the connection if active.
	 *
	 * @param peerKey remote node key
	 */
	public void removePeer(AccountKey peerKey) {
		connectionManager.removePeer(peerKey);
	}

	/**
	 * Returns a snapshot of admitted manager-owned outbound clients. Upgraded
	 * listener-owned routes are not represented as {@link Convex} clients.
	 *
	 * @return defensive copy of the peer set
	 */
	public Set<Convex> getPeers() {
		return connectionManager.getPeers();
	}

	/**
	 * Restores the last persisted standalone value from this propagator's store.
	 * The restored value also becomes this propagator's working view, ready for
	 * directional reconciliation with the next authoritative projection.
	 *
	 * @return restored value, or null if no persisted value exists
	 *         or the store is not persistent
	 */
	public ACell restore() {
		if (!store.isPersistent()) return null;
		try {
			ACell restored = store.getRootData();
			if (restored != null) {
				synchronized (writeLock) {
					workingCursor = Cursors.createLattice(lattice, restored, mergeContext);
				}
			}
			return restored;
		} catch (IOException e) {
			log.warn("Error restoring lattice value from store", e);
			return null;
		}
	}

	/**
	 * Returns whether this group's workers are active.
	 *
	 * @return {@code true} while the group is running
	 */
	public boolean isRunning() { return running; }

	/**
	 * Returns the number of delta broadcast sequences initiated since start.
	 *
	 * @return initiated delta broadcast count
	 */
	public long getBroadcastCount() { return broadcastCount.get(); }

	/**
	 * Returns the latest store-backed view served by this group.
	 *
	 * @return last announced value, or {@code null} before the first announcement
	 */
	public ACell getLastAnnouncedValue() { return announcedCursor.get(); }

	/**
	 * Future completing with the next value announced by this propagator.
	 *
	 * <p>Gives callers something to wait on for propagation: capture the future
	 * <em>before</em> triggering the change, then {@code get(timeout)} — no
	 * sleep-polling on {@link #getLastAnnouncedValue()} required. Each announce
	 * completes the current future and installs a fresh one, so the returned
	 * future always reflects an announce that happens after the call.
	 *
	 * @return future for the next announced store-backed value
	 */
	public CompletableFuture<ACell> nextAnnounce() { return nextAnnounceFuture; }

	/**
	 * Future for the next announce. Swapped under {@link #writeLock} in
	 * {@link #processSnapshot}, completed outside it (dependent actions must
	 * not run while holding the pipeline lock).
	 */
	private volatile CompletableFuture<ACell> nextAnnounceFuture = new CompletableFuture<>();
	/**
	 * Cursor holding the last value announced by this propagator. See
	 * {@link #announcedCursor} for ownership and security semantics.
	 *
	 * @return announced-value cursor
	 */
	public Root<ACell> getAnnouncedCursor() { return announcedCursor; }
	/**
	 * Returns the wall-clock time of this group's last delta broadcast.
	 *
	 * @return epoch time in milliseconds, or zero before the first broadcast
	 */
	public long getLastBroadcastTime() { return lastBroadcastTime; }

	/**
	 * Returns the wall-clock time of this group's last periodic root sync.
	 *
	 * @return epoch time in milliseconds, or zero before the first root sync
	 */
	public long getLastRootSyncTime() { return lastRootSyncTime; }

	/**
	 * Returns the number of periodic root syncs initiated since start.
	 *
	 * @return initiated root-sync count
	 */
	public long getRootSyncCount() { return rootSyncCount; }

	// ========== Lifecycle ==========

	/**
	 * Starts this group's connection manager, protocol endpoint and publication
	 * worker. Attached groups are normally started by their {@link NodeServer}.
	 */
	public synchronized void start() {
		if (running) {
			log.warn("LatticePropagator already running");
			return;
		}

		transition(State.STARTING);
		LatticeProtocolEndpoint protocol=endpoint;
		try {
			if (protocol!=null) {
				connectionManager.setPeerMessageHandler((peer,message) ->
					protocol.receiveFromManagedOutbound(peer,message));
			}
			connectionManager.start();
			if (protocol!=null) protocol.start();

			running = true;
			lastBroadcastTime = 0L;
			lastRootSyncTime = 0L;
			broadcastCount.set(0L);

			propagationThread = new Thread(this::propagationLoop, "Lattice propagator thread");
			propagationThread.setDaemon(true);
			propagationThread.start();
		} catch (RuntimeException | Error failure) {
			running=false;
			recordFailure("launch",failure);
			if (protocol!=null) {
				try {
					protocol.close();
				} catch (IOException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			try {
				connectionManager.close();
			} catch (RuntimeException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			transition(State.STOPPED);
			throw failure;
		}

		transition(State.RUNNING);
		log.debug("LatticePropagator started");
	}

	/**
	 * Triggers a final value and shuts down gracefully.
	 *
	 * <p>The propagator processes any remaining queued values (including the
	 * final value if non-null) before stopping. This is the only blocking
	 * handoff in the group — used during shutdown to finish serving-store
	 * materialisation before routes close.</p>
	 *
	 * @param finalValue final value to process before stopping, or {@code null}
	 */
	public void triggerAndClose(ACell finalValue) {
		transition(State.STOPPING);
		if (!running && propagationThread == null) {
			closeRoutes();
			transition(State.STOPPED);
			return;
		}

		running = false;

		if (finalValue != null) {
			triggerQueue.offer(finalValue); // wakes thread via notify
		} else if (propagationThread != null) {
			propagationThread.interrupt(); // wake thread from poll wait
		}

		if (propagationThread != null) {
			try {
				propagationThread.join(10_000);
				if (propagationThread.isAlive()) {
					log.warn("LatticePropagator thread did not drain within timeout, interrupting");
					propagationThread.interrupt();
					propagationThread.join(2000);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			propagationThread = null;
		}

		// Drain any values the loop did not consume. The loop's exit check
		// (running || !queue.isEmpty()) can observe running==false before the
		// final value above lands in the queue, and exit without processing it —
		// which would silently lose the last writes on a clean shutdown.
		// processSnapshot is callable from any thread (serialised by writeLock),
		// so this drain is safe even if the thread had to be abandoned after the
		// join timeout.
		ACell remaining;
		while ((remaining = triggerQueue.poll()) != null) {
			processSnapshotSafe(remaining);
		}

		closeRoutes();

		transition(State.STOPPED);
		log.debug("LatticePropagator closed (sent {} delta broadcasts, {} root syncs)",
			broadcastCount, rootSyncCount);
	}

	/**
	 * Stops new protocol admission and drains work already accepted by this group.
	 * The publication worker remains live so merges completed during the drain can
	 * still receive authoritative node notifications.
	 *
	 * @throws IOException if accepted endpoint work cannot be drained
	 */
	public void stopIngress() throws IOException {
		if (endpoint!=null) endpoint.close();
	}

	/**
	 * Stops the propagator gracefully. Equivalent to {@code triggerAndClose(null)}.
	 */
	@Override
	public void close() {
		try {
			stopIngress();
		} catch (IOException e) {
			recordFailure("ingress shutdown",e);
			log.warn("Unable to drain propagation protocol endpoint cleanly",e);
		} catch (RuntimeException | StackOverflowError e) {
			recordFailure("ingress shutdown",e);
			log.warn("Unable to stop propagation protocol endpoint cleanly",e);
		}
		triggerAndClose(null);
	}

	/** Closes manager-owned routes even when the group was never started. */
	private void closeRoutes() {
		try {
			connectionManager.close();
		} catch (RuntimeException | StackOverflowError e) {
			recordFailure("route shutdown",e);
			log.warn("Unable to close propagation routes cleanly",e);
		}
	}

	// ========== Trigger API ==========

	/**
	 * Triggers processing of the given lattice value.
	 *
	 * <p>Non-blocking: the value is queued and processed by the background thread.
	 * Uses LatestUpdateQueue which automatically coalesces rapid triggers —
	 * safe because lattice values are monotonic (V2 >= V1 implies V1 is subsumed).</p>
	 *
	 * @param value lattice value to process; {@code null} is ignored
	 */
	public void triggerBroadcast(ACell value) {
		if (!running) return;
		if (value == null) return;
		triggerQueue.offer(value);
	}

	// ========== Propagation Loop ==========

	/**
	 * Main propagation loop. Processes authoritative node notifications from the
	 * trigger queue through this group's projection, materialisation and broadcast.
	 *
	 * <p>When {@code running} is false, switches to drain mode: processes
	 * remaining queued values without waiting, then exits.
	 */
	private void propagationLoop() {
		while (running || !triggerQueue.isEmpty()) {
			try {
				ACell value;
				if (running) {
					value = triggerQueue.poll(ROOT_SYNC_INTERVAL, TimeUnit.MILLISECONDS);
				} else {
					// Drain mode: non-blocking poll, exit when empty
					value = triggerQueue.poll();
					if (value == null) break;
				}

				if (value != null) {
					processSnapshotSafe(value);
				}

				// Periodic root sync only while running
				if (running) {
					maybePerformRootSync(Utils.getCurrentTimestamp());
				}

			} catch (InterruptedException e) {
				// Drain remaining items before exiting
				ACell remaining;
				while ((remaining = triggerQueue.poll()) != null) {
					processSnapshotSafe(remaining);
				}
				break;
			} catch (Exception e) {
				log.warn("Unexpected error in propagation loop", e);
				if (!running) break;
			}
		}
		log.debug("LatticePropagator loop ended");
	}

	/**
	 * Background-thread wrapper around {@link #processSnapshot}. IOException
	 * is logged rather than propagated — the background path is best-effort.
	 */
	private void processSnapshotSafe(ACell value) {
		try {
			processSnapshot(value);
		} catch (VirtualMachineError e) {
			if (!(e instanceof StackOverflowError)) throw e;
			recordFailure("publication",e);
			log.warn("Contained stack overflow in propagation policy group",e);
		} catch (Throwable e) {
			// A broken filter, store cache or connection implementation belongs to
			// this policy group. It must never terminate NodeServer publication or
			// another propagator's worker.
			recordFailure("publication",e);
			log.warn("Isolated propagation policy failure",e);
		}
	}

	/**
	 * Processes a single lattice value through this group's output pipeline:
	 * <ol>
	 *   <li>Reconcile and project this policy group's view</li>
	 *   <li>Announce to its serving store and collect novelty</li>
	 *   <li>Optionally retain a standalone compatibility root</li>
	 *   <li>Broadcast a best-effort delta to its routes</li>
	 * </ol>
	 *
	 * <p>Announcement always runs for store-backed references. Attached policy
	 * groups have root retention disabled because {@link NodeServer} alone owns
	 * the authoritative durable root. Network encoding and queuing are best effort.</p>
	 *
	 * <p>Callable directly for standalone use and deterministic tests. Attached
	 * nodes normally schedule it on this group's worker using
	 * {@link #triggerBroadcast(ACell)}. Calls are serialised by {@link #writeLock}.</p>
	 *
	 * @param value snapshot to process; must not be {@code null}
	 * @return announced store-backed value
	 * @throws IOException if announcement or standalone root publication fails
	 */
	public ACell processSnapshot(ACell value) throws IOException {
		CompletableFuture<ACell> announceFuture;
		synchronized (writeLock) {
			// Reconcile the group's established filtered view with the latest
			// authoritative node snapshot before applying outbound projection.
			if ((workingCursor != null) && (lattice != null)) {
				value = lattice.merge(mergeContext, workingCursor.get(), value);
			}

			// Filtering is outbound-only: pending inbound state participates in the
			// reconciliation above, then projection precedes every outbound operation.
			value = filter.filter(value);
			if (value == null) throw new IllegalArgumentException("Lattice filter returned null");

			// 1. Announce to store (writes cells, collects novelty for delta)
			boolean hasPeers=!connectionManager.getPeers().isEmpty();
			NoveltyCollector noveltyCollector=hasPeers
				?new NoveltyCollector(maxDeltaBroadcastSize):null;
			value = Cells.announce(value, noveltyCollector, store);
			if (workingCursor == null) {
				workingCursor = Cursors.createLattice(lattice, value, mergeContext);
			} else {
				workingCursor.set(value);
			}

			// 2. Set root data for restore (if persist enabled)
			if (persistenceEnabled) {
				store.setRootData(value);
				dirty = true;
			}

			// The store-backed root becomes externally visible before best-effort network
			// encoding. A failed delta must therefore still be eligible for periodic
			// root sync and must still complete the caller's publication future.
			announcedCursor.set(value);
			announceFuture = nextAnnounceFuture;
			nextAnnounceFuture = new CompletableFuture<>();

			// 3. Broadcast to peers. Background triggers are already coalesced by
			// LatestUpdateQueue; an explicitly processed snapshot must not be dropped.
			if (hasPeers) {
				try {
					broadcastDelta(value,noveltyCollector.getCells());
				} catch (RuntimeException e) {
					log.warn("Unable to encode or queue lattice delta; periodic root sync will retry",e);
				}
			}
		}
		announceFuture.complete(value);
		return value;
	}

	/** Sends one bounded delta, or DATA-ahead chunks followed by a root announcement. */
	private void broadcastDelta(ACell value, ArrayList<ACell> novelty) {
		// Embedded cells already travel inside their nearest non-embedded parent and
		// are invalid as trailing multi-cell children.
		novelty.removeIf(ACell::isEmbedded);
		if (!value.isEmbedded()
				&& (novelty.isEmpty() || !novelty.get(novelty.size() - 1).equals(value))) {
			novelty.add(value);
		}

		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE,emptyPath,value);
		Message rootMessage = Message.create(MessageType.LATTICE_VALUE, payload, payload.getEncoding());
		if (rootMessage.getMessageData().count()>maxDeltaMessageSize) {
			log.warn("Lattice root announcement exceeds delta message limit of {} bytes; root sync will recover",
				maxDeltaMessageSize);
			return;
		}
		ArrayList<ACell> delta = new ArrayList<>(novelty.size()+1);
		delta.addAll(novelty);
		delta.add(payload);

		List<Message> messages;
		if (Format.getDeltaEncodingLength(delta)<=maxDeltaMessageSize) {
			Blob deltaData=Format.encodeDelta(delta,maxDeltaMessageSize);
			messages=List.of(Message.create(MessageType.LATTICE_VALUE,payload,deltaData));
		} else {
			try {
				long dataBudget=maxDeltaBroadcastSize-rootMessage.getMessageData().count();
				messages=(dataBudget>0)
					?new ArrayList<>(Message.createDataMessages(
						novelty,maxDeltaMessageSize,dataBudget))
					:new ArrayList<>();
				messages.add(rootMessage);
			} catch (IllegalArgumentException e) {
				// A single non-embedded cell may exceed an application-selected chunk
				// limit. Announce the root only and let the receiver pull that branch.
				messages=List.of(rootMessage);
			}
		}

		var result=connectionManager.broadcastSequence(messages,rootMessage);
		if (result.dropped()>0) {
			log.debug("Dropped lattice delta for {} peer(s); root sync will recover",result.dropped());
		}
		lastBroadcastTime = Utils.getCurrentTimestamp();
		broadcastCount.incrementAndGet();
	}

	// ========== Root Sync ==========

	/**
	 * Performs periodic root-only sync broadcast for divergence detection.
	 */
	private void maybePerformRootSync(long currentTime) {
		if (currentTime < lastRootSyncTime + ROOT_SYNC_INTERVAL) return;
		if (!connectionManager.hasPropagationRoutes()) return;

		try {
			Message message = createRootSyncMessage();
			if (message == null) return;
			connectionManager.broadcast(message);
			lastRootSyncTime = currentTime;
			rootSyncCount++;
			log.debug("Sent root sync ({} bytes)", message.getMessageData().count());
		} catch (Exception e) {
			log.warn("Error during root sync broadcast", e);
		}
	}

	/**
	 * Creates a root sync for the last value successfully announced to this
	 * propagator's serving store. A triggered value is deliberately not externally
	 * visible until {@link Cells#announce(ACell, Consumer, AStore)} has completed.
	 */
	Message createRootSyncMessage() {
		ACell value = announcedCursor.get();
		if (value == null) return null;
		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE,emptyPath,value);
		// The value may be encoded as an indirect ref; DATA_REQUEST resolution is
		// safe because announcedCursor advances only after the store is populated.
		return Message.create(MessageType.LATTICE_VALUE, payload, payload.getEncoding());
	}

	// ========== Standalone compatibility persistence ==========

	/**
	 * Completes a durability barrier when this propagator has unpublished
	 * persistent changes.
	 *
	 * @return true if a barrier was completed
	 * @throws IOException If the barrier fails
	 */
	boolean checkpoint() throws IOException {
		if (!store.isPersistent()) return false;
		synchronized (writeLock) {
			if (!dirty) return false;
			store.flush();
			dirty = false;
			return true;
		}
	}

	// ========== Pull (Fetch from Peers) ==========

	/**
	 * Pulls the latest lattice value from a specific peer into this propagator's store.
	 *
	 * <p>Sends a LATTICE_QUERY to the peer, acquires the full value tree into
	 * this propagator's store via {@link Convex#acquire}, then returns that
	 * store-backed value to the caller.</p>
	 *
	 * <p>This method deliberately performs no merge, root publication or broadcast.
	 * Only {@link NodeServer} owns the authoritative root, so it must merge the
	 * acquired value and sync its cursor before anything is re-propagated. Persisting
	 * the raw peer value here could demote a root already dominating it.</p>
	 *
	 * @param peer manager-owned peer client to query
	 * @return future completing with the acquired value
	 */
	public CompletableFuture<ACell> pull(Convex peer) {
		return pullPath(peer);
	}

	/**
	 * Pulls one path of a peer's latest lattice value into this propagator's
	 * store. Path selection scopes transfer and storage work; it is not an
	 * access-control boundary.
	 *
	 * <p>The caller must not mutate {@code path} while the returned operation is
	 * outstanding.</p>
	 *
	 * @param peer manager-owned peer client to query
	 * @param path path within the peer's announced lattice value; empty selects the root
	 * @return future completing with the acquired value, or {@code null} when absent
	 */
	public CompletableFuture<ACell> pullPath(Convex peer, ACell... path) {
		if (peer == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Peer cannot be null"));
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				if (!peer.isConnected()) {
					throw new RuntimeException("Peer is not connected");
				}

				AVector<?> queryPayload = Vectors.create(
						MessageTag.LATTICE_QUERY, null, Vectors.create(path));
				Message queryMessage = Message.create(MessageType.LATTICE_QUERY, queryPayload);

				// A large value comes back as a partial reply (root plus direct branches,
				// possibly preceded by DATA chunks). It must decode against this store so
				// missing branches resolve lazily and the acquire fallback can complete it.
				if (peer.getStore() == null) peer.setStore(store);

				CompletableFuture<Result> resultFuture = peer.request(queryMessage);
				Result result = resultFuture.get(10, TimeUnit.SECONDS);

				if (result.isError()) {
					throw new RuntimeException("Pull query failed: " + result);
				}

				ACell receivedValue = result.getValue();
				if (receivedValue == null) return null;

				// 2. Store the received value locally. A complete reply is announced
				// directly. A partial reply (root only, or root after DATA chunks) is
				// completed by acquiring from the peer: any cells the reply or its
				// chunks staged in the store are found there and not fetched again.
				// Completeness is checked explicitly, since a store may hold a partial
				// value without throwing.
				ACell acquired;
				HashSet<Hash> missing = new HashSet<>();
				Ref.get(receivedValue).findMissing(missing, 1);
				if (missing.isEmpty()) {
					acquired = Cells.announce(receivedValue, r -> {}, store);
				} else {
					Hash rootHash = Hash.get(receivedValue);
					acquired = peer.acquire(rootHash, store).get(30, TimeUnit.SECONDS);
				}

				log.debug("Acquired pulled lattice path from peer: {}", peer.getHostAddress());
				return acquired;

			} catch (Exception e) {
				log.warn("Lattice pull failed from peer: {}", peer.getHostAddress(), e);
				throw new RuntimeException("Lattice pull failed from peer", e);
			}
		},ThreadUtils.getVirtualExecutor());
	}

	/**
	 * Pulls the latest lattice value from all admitted manager-owned peer clients.
	 *
	 * <p>Sends LATTICE_QUERY to each connected peer in parallel, acquires their
	 * values into this propagator's store. The returned values remain unmerged;
	 * {@link NodeServer} integrates them through its authoritative root cursor.</p>
	 *
	 * @return future containing all acquired values when every pull is complete
	 */
	public CompletableFuture<List<ACell>> pullAll() {
		Set<Convex> peerSet = connectionManager.getPeers();
		if (peerSet.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}

		List<CompletableFuture<ACell>> futures = new ArrayList<>();
		for (Convex peer : peerSet) {
			if (peer != null && peer.isConnected()) {
				futures.add(pull(peer));
			}
		}

		if (futures.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
			.thenApply(v -> {
				List<ACell> acquired = new ArrayList<>(futures.size());
				for (CompletableFuture<ACell> future : futures) {
					acquired.add(future.join());
				}
				return acquired;
			});
	}
}
