package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Acquiror;
import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.StoreException;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.BoundedMessageQueue;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.RootComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.RootLatticeCursor;
import convex.net.AServer;
import convex.net.impl.netty.NettyServer;
import convex.peer.Config;

/**
 * Replicates one application-supplied lattice over the Convex binary protocol.
 *
 * <p>This class is deliberately ignorant of the lattice's application schema.
 * It never assumes that a path such as {@code :p2p}, {@code :social} or
 * {@code :nodes} exists, and it never publishes or interprets application
 * records. Its scope is the generic CAD036 transport: authoritative cursor,
 * listener lifecycle, bounded ordered decode/merge pipeline, missing-cell
 * acquisition and connection-to-publication-view assignment.</p>
 *
 * <p>Transport identity is also explicit. {@link #setTransportKeyPair(AKeyPair)}
 * configures connection challenge/response; {@link LatticeContext} independently
 * defines application merge and owner-signature policy. A wrapper may choose the
 * same key for both roles, but this class never infers that relationship.</p>
 *
 * <p>Application modules compose behaviour through the supplied {@link ALattice},
 * {@link LatticeContext}, ingress/publication filters and optional notification
 * hooks. For example, node discovery belongs to {@code convex-p2p.P2PNode}, not
 * this base transport.</p>
 *
 * <p><b>Inbound capability boundary.</b> Network lattice traffic is denied until
 * operator policy assigns its physical connection to exactly one propagator. That
 * propagator supplies both the query view and the acquisition store. Partial values
 * are acquired completely in that store before they enter the ordered merge path;
 * NodeServer never searches another propagator or falls back to the primary store.</p>
 *
 * <p><b>Threading.</b> Application cursor updates may originate on caller
 * threads. Primary publication is synchronous with cursor {@code sync()};
 * secondary propagation is asynchronous. All accepted inbound messages pass
 * through one bounded ordered dispatcher. Missing-cell acquisition runs on
 * owned workers and re-enters that dispatcher only after completion.</p>
 *
 * <p>See the {@code convex.node} package documentation and
 * {@code convex-peer/docs/LATTICE_NETWORKING.md} for the component, route and
 * lifecycle state maps.</p>
 *
 * @param <V> The type of lattice values managed by this node server
 */
public class NodeServer<V extends ACell> implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(NodeServer.class.getName());

	/**
	 * The lattice instance that defines merge semantics for values
	 */
	private final ALattice<V> lattice;

	/**
	 * Configuration for this node server
	 */
	private final NodeConfig config;

	/**
	 * Cursor for the current local lattice value
	 */
	private final RootLatticeCursor<V> cursor;

	/** Generic application root owned by this server. */
	private final RootComponent<V> rootComponent;
	/** True once this server has installed and frozen its root publication policy. */
	private boolean rootPublicationConfigured;

	/**
	 * Network server instance for handling connections
	 */
	private AServer networkServer;

	/**
	 * Store for this server. Used for local/connection-less decoding and as the
	 * default primary propagator store when no topology is supplied.
	 *
	 * <p>Network lattice acquisition uses the store of the connection's explicitly
	 * assigned propagator, not this field merely because NodeServer owns it.
	 * May be the same store as the propagator's store (typical single-propagator case)
	 * or a different store if the operator chooses a different topology.
	 */
	private final AStore store;

	/**
	 * Propagators for persistence and broadcast. Index 0 is the primary propagator
	 * (if present). Its synchronous snapshot result is installed by the root cursor.
	 * Additional propagators handle public/backup broadcast.
	 */
	private final List<LatticePropagator> propagators = new ArrayList<>();

	/**
	 * Context used for all merge operations. Carries signing key and owner
	 * verifier through the lattice hierarchy. Default is EMPTY (no signing,
	 * no owner verification).
	 */
	private LatticeContext mergeContext = LatticeContext.EMPTY;

	/**
	 * Optional node key used only for transport challenge/response. It is separate
	 * from the signing and owner-verification policy carried by {@link #mergeContext}.
	 */
	private AKeyPair transportKeyPair;

	/**
	 * Message receiver action for handling incoming lattice sync messages
	 */
	private final java.util.function.Consumer<Message> receiveAction;

	/**
	 * Port this server is listening on
	 */
	private Integer port;

	/** Complete lifecycle for the services owned by this node. */
	enum LifecycleState {
		NEW, STARTING, RUNNING, STOPPING, STOPPED
	}

	/**
	 * Single lifecycle authority. In particular, STOPPING remains visible after a
	 * dispatcher drain timeout, preventing relaunch or configuration mutation while
	 * the original consumer may still be using the cursor or store.
	 */
	private volatile LifecycleState lifecycleState = LifecycleState.NEW;

	/** Bounded handoff from Netty event loops to the lattice processing thread. */
	private final BoundedMessageQueue inboundQueue;

	/** Pre-allocated backpressure retry returned when {@link #inboundQueue} is full. */
	private final Predicate<Message> inboundRetry = this::offerInboundBlocking;

	/** Pre-allocated terminal rejection used while the server is stopping. */
	private final Predicate<Message> inboundRejected = message -> false;

	/** Whether new network messages may be admitted to the inbound queue. */
	private volatile boolean acceptingInbound = false;

	/** Whether the inbound dispatcher should wait for new work. */
	private volatile boolean inboundRunning = false;

	/** Single ordered consumer for decode, merge and persistence work. */
	private Thread inboundThread;

	/**
	 * Per-connection inbound counters, keyed by the originating connection (#566). Used for
	 * observability and to drive the circuit-breaker that closes a connection flooding us with
	 * bad messages. Connection-less (in-JVM) messages are not tracked.
	 */
	private final ConcurrentHashMap<AConnection, ConnectionStats> connectionStats = new ConcurrentHashMap<>();

	/**
	 * Operator policy for assigning an inbound physical connection to one propagator.
	 * Null means that network lattice queries and values are denied. A successful
	 * selection is cached for the lifetime of the connection and can never change.
	 */
	private Function<AConnection, LatticePropagator> inboundPropagatorSelector;

	/** Complete-value admission/projection applied before inbound persistence. */
	private LatticeIngressFilter ingressFilter=(path,value) -> value;

	/** Projection applied to the primary persisted and replicated view. */
	private LatticeFilter<V> publicationFilter=value -> value;

	/** Optional application protocol handler for complete UNKNOWN messages. */
	private Predicate<Message> applicationMessageHandler;

	/** Optional application observer for accepted inbound lattice values. */
	private InboundLatticeListener inboundLatticeListener;

	/** Immutable connection-to-propagator capabilities established by operator policy. */
	private final ConcurrentHashMap<AConnection, LatticePropagator> inboundPropagators =
		new ConcurrentHashMap<>();

	/**
	 * Manager-owned outbound connections carrying reverse messages from their
	 * authenticated remote endpoint. Kept separate from operator-assigned inbound
	 * connections so physical direction and capability origin remain explicit.
	 */
	private final ConcurrentHashMap<AConnection, LatticePropagator> outboundPropagators =
		new ConcurrentHashMap<>();

	/** Performs the explicit untrusted-inbound to authenticated-route upgrade. */
	private final LatticeInboundVerifier inboundVerifier;

	/** Pending reverse data requests, isolated by physical connection and request ID. */
	private final ConcurrentHashMap<AConnection, ConcurrentHashMap<ACell, CompletableFuture<Result>>>
		pendingDataRequests = new ConcurrentHashMap<>();

	/** Active Acquirors retained until their owned workers have terminated. */
	private final ConcurrentHashMap<AConnection, Set<Acquiror>> activeAcquirors =
		new ConcurrentHashMap<>();
	private final Object acquisitionLifecycleLock = new Object();
	private boolean acceptingAcquisitions;

	/** Bounds acquisition sessions independently of the bounded merge dispatcher queue. */
	private final Semaphore acquisitionPermits;

	/** Prepared messages re-entering the ordered dispatcher after complete acquisition. */
	private final ConcurrentHashMap<Message, LatticePropagator> acquiredMessages =
		new ConcurrentHashMap<>();

	/** Acquisition failures re-enter the dispatcher so connection metrics stay single-writer. */
	private final ConcurrentHashMap<Message, Throwable> acquisitionFailures =
		new ConcurrentHashMap<>();

	/**
	 * Tracked-connection count above which {@link #statsFor} sweeps closed entries (#566).
	 * Sits just above the inbound channel cap ({@link Config#MAX_CLIENT_CONNECTIONS}): there
	 * can be at most that many <em>live</em> connections, so exceeding this means stale
	 * (closed) entries have accumulated and should be reclaimed. The small headroom avoids
	 * sweeping on transient overlap between a closing connection and its replacement.
	 */
	private static final int MAX_TRACKED_CONNECTIONS = Config.MAX_CLIENT_CONNECTIONS + 64;

	/** Interval (ms) between periodic sweeps of closed connections from the stats map (#566). */
	private static final long CONNECTION_SWEEP_INTERVAL = 30_000L;

	/** Virtual thread that periodically prunes closed connections from {@link #connectionStats}. */
	private Thread maintenanceThread;
	private final Object maintenanceSignal = new Object();

	/** Stable identity required for prompt shutdown-hook deregistration. */
	private final Runnable shutdownHook = this::shutdownPersist;
	private boolean shutdownHookRegistered;

	// ========== Construction and Lifecycle ==========

	/**
	 * Creates a new NodeServer with the specified lattice, store and configuration.
	 *
	 * @param lattice The lattice instance defining merge semantics
	 * @param store Local decode store and default primary propagator store
	 * @param config Configuration (or null for defaults)
	 */
	public NodeServer(ALattice<V> lattice, AStore store, NodeConfig config) {
		this.lattice = lattice;
		this.store = store;
		this.config = (config != null) ? config : NodeConfig.create();
		this.inboundQueue = new BoundedMessageQueue(this.config.getInboundQueueSize(),
			this.config.getMaxInboundQueueBytes());
		this.acquisitionPermits = new Semaphore(this.config.getInboundQueueSize());
		this.inboundVerifier = new LatticeInboundVerifier(this);
		this.port = this.config.getPort();
		this.mergeContext = LatticeContext.EMPTY.withMaxFutureTimestampSkew(
			this.config.getMaxFutureTimestampSkew());
		this.cursor = Cursors.createLattice(lattice, lattice.zero(), mergeContext);
		this.rootComponent = new RootComponent<>(cursor,store);

		// Hook sync callback: synchronous publication on the primary propagator,
		// async fan-out to secondaries.
		//
		// The primary's pipeline (announce + setRootData + broadcast) runs on the
		// caller's thread. A successful cursor.sync() confirms that the root and its
		// reachable cells have been published to the primary store; it is not a
		// physical durability barrier. IOException from announce or setRootData is
		// wrapped and propagated to the caller.
		//
		// Secondary propagators use the existing async path; their broadcast
		// latency is independent of caller publication.
		//
		// The returned (announced/store-backed) value is CASed back into the
		// cursor by RootLatticeCursor.sync(), with lattice-merge fallback if a
		// concurrent app write changed the cursor during the announce.
		// Initialize receive action for handling incoming messages
		this.receiveAction = this::handleIncomingMessage;

		// Network server will be created in launch() method
		this.networkServer = null;
	}

	/**
	 * Creates a new NodeServer instance with default configuration.
	 *
	 * @param lattice The lattice instance defining merge semantics
	 * @param store The store for persisting lattice values
	 */
	public NodeServer(ALattice<V> lattice, AStore store) {
		this(lattice, store, (NodeConfig) null);
	}

	/**
	 * Launches the node server, binding to the configured port and starting
	 * network listeners and automatic propagation.
	 *
	 * @throws IOException If an IO error occurs during launch
	 * @throws InterruptedException If the operation is interrupted
	 */
	public synchronized void launch() throws IOException, InterruptedException {
		validateLaunchRequest();
		validateLaunchConfiguration();

		lifecycleState = LifecycleState.STARTING;
		try {
			log.debug("Launching NodeServer on port {}", port);
			createDefaultPrimaryPropagator();
			configurePublicationPipeline();
			configurePropagatorsForLaunch();
			restorePersistedViews();
			seedPublicationStores();
			startInboundDispatcher();
			launchNetworkListener();
			startMaintenanceService();
			registerShutdownPersistenceHook();
			startPropagationServices();

			lifecycleState = LifecycleState.RUNNING;
			log.debug("NodeServer started successfully on port {}", port);
		} catch (IOException | InterruptedException | RuntimeException | Error e) {
			abortFailedLaunch(e);
			throw e;
		}
	}

	/** Rejects concurrent launch and relaunch while an earlier close is incomplete. */
	private void validateLaunchRequest() {
		if (lifecycleState == LifecycleState.RUNNING || lifecycleState == LifecycleState.STARTING) {
			throw new IllegalStateException("NodeServer is already running");
		}
		if (lifecycleState == LifecycleState.STOPPING) {
			throw new IllegalStateException("NodeServer shutdown is incomplete; call close() again before launch");
		}

	}

	/** Validates immutable settings before any listener or worker is started. */
	private void validateLaunchConfiguration() {
		// Discover an unusable close-time policy before opening resources.
		config.getInboundShutdownTimeout();

	}

	/** Creates the conventional single primary publication view when none was supplied. */
	private void createDefaultPrimaryPropagator() {
		if (!propagators.isEmpty() || store == null) return;
		LatticeConnectionManager connectionManager = new LatticeConnectionManager(store);
		if (transportKeyPair != null) connectionManager.setKeyPair(transportKeyPair);
		propagators.add(new LatticePropagator(
			store,connectionManager,lattice,publicationFilter));
	}

	/** Installs the primary publication callback once and prevents later replacement. */
	private void configurePublicationPipeline() {
		if (rootPublicationConfigured) return;
		rootComponent.setPublicationPolicy(this::publishApplicationRoot);
		rootComponent.freezePublicationPolicy();
		rootPublicationConfigured=true;
	}

	/** Applies immutable merge, persistence, size and reverse-delivery policy. */
	private void configurePropagatorsForLaunch() {
		for (int i = 0; i < propagators.size(); i++) {
			LatticePropagator propagator = propagators.get(i);
			propagator.configure(lattice, mergeContext, i == 0);
			propagator.setPersistenceEnabled(config.isPersist());
			propagator.setMaxDeltaMessageSize(config.getMaxDeltaMessageSize());
			propagator.setMaxDeltaBroadcastSize(config.getMaxDeltaBroadcastSize());

			LatticeConnectionManager manager = propagator.getConnectionManager();
			if (transportKeyPair!=null) manager.setKeyPair(transportKeyPair);
			manager.setMaxDesiredPeers(config.getMaxDesiredPeers());
			manager.setInboundMessageLimits(
				config.getMaxMessageSize(), config.getMaxTrustedMessageSize());
			manager.setPeerMessageHandler(
				(peer, message) -> receiveFromManagedOutbound(propagator, peer, message));
		}
	}

	/** Restores each private view and installs only the primary as authoritative root. */
	@SuppressWarnings("unchecked")
	private void restorePersistedViews() {
		if (!config.isRestore()) return;
		for (int i = 0; i < propagators.size(); i++) {
			ACell restored = propagators.get(i).restore();
			if (restored != null && i == 0) {
				cursor.set((V) restored);
				log.info("Restored lattice value from primary store");
			}
		}
	}

	/** Seeds store-backed views and completes the startup durability barrier. */
	@SuppressWarnings("unchecked")
	private void seedPublicationStores() throws IOException {
		if (propagators.isEmpty()) return;
		ACell announced = propagators.get(0).processSnapshot(cursor.get());
		cursor.set((V) announced);
		for (int i = 1; i < propagators.size(); i++) {
			propagators.get(i).processSnapshot(announced);
		}
		if (config.isPersist()) {
			for (LatticePropagator propagator : propagators) propagator.checkpoint();
		}
	}

	/** Opens the optional listener; outbound-only nodes still use the dispatcher. */
	private void launchNetworkListener() throws IOException, InterruptedException {
		if (port != null && port < 0) return;
		if (networkServer == null) {
			NettyServer nettyServer = new NettyServer(port);
			nettyServer.setReceiveAction(receiveAction);
			nettyServer.setMessageDelivery(this::deliverIncomingMessage);
			nettyServer.setMaxClientConnections(config.getMaxConnections());
			nettyServer.setMaxMessageLength(config.getMaxMessageSize());
			nettyServer.setDisconnectAction(this::removeConnection);
			networkServer = nettyServer;
		}
		if (port != null) networkServer.setPort(port);
		networkServer.launch();
		port = networkServer.getPort();
	}

	/** Starts periodic stale-connection metric pruning. */
	private void startMaintenanceService() {
		maintenanceThread = Thread.ofVirtual()
			.name("NodeServer connection-stats maintenance")
			.start(this::maintenanceLoop);
	}

	/** Registers orderly persistence before Etch shutdown hooks close their files. */
	private void registerShutdownPersistenceHook() {
		Shutdown.addHook(Shutdown.SERVER, shutdownHook);
		shutdownHookRegistered = true;
	}

	/** Starts each connection manager before its owning propagation worker. */
	private void startPropagationServices() {
		for (LatticePropagator propagator : propagators) {
			propagator.getConnectionManager().start();
			propagator.start();
		}
	}

	/**
	 * Stops every service that may have started after the listener opened, attaching
	 * cleanup failures to the original launch failure. This deliberately differs from
	 * {@link #close()}: it does not submit a final persistence snapshot. Retrying the
	 * persistence while unwinding the failure would obscure whether publication succeeded
	 * and could turn lifecycle cleanup into a second failing persistence operation.
	 */
	private void abortFailedLaunch(Throwable failure) {
		acceptingInbound = false;
		lifecycleState = LifecycleState.STOPPING;

		try {
			if (networkServer != null) networkServer.close();
		} catch (Throwable cleanupError) {
			addCleanupFailure(failure, cleanupError);
		}
		inboundVerifier.close();

		boolean acquisitionsStopped = false;
		try {
			stopAcquisitions();
			acquisitionsStopped = true;
		} catch (Throwable cleanupError) {
			addCleanupFailure(failure, cleanupError);
		}

		boolean dispatcherStopped = false;
		try {
			stopInboundDispatcher();
			dispatcherStopped = true;
		} catch (Throwable cleanupError) {
			addCleanupFailure(failure, cleanupError);
		}

		try {
			stopMaintenance();
		} catch (Throwable cleanupError) {
			addCleanupFailure(failure, cleanupError);
		}

		// A timed-out dispatcher may still be inside cursor.sync() or store decoding.
		// Keep the propagators and store-facing services available until a later
		// close() confirms that the sole ordered consumer has actually terminated.
		if (!acquisitionsStopped || !dispatcherStopped) return;

		connectionStats.clear();
		inboundPropagators.clear();
		outboundPropagators.clear();

		for (LatticePropagator p : propagators) {
			try {
				p.close(); // stop and drain existing work, but do not enqueue a final snapshot
			} catch (Throwable cleanupError) {
				addCleanupFailure(failure, cleanupError);
			}
			try {
				p.getConnectionManager().close();
			} catch (Throwable cleanupError) {
				addCleanupFailure(failure, cleanupError);
			}
		}
		lifecycleState = LifecycleState.STOPPED;
		removeShutdownHook();
	}

	/** Retains unwind failures without replacing the launch failure seen by the caller. */
	private static void addCleanupFailure(Throwable failure, Throwable cleanupError) {
		if (cleanupError != failure) failure.addSuppressed(cleanupError);
	}

	// ========== Ordered Inbound Pipeline ==========

	/**
	 * Handles an incoming message from a peer node.
	 * Supports PING, LATTICE_QUERY, LATTICE_VALUE, CHALLENGE and explicit rejection
	 * of unscoped DATA_REQUEST messages.
	 * Processing exceptions are contained at this message boundary. A request with an
	 * ID receives an error result where possible; fire-and-forget message failures are
	 * logged. In particular, a store-publication failure after an inbound lattice merge does
	 * not impose a shutdown or retry policy on the node.
	 *
	 * @param message The incoming message
	 */
	void handleIncomingMessage(Message message) {
		log.debug("Received message from peer: {}", message);
		InboundMessageContext context=prepareInboundMessage(message);
		if (context==null) return;

		try {
			dispatchDecodedMessage(message,context);
		} catch (Exception e) {
			returnHandlerFailure(message,e);
		}
	}

	/** Immutable facts established before protocol-specific dispatch begins. */
	private record InboundMessageContext(AConnection connection,ConnectionStats stats,
			LatticePropagator owner,boolean acquired) {}

	/**
	 * Completes the common inbound pre-dispatch pipeline: account for the frame,
	 * resume any asynchronous acquisition, bind the connection capability and decode
	 * with the store access appropriate to its trust state.
	 *
	 * @return dispatch context, or null when the message was rejected or rescheduled
	 */
	private InboundMessageContext prepareInboundMessage(Message message) {
		AConnection connection = message.getConnection();
		ConnectionStats stats = statsFor(connection);
		Throwable acquisitionFailure = acquisitionFailures.remove(message);
		LatticePropagator acquiredOwner = acquiredMessages.remove(message);
		boolean acquired = acquiredOwner != null;
		if (!acquired && acquisitionFailure == null) recordReceived(stats);
		if (acquisitionFailure != null) {
			rejectAcquisitionFailure(message,connection,stats,acquisitionFailure);
			return null;
		}

		LatticePropagator owner;
		try {
			owner = acquired ? acquiredOwner : resolveInboundPropagator(connection);
		} catch (Exception e) {
			recordDecodeError(connection, stats);
			log.warn("Rejected inbound connection ownership: {}", e.getMessage());
			return null;
		}
		if (!decodeOrAcquire(message,connection,owner,acquired,stats)) return null;
		return new InboundMessageContext(connection,stats,owner,acquired);
	}

	/** Handles a failed missing-cell worker back on the ordered dispatcher. */
	private void rejectAcquisitionFailure(Message message,AConnection connection,
			ConnectionStats stats,Throwable failure) {
		if (failure instanceof VirtualMachineError fatal
				&& !(fatal instanceof StackOverflowError)) {
			throw fatal;
		}
		recordMergeReject(connection,stats);
		log.warn("Rejected lattice value after acquisition failure: {}",failure.getMessage());
		returnLatticeResult(message,Result.fromException(failure));
	}

	/**
	 * Decodes one complete message, or starts bounded missing-cell acquisition for a
	 * trusted partial lattice value. Untrusted network input always decodes
	 * storelessly and therefore cannot deposit cells before admission.
	 */
	private boolean decodeOrAcquire(Message message,AConnection connection,
			LatticePropagator owner,boolean acquired,ConnectionStats stats) {
		try {
			AStore decodeStore = (connection == null)
				? ((owner != null) ? owner.getStore() : store)
				: ((owner != null && connection.isTrusted()) ? owner.getStore() : null);
			message.getPayload(decodeStore);
			return true;
		} catch (MissingDataException e) {
			if (!acquired && owner != null && connection != null && connection.isTrusted()) {
				beginLatticeAcquisition(message, owner, stats);
				return false;
			}
			recordDecodeError(connection, stats);
			log.warn("Rejected incomplete inbound message: {}", e.getMessage());
			returnLatticeResult(message,Result.error(ErrorCodes.FORMAT,
				"Only complete lattice values are accepted from unverified connections"));
			return false;
		} catch (Exception e) {
			recordDecodeError(connection, stats);
			log.warn("Failed to decode incoming message: {}", e.getMessage());
			try {
				ACell id = message.getRequestID();
				message.returnMessage(Message.createResult(Result.fromException(e).withID(id)));
			} catch (Exception e2) {
				// best effort -- connection may be bad
			}
			return false;
		}
	}

	/** Dispatches a fully decoded message without changing its capability context. */
	private void dispatchDecodedMessage(Message message,InboundMessageContext context)
			throws BadFormatException, IOException {
		MessageType type = message.getType();
		if (type == MessageType.RESULT) {
			if (inboundVerifier.handleResult(message)) return;
			if (completeDataRequest(message,context.connection())) return;
		}
		switch (type) {
			case PING:
				processPing(message);
				break;
			case LATTICE_QUERY:
				if (context.owner() == null) {
					rejectUnscopedLatticeRequest(message);
				} else {
					processLatticeQuery(message,context.owner());
				}
				break;
			case LATTICE_VALUE:
				dispatchLatticeValue(message,context);
				break;
			case DATA:
				dispatchData(message,context);
				break;
			case DATA_REQUEST:
				if (context.owner() == null) {
					rejectUnscopedDataRequest(message);
				} else {
					processDataRequest(message,context.owner());
				}
				break;
			case CHALLENGE:
				processChallenge(message);
				break;
			case UNKNOWN:
				dispatchApplicationMessage(message,context);
				break;
			default:
				log.debug("Unhandled message type: {}", type);
				break;
		}
	}

	/** Applies the distinct first-pass and post-acquisition lattice-value paths. */
	private void dispatchLatticeValue(Message message,InboundMessageContext context)
			throws BadFormatException {
		if (context.owner() == null) {
			recordMergeReject(context.connection(),context.stats());
			log.warn("Rejected LATTICE_VALUE on an unassigned connection");
			returnLatticeResult(message,Result.error(ErrorCodes.TRUST,
				"Lattice access requires an operator-assigned propagator connection"));
		} else if (context.acquired()) {
			processLatticeValue(message,context.owner());
		} else {
			prepareLatticeValue(message,context.owner(),context.stats());
		}
	}

	/** Enforces trust and view assignment before staging DATA cells. */
	private void dispatchData(Message message,InboundMessageContext context)
			throws BadFormatException, IOException {
		if (context.connection() != null && !context.connection().isTrusted()) {
			recordMergeReject(context.connection(),context.stats());
			log.debug("Rejected unsolicited DATA from an unverified connection");
		} else if (context.owner() == null) {
			recordMergeReject(context.connection(),context.stats());
			log.warn("Rejected DATA on an unassigned connection");
		} else {
			processData(message,context.owner(),context.stats());
		}
	}

	/** Runs a complete application protocol message on the ordered dispatcher. */
	private void dispatchApplicationMessage(Message message,InboundMessageContext context) {
		Predicate<Message> handler=applicationMessageHandler;
		if (handler!=null && handler.test(message)) {
			recordNonMergeAccept(context.stats());
		} else {
			recordMergeReject(context.connection(),context.stats());
			log.debug("Rejected unhandled application message");
		}
	}

	/** Contains a protocol-handler failure at the per-message boundary. */
	private void returnHandlerFailure(Message message,Exception failure) {
		log.warn("Error handling message: {}",failure.getMessage());
		try {
			ACell id=message.getRequestID();
			if (id!=null) message.returnResult(Result.fromException(failure));
		} catch (Exception ignored) {
			// Best effort: the connection may already be unusable.
		}
	}

	/**
	 * Stages a bounded batch of independently addressable cells for a local/internal
	 * delivery path. Unsolicited DATA from an unverified network connection is
	 * rejected by the dispatcher before this method. A verified propagation route
	 * may stage bounded delta-ahead cells. DATA never merges or publishes a root.
	 */
	private void processData(Message message, LatticePropagator owner,
			ConnectionStats stats) throws BadFormatException, IOException {
		AConnection conn=message.getConnection();
		AVector<?> payload=RT.ensureVector(message.getPayload());
		if (payload==null || payload.count()<2
				|| payload.count()>CPoSConstants.MISSING_LIMIT+1
				|| !MessageTag.DATA.equals(payload.get(0))) {
			recordMergeReject(conn,stats);
			throw new BadFormatException("Invalid DATA message format");
		}
		for (long i=1; i<payload.count(); i++) {
			ACell cell=payload.get(i);
			if (cell==null || cell.isEmbedded()) {
				recordMergeReject(conn,stats);
				throw new BadFormatException("DATA message contains invalid cell");
			}
			Cells.store(cell,owner.getStore());
		}
		recordNonMergeAccept(stats);
	}

	/**
	 * Non-blocking Netty delivery entry point. The event loop performs only a bounded
	 * queue offer. When full, Netty pauses reads on this connection and invokes the
	 * returned retry predicate on a virtual thread.
	 */
	Predicate<Message> deliverIncomingMessage(Message message) {
		if (!acceptingInbound) return inboundRejected;
		if (inboundQueue.offer(message)) return null;
		return inboundRetry;
	}

	/**
	 * Admits a reverse message from an authenticated manager-owned outbound client.
	 * This path is deliberately distinct from {@link #inboundPropagators}: the
	 * manager's successful peer challenge grants this connection its owning view,
	 * while an arbitrary inbound socket still requires operator assignment.
	 */
	private void receiveFromManagedOutbound(
			LatticePropagator owner, Convex peer, Message message) {
		AccountKey peerKey = peer.getVerifiedPeer();
		AConnection connection = message.getConnection();
		if (peerKey == null || connection == null
				|| !peerKey.equals(connection.getTrustedKey())
				|| owner.getConnectionManager().getConnection(peerKey) != peer) {
			log.debug("Dropped reverse message from a connection without current outbound admission");
			return;
		}

		LatticePropagator previous = outboundPropagators.putIfAbsent(connection, owner);
		if (previous != null && previous != owner) {
			log.warn("Dropped reverse message whose outbound connection changed propagator ownership");
			return;
		}
		// The client transport has no server-style read-pause hook. Preserve the hard
		// queue bound and rely on periodic root sync/request timeout for recovery.
		if (deliverIncomingMessage(message) != null) {
			log.debug("Dropped reverse lattice message because the inbound queue is full or stopping");
		}
	}

	/** Retry path used after Netty has paused reads for a full inbound queue. */
	private boolean offerInboundBlocking(Message message) {
		if (!acceptingInbound) return false;
		try {
			boolean offered = inboundQueue.offer(message, Config.DEFAULT_INTERNAL_TIMEOUT, TimeUnit.MILLISECONDS);
			if (offered && !acceptingInbound) {
				inboundQueue.remove(message);
				return false;
			}
			return offered;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Starts the sole ordered consumer and opens both message and acquisition gates. */
	private synchronized void startInboundDispatcher() {
		if (inboundRunning) return;
		if (inboundThread != null) {
			throw new IllegalStateException("Previous inbound dispatcher has not been reaped");
		}
		inboundRunning = true;
		acceptingInbound = true;
		synchronized (acquisitionLifecycleLock) {
			acceptingAcquisitions = true;
		}
		inboundThread = new Thread(this::inboundLoop, "NodeServer inbound dispatcher");
		inboundThread.setDaemon(true);
		inboundThread.start();
	}

	/** Drains accepted messages until close has stopped admission and emptied the queue. */
	private void inboundLoop() {
		try {
			while (inboundRunning || !inboundQueue.isEmpty()) {
				try {
					Message message = inboundQueue.poll(1, TimeUnit.SECONDS);
					if (message != null) dispatchInboundMessage(message);
				} catch (InterruptedException e) {
					// Closing interrupts the wait, then the loop drains any accepted messages.
					if (inboundRunning) continue;
				} catch (Exception e) {
					// The per-message handler normally contains Exceptions. Keep this final
					// guard around queue/dispatcher machinery so an implementation defect
					// cannot silently terminate the only inbound consumer.
					log.warn("Unexpected exception in inbound lattice dispatcher", e);
				}
			}
		} finally {
			// If the dispatcher terminates unexpectedly (for example after a fatal JVM
			// error), fail closed. Leaving admission enabled with no consumer would fill
			// the bounded queue and make every connection stall until timeout.
			// Do not acquire this object's monitor here: stopInboundDispatcher joins
			// this thread from a synchronized lifecycle method, so taking the same
			// monitor during termination would force every normal close to hit its
			// join timeout. Both flags are volatile and safe to publish directly.
			if (Thread.currentThread() == inboundThread) {
				acceptingInbound = false;
				inboundRunning = false;
			}
		}
	}

	/**
	 * Runs one accepted message behind a robustness boundary owned by the dispatcher.
	 * {@link #handleIncomingMessage(Message)} contains ordinary Exceptions, while this
	 * outer boundary handles Errors caused by hostile or pathologically deep values.
	 *
	 * <p>A {@link StackOverflowError} is recoverable once the offending call unwinds, so
	 * it is isolated to that connection. Other non-fatal Errors are also isolated: an
	 * assertion or third-party implementation failure in one message must not disable
	 * all network processing. Fatal JVM conditions ({@link VirtualMachineError}, except
	 * stack overflow) are deliberately rethrown; pretending the process is healthy after
	 * such a condition is unsafe. The loop's {@code finally} block disables further
	 * admission if one of these fatal conditions terminates it.
	 */
	private void dispatchInboundMessage(Message message) {
		try {
			handleIncomingMessage(message);
		} catch (StackOverflowError e) {
			log.warn("Rejected inbound message after stack overflow; closing its connection");
			closeFaultingConnection(message);
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Error e) {
			log.warn("Contained unexpected Error while handling inbound message; closing its connection", e);
			closeFaultingConnection(message);
		}
	}

	/** Closes and forgets the connection responsible for an Error at the message boundary. */
	private void closeFaultingConnection(Message message) {
		AConnection conn = message.getConnection();
		if (conn == null) return;
		try {
			conn.close();
		} catch (Exception e) {
			log.debug("Unable to close connection after inbound message failure: {}", e.getMessage());
		} finally {
			removeConnection(conn);
		}
	}

	/**
	 * Stops admission and waits for the sole ordered dispatcher to drain work that was
	 * already accepted. The thread reference is cleared only after termination is
	 * observed. Retaining it on timeout is essential: otherwise a later launch could
	 * start a second consumer while the first still mutates the cursor or uses the store.
	 *
	 * @throws IOException if shutdown is interrupted or the dispatcher does not drain
	 *         within the configured timeout
	 */
	private synchronized void stopInboundDispatcher() throws IOException {
		acceptingInbound = false;
		inboundRunning = false;
		inboundQueue.signalAll();
		Thread thread = inboundThread;
		if (thread == null) return;
		long timeout = config.getInboundShutdownTimeout();
		thread.interrupt();
		try {
			thread.join(timeout);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("NodeServer shutdown interrupted while draining inbound work", e);
		}
		if (thread.isAlive()) {
			log.warn("NodeServer inbound dispatcher did not drain within {} ms", timeout);
			throw new IOException("NodeServer shutdown incomplete: inbound dispatcher did not drain within "
				+ timeout + " ms");
		}
		if (inboundThread == thread) inboundThread = null;
	}

	/**
	 * Cancels every Acquiror owned by this NodeServer and waits until its worker can
	 * no longer touch a propagator store. No new Acquiror can register after the
	 * lifecycle gate closes.
	 */
	private void stopAcquisitions() throws IOException {
		ArrayList<Acquiror> acquisitions = new ArrayList<>();
		synchronized (acquisitionLifecycleLock) {
			acceptingAcquisitions = false;
			for (Set<Acquiror> values : activeAcquirors.values()) {
				acquisitions.addAll(values);
			}
		}

		acquisitions.forEach(Acquiror::close);
		long timeout = config.getInboundShutdownTimeout();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
		try {
			for (Acquiror acquiror : acquisitions) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0
						|| !acquiror.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
					throw new IOException("NodeServer acquisition shutdown incomplete after "
						+ timeout + " ms");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while stopping lattice acquisitions", e);
		}
	}

	/**
	 * Processes a PING message by responding with a RESULT containing the same ID.
	 *
	 * @param message The PING message
	 */
	private void processPing(Message message) {
		ACell id = message.getRequestID();
		if (id == null) return;
		message.returnResult(Result.create(id, CVMLong.create(Utils.getCurrentTimestamp())));
	}

	/**
	 * Processes a LATTICE_QUERY message by returning the value at the specified path.
	 *
	 * <p>Returns the most recently announced (store-backed) value rather than the
	 * live cursor. Never announces directly — that is the propagator's job.
	 *
	 * <p>Payload format: [:LQ id [*path*]]
	 *
	 * @param message The LATTICE_QUERY message
	 * @throws BadFormatException If message format is invalid
	 */
	private void processLatticeQuery(Message message, LatticePropagator owner) throws BadFormatException {
		AVector<?> payload = RT.ensureVector(message.getPayload());
		if (payload == null || payload.count() != 3
				|| !MessageTag.LATTICE_QUERY.equals(payload.get(0))) {
			log.warn("Invalid LATTICE_QUERY message format");
			Result error = Result.create(message.getRequestID(), Strings.create("Invalid LATTICE_QUERY format"), ErrorCodes.ARGUMENT);
			message.returnResult(error);
			return;
		}

		ACell id = payload.get(1);
		ACell pathValue=payload.get(2);
		AVector<?> pathVector = RT.ensureVector(pathValue);
		if (pathVector==null) {
			message.returnResult(Result.create(id,
					Strings.create("LATTICE_QUERY path must be a vector"),ErrorCodes.ARGUMENT));
			return;
		}

		// Query and later DATA_REQUEST resolution use the same capability-bound
		// propagator view. Falling back to the primary would cross a store boundary.
		Root<ACell> announced = owner.getAnnouncedCursor();
		ACell valueAtPath = (pathVector.count() > 0)
			? announced.get(pathVector.toCellArray())
			: announced.get();

		Result result = Result.create(id, valueAtPath);
		message.returnResult(result);
		log.debug("Responded to LATTICE_QUERY at path with length: {}",pathVector.count());
	}

	/**
	 * Serves a DATA_REQUEST only from the store selected for this physical connection.
	 * Query and data resolution therefore expose the same propagator capability; this
	 * method must never search another propagator or fall back to the primary store.
	 */
	private void processDataRequest(Message message, LatticePropagator owner)
			throws BadFormatException {
		Message response = message.makeDataResponse(owner.getStore());
		if (!message.returnMessage(response)) {
			log.debug("Unable to return lattice data: Peer send buffer is full");
		}
	}

	/**
	 * Rejects a DATA_REQUEST when operator policy has not assigned the connection
	 * to a propagator. Choosing any store implicitly would cross a capability boundary.
	 *
	 * @param message The DATA_REQUEST message
	 */
	private void rejectUnscopedDataRequest(Message message) {
		ACell id = message.getRequestID();
		if (id == null) return;
		Result result = Result.create(id,
			Strings.create("DATA_REQUEST requires a configured propagator connection"),
			ErrorCodes.TRUST);
		message.returnResult(result);
	}

	/** Rejects a lattice query before selecting any propagator view. */
	private void rejectUnscopedLatticeRequest(Message message) {
		ACell id = message.getRequestID();
		if (id == null) return;
		message.returnResult(Result.create(id,
			Strings.create("Lattice access requires an operator-assigned propagator connection"),
			ErrorCodes.TRUST));
	}

	/** Returns a correlated lattice result only when the sender supplied an ID. */
	private void returnLatticeResult(Message message, Result result) {
		ACell id = message.getRequestID();
		if (id == null || message.getConnection() == null) return;
		if (!message.returnMessage(Message.createResult(result.withID(id)))) {
			log.debug("Unable to return lattice result: Peer send buffer is full");
		}
	}

	/** Answers only challenges carrying the fixed lattice-peer protocol context. */
	private void processChallenge(Message message) {
		message.respondToChallenge(transportKeyPair,
			Message.LATTICE_PEER_CHALLENGE_CONTEXT::equals);
	}

	/**
	 * Completes a reverse DATA_REQUEST future. Results are correlated by both
	 * physical connection and request ID, so one Peer cannot satisfy another
	 * connection's acquisition request.
	 */
	private boolean completeDataRequest(Message message, AConnection connection) {
		if (connection == null) return false;
		ConcurrentHashMap<ACell, CompletableFuture<Result>> byID = pendingDataRequests.get(connection);
		if (byID == null) return false;
		try {
			ACell id = message.getResultID();
			if (id == null) return false;
			CompletableFuture<Result> future = byID.remove(id);
			if (byID.isEmpty()) pendingDataRequests.remove(connection, byID);
			if (future == null) return false;
			future.complete(message.toResult());
			return true;
		} catch (Exception e) {
			log.warn("Unable to correlate lattice data response: {}", e.getMessage());
			return false;
		}
	}

	// ========== Missing-cell Acquisition ==========

	/**
	 * Persists a complete inbound value in its owning propagator store before
	 * allowing the ordered dispatcher to call lattice merge code.
	 */
	private void prepareLatticeValue(Message message, LatticePropagator owner,
			ConnectionStats stats) {
		try {
			Message complete = completeLatticeMessage(message, owner.getStore());
			processLatticeValue(complete, owner);
		} catch (MissingDataException e) {
			AConnection connection=message.getConnection();
			if (connection!=null && connection.isTrusted()) {
				beginLatticeAcquisition(message, owner, stats);
			} else {
				recordDecodeError(connection,stats);
				returnLatticeResult(message,Result.error(ErrorCodes.FORMAT,
					"Only complete lattice values are accepted from unverified connections"));
			}
		} catch (BadFormatException e) {
			recordMergeReject(message.getConnection(),stats);
			returnLatticeResult(message,Result.error(ErrorCodes.FORMAT,e.getMessage()));
		} catch (IOException e) {
			recordMergeReject(message.getConnection(), stats);
			log.warn("Unable to persist inbound lattice value in its propagator store", e);
			returnLatticeResult(message, Result.fromException(e));
		}
	}

	/**
	 * Returns a message whose lattice value is known complete, admitted and persisted
	 * in the supplied propagator store. This is the sole gateway into
	 * processLatticeValue.
	 */
	private Message completeLatticeMessage(Message message, AStore acquisitionStore)
			throws BadFormatException, IOException {
		LatticeValuePayload payload=parseLatticeValuePayload(message);
		ACell value=payload.value();

		// Prove completeness before either admission or persistence. Unverified
		// connections are decoded storelessly, while verified acquisition may refer
		// only to cells obtained through correlated DATA_REQUEST results.
		HashSet<Hash> missing = new HashSet<>();
		Ref.get(value).findMissing(missing, 1);
		if (!missing.isEmpty()) {
			throw new MissingDataException(acquisitionStore, missing.iterator().next());
		}
		if (!withinInboundSizeLimit(value)) {
			throw new BadFormatException("Acquired lattice value exceeds inbound size limit");
		}

		ACell[] path=extractPath(payload.path());
		ACell admitted=ingressFilter.filter(path,value);
		if (admitted==null) throw new BadFormatException("Inbound lattice value is not locally desired");
		if (!withinInboundSizeLimit(admitted)) {
			throw new BadFormatException("Projected lattice value exceeds inbound size limit");
		}
		ACell complete=Cells.persist(admitted,acquisitionStore);

		AVector<?> completePayload = Vectors.create(
			MessageTag.LATTICE_VALUE,payload.id(),payload.path(),complete);
		return Message.create(MessageType.LATTICE_VALUE, completePayload)
			.withConnection(message.getConnection());
	}

	/**
	 * Starts bounded acquisition outside the ordered dispatcher. Completion or
	 * failure is posted back to that dispatcher, so no slow Peer can block unrelated
	 * merges and per-connection metrics retain a single writer.
	 */
	private void beginLatticeAcquisition(Message message, LatticePropagator owner,
			ConnectionStats stats) {
		AConnection connection = message.getConnection();
		if (connection == null) {
			recordMergeReject(null, stats);
			return;
		}
		if (!acquisitionPermits.tryAcquire()) {
			recordMergeReject(connection, stats);
			log.warn("Rejected incomplete lattice value: acquisition capacity exhausted");
			returnLatticeResult(message, Result.error(ErrorCodes.LOAD,
				"Lattice acquisition capacity exhausted"));
			return;
		}

		CompletableFuture<Message> acquisition;
		try {
			acquisition = acquireLatticeMessage(message, owner.getStore(), connection);
		} catch (Throwable t) {
			// The permit belongs to this acquisition attempt even when hostile
			// decoding fails before an asynchronous stage can be returned.
			acquisitionPermits.release();
			throw t;
		}

		acquisition.whenComplete((complete, error) -> {
				try {
					if (error == null) {
						acquiredMessages.put(complete, owner);
						if (!offerInboundBlocking(complete)) acquiredMessages.remove(complete);
					} else {
						acquisitionFailures.put(message, unwrapCompletion(error));
						if (!offerInboundBlocking(message)) acquisitionFailures.remove(message);
					}
				} finally {
					acquisitionPermits.release();
				}
		});
	}

	private static Throwable unwrapCompletion(Throwable error) {
		while ((error instanceof java.util.concurrent.CompletionException
				|| error instanceof java.util.concurrent.ExecutionException)
				&& error.getCause() != null) {
			error = error.getCause();
		}
		return error;
	}

	/** Acquires missing protocol/value roots without creating a NodeServer worker. */
	private CompletableFuture<Message> acquireLatticeMessage(Message message,
			AStore acquisitionStore, AConnection connection) {
		try {
			message.getPayload(acquisitionStore);
		} catch (MissingDataException e) {
			return acquireHash(connection, acquisitionStore, e.getMissingHash())
				.thenCompose(value -> acquireLatticeMessage(message, acquisitionStore, connection));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}

		try {
			if (message.getType() != MessageType.LATTICE_VALUE) {
				throw new BadFormatException("Missing data acquisition is only valid for LATTICE_VALUE");
			}
			try {
				return CompletableFuture.completedFuture(
					completeLatticeMessage(message, acquisitionStore));
			} catch (MissingDataException e) {
				// Re-dereferencing the payload can repeat the same exception. Acquire
				// the precise missing ref and iterate until the value is complete.
				return acquireHash(connection, acquisitionStore, e.getMissingHash())
					.thenCompose(value -> acquireLatticeMessage(
						message, acquisitionStore, connection));
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Creates one owned Acquiror for the missing root. */
	private CompletableFuture<ACell> acquireHash(AConnection connection,
			AStore acquisitionStore, Hash hash) {
		Acquiror acquiror = Acquiror.create(hash, acquisitionStore,
			hashes -> requestMissing(connection, hashes));
		Set<Acquiror> acquisitions;
		synchronized (acquisitionLifecycleLock) {
			if (!acceptingAcquisitions) {
				acquiror.close();
				return CompletableFuture.failedFuture(
					new IOException("NodeServer is not accepting lattice acquisitions"));
			}
			acquisitions = activeAcquirors.computeIfAbsent(connection,
				c -> ConcurrentHashMap.newKeySet());
			acquisitions.add(acquiror);
		}
		acquiror.getTerminationFuture().whenComplete((ignored, error) -> {
			synchronized (acquisitionLifecycleLock) {
				acquisitions.remove(acquiror);
				if (acquisitions.isEmpty()) activeAcquirors.remove(connection, acquisitions);
			}
		});

		return acquiror.getFuture();
	}

	/** Requests one correlated batch from the update's physical connection. */
	private CompletableFuture<Result> requestMissing(AConnection connection, Hash[] hashes) {
		if (connection == null || connection.isClosed()) {
			return CompletableFuture.failedFuture(
				new IOException("Lattice source connection is closed"));
		}

		ACell id = connection.nextRequestID();
		CompletableFuture<Result> future = new CompletableFuture<>();
		ConcurrentHashMap<ACell, CompletableFuture<Result>> byID =
			pendingDataRequests.computeIfAbsent(connection, c -> new ConcurrentHashMap<>());
		byID.put(id, future);

		future.orTimeout(Config.DEFAULT_INTERNAL_TIMEOUT, TimeUnit.MILLISECONDS);
		future.whenComplete((result, error) -> {
			byID.remove(id);
			if (byID.isEmpty()) pendingDataRequests.remove(connection, byID);
		});

		try {
			if (connection.isClosed()
					|| !connection.sendMessage(Message.createDataRequest(id, hashes))) {
				future.completeExceptionally(new IOException("Unable to send lattice DATA_REQUEST"));
			}
		} catch (Exception e) {
			future.completeExceptionally(e);
		}
		return future;
	}

	/**
	 * Processes an incoming LATTICE_VALUE message from a peer.
	 *
	 * <p>Navigates to the target path via {@code cursor.path()}, merges the
	 * received value, then calls {@code cursor.sync()} to notify propagators. Network
	 * delivery is first handed to a bounded dispatcher, so this synchronous publication
	 * work never blocks a shared Netty event-loop thread.
	 *
	 * <p>The confirmed payload is [:LV id [*path*] value]. The optimistic push
	 * form [:LV [*path*] value] is normalised before this method.
	 *
	 * @param message The LATTICE_VALUE message
	 * @throws BadFormatException If message format is invalid
	 */
	private void processLatticeValue(Message message, LatticePropagator owner) throws BadFormatException {
		AConnection conn = message.getConnection();
		ConnectionStats stats = statsFor(conn);

		LatticeValuePayload payload=parseLatticeValuePayload(message);
		ACell value=payload.value();

		// #564: bound merge cost from untrusted peers — reject an oversized value before
		// the synchronous dispatcher merge runs.
		if (!withinInboundSizeLimit(value)) {
			recordMergeReject(conn, stats);
			returnLatticeResult(message, Result.error(ErrorCodes.MEMORY,
				"LATTICE_VALUE exceeds the inbound size limit"));
			return;
		}

		// Navigate to target path and merge
		ACell[] path = extractPath(payload.path());
		ALatticeCursor<ACell> target = cursor.path(path);

		ACell before = target.get();

		// A rejected merge leaves the cursor unchanged (atomic abort), so there is
		// nothing to sync or propagate.
		if (!mergeIncoming(target, value)) {
			recordMergeReject(conn, stats);
			returnLatticeResult(message, Result.error(ErrorCodes.ARGUMENT,
				"Lattice merge rejected"));
			return;
		}

		// #566: a successful merge resets the connection's consecutive-reject streak.
		recordAccept(stats);

		// A valid replay is accepted but must not trigger another announce/root write.
		// Lattice merges conventionally preserve identity on no-op; equals is the
		// defensive fallback for implementations that return an equivalent value.
		ACell after = target.get();
		boolean changed = before != after && (before == null || !before.equals(after));
		if (changed) {
			// Keep the ingress propagator's subset current until the accepted primary
			// value is projected back through normal fan-out. Merge remains the sole
			// validation mechanism; this is only replica/view bookkeeping.
			try {
				owner.mergeInbound(path, value);
			} catch (RuntimeException e) {
				// The authoritative merge has already succeeded. A failed staging
				// optimisation must not reject it; fan-out below reconstructs the view.
				log.debug("Unable to stage accepted inbound value in propagator view: {}",
					e.getMessage());
			}

			// Notify propagators that cursor state has changed. This is a synchronous
			// primary-store publication on the dispatcher thread, never on a Netty event loop.
			cursor.sync();

		}

		notifyInboundLatticeListener(conn,owner,path,value,changed);

		// The response is deliberately empty: completion is the acknowledgement, and
		// returning the merged value would duplicate a potentially large lattice tree.
		// Check the ID before constructing anything so normal fire-and-forget gossip
		// retains an allocation-free response path.
		ACell id = message.getRequestID();
		if (id != null && conn != null) {
			if (!message.returnMessage(Message.createResult(id, null, null))) {
				log.debug("Unable to return lattice result: Peer send buffer is full");
			}
		}
	}

	/** Notifies application policy without changing an already completed merge. */
	private void notifyInboundLatticeListener(AConnection connection,
			LatticePropagator owner,ACell[] path,ACell value,boolean changed) {
		InboundLatticeListener listener=inboundLatticeListener;
		if (listener==null) return;
		try {
			listener.onAccepted(connection,owner,path,value,changed);
		} catch (RuntimeException e) {
			log.warn("Inbound lattice listener failed after an accepted merge",e);
		}
	}

	/**
	 * Parses and validates the optimistic and confirmed lattice-value envelopes.
	 * The optimistic three-field form has no request ID and is normalised to a null ID.
	 */
	private static LatticeValuePayload parseLatticeValuePayload(Message message)
			throws BadFormatException {
		AVector<?> payload=RT.ensureVector(message.getPayload());
		if (payload==null || payload.count()==0
				|| !MessageTag.LATTICE_VALUE.equals(payload.get(0))) {
			throw new BadFormatException("Invalid LATTICE_VALUE message format");
		}

		long count=payload.count();
		ACell id;
		ACell pathCell;
		ACell value;
		if (count==4) {
			id=payload.get(1);
			if (id!=null && RT.ensureLong(id)==null) {
				throw new BadFormatException("LATTICE_VALUE ID must be a long or nil");
			}
			pathCell=payload.get(2);
			value=payload.get(3);
		} else if (count==3) {
			id=null;
			pathCell=payload.get(1);
			value=payload.get(2);
		} else {
			throw new BadFormatException("Invalid LATTICE_VALUE message format");
		}

		AVector<?> path=RT.ensureVector(pathCell);
		if (path==null) throw new BadFormatException("LATTICE_VALUE path must be a vector");
		if (value==null) throw new BadFormatException("LATTICE_VALUE message missing value");
		return new LatticeValuePayload(id,path,value);
	}

	private record LatticeValuePayload(ACell id,AVector<?> path,ACell value) {}

	/**
	 * Extracts a path array from a validated message path vector.
	 *
	 * @param pathVector Path vector from a lattice protocol message
	 * @return Array of path keys (empty for the root)
	 */
	private static ACell[] extractPath(AVector<?> pathVector) {
		return pathVector.toCellArray();
	}

	/**
	 * #564: whether an inbound LATTICE_VALUE is within the configured size limit for
	 * merging. Values whose memory size exceeds
	 * {@link NodeConfig#getMaxInboundValueSize()} are rejected before the merge runs on
	 * the dispatcher thread, bounding merge cost from untrusted peers. {@code getMemorySize}
	 * is cached (computed at decode), so this is O(1). Package-visible for testing.
	 *
	 * @param value inbound value (may be null)
	 * @return true if the value may be merged, false if it is too large
	 */
	boolean withinInboundSizeLimit(ACell value) {
		long max = config.getMaxInboundValueSize();
		long size = ACell.getMemorySize(value);
		if (size > max) {
			log.warn("Rejected oversized inbound LATTICE_VALUE: {} bytes exceeds limit of {}", size, max);
			return false;
		}
		return true;
	}

	/**
	 * Merges an incoming (untrusted) value into a lattice cursor at the target path.
	 * Does not notify propagators — the caller syncs only if the merge is accepted.
	 *
	 * <p>Per the lattice contract, {@code merge} is the enforcement point and aborts
	 * atomically on bad data (see {@link convex.lattice.ALattice}), so a rejected value
	 * leaves the cursor unchanged. A value that is not this lattice's type surfaces as a
	 * {@link ClassCastException} — the erased {@code (T)} cast cannot catch it earlier —
	 * and is treated here as an explicit rejection rather than a generic merge error.</p>
	 *
	 * @param <T> Type of cursor value
	 * @param target Lattice cursor at the merge target (from {@code cursor.path(...)})
	 * @param value Value to merge (untrusted; may be the wrong type for this lattice)
	 * @return true if the value was merged, false if it was rejected
	 */
	@SuppressWarnings("unchecked")
	<T extends ACell> boolean mergeIncoming(ALatticeCursor<T> target, ACell value) {
		try {
			target.merge((T) value);
			return true;
		} catch (ClassCastException e) {
			// Wrong type for this lattice: the (T) cast is erased, so the mismatch only
			// surfaces inside merge. Reject cleanly rather than as a generic error.
			log.warn("Rejected inbound lattice value of wrong type: {}",
					(value == null) ? "null" : value.getClass().getSimpleName());
			return false;
		} catch (StackOverflowError e) {
			// A pathologically deep untrusted value can exhaust the current stack. The
			// cursor update aborts atomically, and this Error is safe to contain once the
			// stack has unwound.
			log.warn("Rejected inbound lattice value (merge failed: {})", e.toString());
			return false;
		} catch (Exception e) {
			// Ordinary implementation failures reject this merge without terminating the
			// ordered dispatcher. Other Errors deliberately propagate to its outer boundary:
			// non-fatal Errors close the responsible connection, while fatal VM conditions
			// terminate the dispatcher and disable further admission.
			log.warn("Rejected inbound lattice value (merge failed: {})", e.toString());
			return false;
		}
	}

	// ===== Per-connection inbound metrics & circuit-breaker (#566) =====

	/**
	 * Per-connection inbound counters. A connection's counters are written only from that
	 * ordered inbound dispatcher, so plain {@code volatile long}s are correct and cheap:
	 * exactly one counter writer, with volatile giving visibility to aggregate/operator
	 * reads and disconnect cleanup on other threads.
	 */
	static final class ConnectionStats {
		/** Total messages received on this connection. */
		volatile long messagesReceived;
		/** Merges accepted (value incorporated). */
		volatile long mergesAccepted;
		/** Merges rejected (bad/oversized/wrong-type value, malformed LATTICE_VALUE). */
		volatile long mergesRejected;
		/** Undecodable messages. */
		volatile long decodeErrors;
		/** Consecutive rejects/decode-errors since the last accepted merge (drives the breaker). */
		volatile long consecutiveRejects;
		/** Wall-clock timestamp of the last reject/decode-error. */
		volatile long lastErrorTimestamp;
	}

	/**
	 * Gets (creating if needed) the stats for a connection, or null for a connection-less
	 * (in-JVM / local) message which is not rate-tracked. Opportunistically sweeps closed
	 * connections when the map grows large, bounding memory from connection churn.
	 *
	 * @param conn originating connection, or null
	 * @return the connection's stats, or null if {@code conn} is null
	 */
	ConnectionStats statsFor(AConnection conn) {
		if (conn == null) return null;
		if (connectionStats.size() > MAX_TRACKED_CONNECTIONS) {
			sweepClosedConnections();
		}
		return connectionStats.computeIfAbsent(conn, k -> new ConnectionStats());
	}

	/**
	 * Releases all inbound state held for a connection (#566). This is the single cleanup
	 * sink for the physical inbound lifecycle, including any authenticated outbound-route
	 * upgrade, and is invoked from three places:
	 * the network layer's disconnect hook when a connection closes (the eager path — see
	 * {@code NettyServer}/{@code AServer#setDisconnectAction}), the circuit-breaker when it
	 * closes an abusive connection, and the sweep backstops below. It is also where any
	 * future per-connection inbound state (work queues, rate limiters) should be torn down.
	 * Distinct from {@link #removePeer(AccountKey)}, which manages outbound, identity-keyed
	 * peer connections.
	 *
	 * @param conn connection to forget (may be null)
	 */
	void removeConnection(AConnection conn) {
		if (conn == null) return;
		inboundVerifier.forget(conn);
		connectionStats.remove(conn);
		inboundPropagators.remove(conn);
		outboundPropagators.remove(conn);
		for (LatticePropagator propagator : propagators) {
			propagator.getConnectionManager().removeUpgradedInboundConnection(conn);
		}
		ConcurrentHashMap<ACell, CompletableFuture<Result>> pending = pendingDataRequests.remove(conn);
		if (pending != null) {
			IOException closed = new IOException("Lattice source connection closed during acquisition");
			pending.values().forEach(future -> future.completeExceptionally(closed));
		}
		Set<Acquiror> acquisitions = activeAcquirors.get(conn);
		if (acquisitions != null) acquisitions.forEach(Acquiror::close);
		acquiredMessages.keySet().removeIf(message -> message.getConnection() == conn);
		acquisitionFailures.keySet().removeIf(message -> message.getConnection() == conn);
	}

	/**
	 * Prunes closed connections from the stats map (#566). A backstop to the eager disconnect
	 * hook: called opportunistically from {@link #statsFor} when the map grows past
	 * {@link #MAX_TRACKED_CONNECTIONS} (bounding memory during connection churn) and
	 * periodically from {@link #maintenanceLoop} — so entries still drain if a disconnect
	 * event is missed or a transport does not surface one.
	 */
	void sweepClosedConnections() {
		Set<AConnection> candidates = new java.util.HashSet<>(connectionStats.keySet());
		candidates.addAll(inboundPropagators.keySet());
		candidates.addAll(outboundPropagators.keySet());
		candidates.addAll(pendingDataRequests.keySet());
		candidates.addAll(activeAcquirors.keySet());
		for (AConnection connection : candidates) {
			if (connection.isClosed()) removeConnection(connection);
		}
	}

	/**
	 * Periodic maintenance loop: sweeps closed connections and checkpoints dirty
	 * persistent stores. Reusing this loop keeps checkpoint policy in NodeServer
	 * without adding another store-writer thread.
	 */
	private void maintenanceLoop() {
		long checkpointInterval = config.getPersistInterval();
		long waitInterval = (checkpointInterval > 0L)
				? Math.min(CONNECTION_SWEEP_INTERVAL, checkpointInterval)
				: CONNECTION_SWEEP_INTERVAL;
		long now = Utils.getCurrentTimestamp();
		long lastConnectionSweep = now;
		long lastCheckpoint = now;
		while (lifecycleState == LifecycleState.STARTING
				|| lifecycleState == LifecycleState.RUNNING) {
			try {
				synchronized (maintenanceSignal) {
					if (lifecycleState == LifecycleState.STARTING
							|| lifecycleState == LifecycleState.RUNNING) {
						maintenanceSignal.wait(waitInterval);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			if (lifecycleState != LifecycleState.STARTING
					&& lifecycleState != LifecycleState.RUNNING) break;
			now = Utils.getCurrentTimestamp();
			if (now - lastConnectionSweep >= CONNECTION_SWEEP_INTERVAL) {
				sweepClosedConnections();
				lastConnectionSweep = now;
			}
			if (checkpointInterval > 0L && now - lastCheckpoint >= checkpointInterval) {
				lastCheckpoint = now;
				checkpointDirtyStores();
			}
		}
	}

	/** Stops maintenance without interrupting a thread that may be forcing a file. */
	private void stopMaintenance() throws IOException {
		Thread maintenance = maintenanceThread;
		if (maintenance == null) return;
		synchronized (maintenanceSignal) {
			maintenanceSignal.notifyAll();
		}
		try {
			maintenance.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while stopping NodeServer maintenance",e);
		}
		maintenanceThread = null;
	}

	/** Completes periodic barriers independently for every dirty propagator store. */
	private void checkpointDirtyStores() {
		if (!config.isPersist()) return;
		for (LatticePropagator propagator : propagators) {
			try {
				propagator.checkpoint();
			} catch (IOException e) {
				log.warn("Periodic lattice-store checkpoint failed; will retry", e);
			}
		}
	}

	/**
	 * Records an inbound message received on a connection (#566). Null-safe: a no-op for
	 * connection-less (in-JVM) messages, so callers need not guard.
	 *
	 * @param stats the connection's stats (may be null)
	 */
	private void recordReceived(ConnectionStats stats) {
		if (stats != null) stats.messagesReceived++;
	}

	/**
	 * Records an undecodable inbound message against a connection (#566), tripping the
	 * circuit-breaker if the consecutive-reject limit is reached.
	 *
	 * @param conn originating connection (may be null)
	 * @param stats the connection's stats (may be null)
	 */
	private void recordDecodeError(AConnection conn, ConnectionStats stats) {
		if (stats == null) return;
		stats.decodeErrors++;
		registerReject(conn, stats);
	}

	/**
	 * Records a rejected merge — a malformed, oversized, wrong-type or invalid value — against
	 * a connection (#566), tripping the circuit-breaker if the consecutive-reject limit is reached.
	 *
	 * @param conn originating connection (may be null)
	 * @param stats the connection's stats (may be null)
	 */
	private void recordMergeReject(AConnection conn, ConnectionStats stats) {
		if (stats == null) return;
		stats.mergesRejected++;
		registerReject(conn, stats);
	}

	/**
	 * Shared reject bookkeeping (#566): advances the consecutive-reject streak and, if the
	 * configured limit is reached, trips the circuit-breaker — closing the connection and
	 * dropping its stats. The caller has already bumped the specific reject counter.
	 *
	 * @param conn originating connection (may be null)
	 * @param stats the connection's stats (non-null)
	 */
	private void registerReject(AConnection conn, ConnectionStats stats) {
		stats.consecutiveRejects++;
		stats.lastErrorTimestamp = Utils.getCurrentTimestamp();

		long limit = config.getMaxConsecutiveRejects();
		if (limit > 0 && stats.consecutiveRejects >= limit && conn != null && !conn.isClosed()) {
			log.warn("Circuit-breaker: closing connection {} after {} consecutive bad inbound messages",
					conn, stats.consecutiveRejects);
			try {
				conn.close();
			} catch (Exception e) {
				// best effort — connection may already be failing
			}
			removeConnection(conn);
		}
	}

	/**
	 * Records an accepted merge against a connection, resetting its consecutive-reject streak.
	 *
	 * @param stats the connection's stats (may be null)
	 */
	private void recordAccept(ConnectionStats stats) {
		if (stats == null) return;
		stats.mergesAccepted++;
		stats.consecutiveRejects = 0;
	}

	/** Records a valid non-merge protocol message without inflating merge metrics. */
	private void recordNonMergeAccept(ConnectionStats stats) {
		if (stats != null) stats.consecutiveRejects=0;
	}

	/**
	 * Immutable aggregate snapshot of inbound counters across all currently-tracked
	 * connections (#566). Intended for operator observability — logging, health endpoints,
	 * or tests. Connections closed by the circuit-breaker are no longer counted.
	 */
	public static final class InboundStats {
		/** Number of connections currently tracked. */
		public final long connections;
		/** Total inbound messages received. */
		public final long messagesReceived;
		/** Total merges accepted. */
		public final long mergesAccepted;
		/** Total merges rejected. */
		public final long mergesRejected;
		/** Total undecodable messages. */
		public final long decodeErrors;

		InboundStats(long connections, long messagesReceived, long mergesAccepted,
				long mergesRejected, long decodeErrors) {
			this.connections = connections;
			this.messagesReceived = messagesReceived;
			this.mergesAccepted = mergesAccepted;
			this.mergesRejected = mergesRejected;
			this.decodeErrors = decodeErrors;
		}

		@Override
		public String toString() {
			return "InboundStats[connections=" + connections + ", received=" + messagesReceived
					+ ", accepted=" + mergesAccepted + ", rejected=" + mergesRejected
					+ ", decodeErrors=" + decodeErrors + "]";
		}
	}

	/**
	 * Returns an aggregate snapshot of inbound metrics across all tracked connections (#566).
	 *
	 * @return immutable aggregate inbound stats snapshot
	 */
	public InboundStats getInboundStats() {
		long conns = 0, recv = 0, acc = 0, rej = 0, dec = 0;
		for (ConnectionStats s : connectionStats.values()) {
			conns++;
			recv += s.messagesReceived;
			acc += s.mergesAccepted;
			rej += s.mergesRejected;
			dec += s.decodeErrors;
		}
		return new InboundStats(conns, recv, acc, rej, dec);
	}

	// ========== Pull and Merge API ==========

	/**
	 * Pulls the latest lattice value from a specific peer and merges it locally.
	 *
	 * <p>The primary propagator only acquires the full value tree into its store.
	 * NodeServer then merges through the authoritative root cursor and synchronously
	 * publishes that merged root before it can be re-propagated.
	 *
	 * @param convex Convex connection to the peer node
	 * @return CompletableFuture that completes with the current cursor value after merge
	 */
	public CompletableFuture<V> pull(Convex convex) {
		if (propagators.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("No propagators configured"));
		}
		return propagators.get(0).pull(convex).thenApply(acquired -> {
			mergePulledValue(acquired);
			// Always sync the root, even for a dominated pull. Local application writes
			// may not yet have been published, and the raw peer value must never become
			// the persisted or announced root independently of the merged cursor.
			cursor.sync();
			return cursor.get();
		});
	}

	/**
	 * Pulls one path from a peer and merges it at the same local cursor path.
	 * Path selection reduces transfer and storage work; it does not grant or
	 * enforce visibility independently of the selected propagator.
	 *
	 * <p>The caller must not mutate {@code path} while the returned operation is
	 * outstanding.</p>
	 *
	 * @param convex Convex connection to the peer node
	 * @param path path within both peer and local lattice roots
	 * @return future completing with the local value at the path after the merge
	 */
	public CompletableFuture<ACell> pullPath(Convex convex, ACell... path) {
		if (propagators.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("No propagators configured"));
		}
		return propagators.get(0).pullPath(convex,path).thenApply(acquired -> {
			ALatticeCursor<ACell> target=cursor.path(path);
			if (acquired!=null) mergeIncoming(target,acquired);

			// Publish any pending local writes even when this pull was absent,
			// rejected or dominated, matching root pull semantics.
			cursor.sync();

			return target.get();
		});
	}

	/**
	 * Pulls the latest lattice value from all connected peers and merges locally.
	 *
	 * <p>The primary propagator acquires full value trees from every peer in
	 * parallel. NodeServer merges all successful results through its root cursor,
	 * then performs one synchronous publication of the combined result.
	 *
	 * @return true if all pulls completed successfully, false otherwise
	 */
	public boolean pull() {
		if (propagators.isEmpty()) {
			log.debug("No propagators configured — cannot pull");
			return true;
		}

		try {
			List<ACell> acquiredValues = propagators.get(0).pullAll().get(30, TimeUnit.SECONDS);
			for (ACell acquired : acquiredValues) {
				mergePulledValue(acquired);
			}
			// Persist and announce the combined root once, never an individual peer's
			// pre-merge value.
			cursor.sync();
			return true;
		} catch (Exception e) {
			log.warn("Pull failed: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Merges one store-backed pull result into the authoritative root cursor without
	 * propagating it. The caller publishes only after the root merge has completed.
	 */
	@SuppressWarnings("unchecked")
	private void mergePulledValue(ACell acquired) {
		if (acquired == null) return;
		cursor.updateAndGet(current -> lattice.merge(mergeContext, current, (V) acquired));
	}

	/**
	 * @deprecated Use {@link #pull(Convex)} instead
	 */
	@Deprecated
	public CompletableFuture<V> syncWithPeer(Convex convex) {
		return pull(convex);
	}

	/**
	 * Updates the local lattice value by merging with a received value.
	 *
	 * This method performs an atomic merge operation using the cursor's
	 * updateAndGet method, ensuring thread-safe updates.
	 *
	 * @param receivedValue The value received from a peer
	 * @return The merged value, or null if merge was not performed (e.g., invalid foreign value)
	 */
	public V mergeValue(V receivedValue) {
		if (receivedValue == null) {
			return null;
		}

		// Validate foreign value before attempting merge
		if (!lattice.checkForeign(receivedValue)) {
			log.debug("Rejected invalid foreign lattice value");
			return null;
		}

		return cursor.merge(receivedValue);
	}

	// ========== Compatibility Peer API ==========

	/**
	 * Adds a peer connection to the primary propagator.
	 *
	 * @param peerKey AccountKey identifying the remote peer
	 * @param convex Convex connection to the peer node
	 * @deprecated Use {@code getPropagator().addPeer(peerKey, convex)} directly
	 */
	@Deprecated
	public void addPeer(AccountKey peerKey, Convex convex) {
		if (propagators.isEmpty()) {
			log.warn("Cannot add peer: no propagators configured");
			return;
		}
		propagators.get(0).addPeer(peerKey, convex);
	}

	/**
	 * Removes a peer from the primary propagator.
	 *
	 * @param peerKey AccountKey of the peer to remove
	 * @deprecated Use {@code getPropagator().removePeer(peerKey)} directly
	 */
	@Deprecated
	public void removePeer(AccountKey peerKey) {
		if (propagators.isEmpty()) return;
		propagators.get(0).removePeer(peerKey);
	}

	// ========== State and Configuration API ==========

	/**
	 * Gets the current local lattice value.
	 *
	 * @return Current local lattice value
	 */
	public V getLocalValue() {
		return cursor.get();
	}

	/**
	 * Gets the memory-first cursor for the lattice value.
	 *
	 * <p>Cursor writes perform no persistence. Calling {@link ALatticeCursor#sync()}
	 * runs the primary publication pipeline synchronously. A successful return confirms
	 * that the root and its reachable cells are available from the primary store, but
	 * does not perform a physical durability barrier. A primary-store publication failure
	 * throws {@link StoreException} without rolling back the in-memory cursor. NodeServer
	 * remains running so recovery remains operator policy.
	 *
	 * @return The value cursor
	 */
	public ALatticeCursor<V> getCursor() {
		return cursor;
	}

	/**
	 * Gets the generic root component for applications hosted by this server.
	 *
	 * <p>Application branches should attach to this component rather than depend
	 * on NodeServer directly. Persistence delegates to the server's primary store;
	 * syncing the component publishes through the root cursor's normal pipeline.</p>
	 *
	 * @return Root application component
	 */
	public RootComponent<V> getRootComponent() {
		return rootComponent;
	}

	/**
	 * Sets the merge context used for all lattice merge operations.
	 * The context carries signing keys and owner verification through the
	 * lattice hierarchy (e.g. OwnerLattice, SignedLattice).
	 *
	 * <p><b>Configuration-only (#568).</b> This must be called before {@link #launch()}.
	 * The context is then read by pull operations and the inbound dispatcher thread
	 * (root validation and merges), and is safely published to them via the
	 * happens-before edge of thread start — so
	 * the field is deliberately non-volatile. Setting it after launch is rejected: those
	 * threads could otherwise observe a stale reference indefinitely, and any in-flight
	 * merge would already have captured the old context, giving non-deterministic
	 * signing-key behaviour.</p>
	 *
	 * @param context Merge context (must not be null — use LatticeContext.EMPTY for default)
	 * @throws IllegalStateException if called after {@link #launch()}
	 */
	public synchronized void setMergeContext(LatticeContext context) {
		if (context == null) throw new IllegalArgumentException("Use LatticeContext.EMPTY instead of null");
		requireNewLifecycle("setMergeContext");
		long configuredSkew=config.getMaxFutureTimestampSkew();
		long effectiveSkew=config.getMap().containsKey(NodeConfig.MAX_FUTURE_TIMESTAMP_SKEW)
			?configuredSkew:context.getMaxFutureTimestampSkew(configuredSkew);
		this.mergeContext = context.withMaxFutureTimestampSkew(effectiveSkew);
		// Propagate to lattice cursor so path-navigated cursors inherit it
		cursor.setContext(mergeContext);
	}

	/**
	 * Sets the key used exclusively for node-to-node transport challenge/response.
	 *
	 * <p>This is deliberately independent of {@link #setMergeContext}. A transport
	 * identity neither authorises application data nor has to be the key used by a
	 * signed lattice. When a particular application intentionally binds the two,
	 * its wrapper must supply that same key to both configurations explicitly.</p>
	 *
	 * @param keyPair transport identity key, or null to disable authenticated routes
	 * @throws IllegalStateException if called after the first launch begins
	 */
	public synchronized void setTransportKeyPair(AKeyPair keyPair) {
		requireNewLifecycle("setTransportKeyPair");
		this.transportKeyPair=keyPair;
	}

	/** Transport challenge key used by the inbound verifier, or null. */
	AKeyPair getTransportKeyPair() {
		return transportKeyPair;
	}

	/**
	 * Gets the port this server is listening on.
	 *
	 * @return Port number, or null if not bound
	 */
	public Integer getPort() {
		return port;
	}

	/**
	 * Gets the host address this server is bound to.
	 *
	 * @return The host address, or null if server is not launched
	 */
	public InetSocketAddress getHostAddress() {
		if (networkServer != null && lifecycleState == LifecycleState.RUNNING) {
			return networkServer.getHostAddress();
		}
		return null;
	}

	/**
	 * Gets the store instance used by this server.
	 *
	 * @return Store instance
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Gets the configuration for this server.
	 *
	 * @return NodeConfig instance
	 */
	public NodeConfig getConfig() {
		return config;
	}

	/**
	 * Gets the lattice instance used by this server.
	 *
	 * @return Lattice instance
	 */
	public ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Checks if the server is currently running.
	 *
	 * @return true if running, false otherwise
	 */
	public boolean isRunning() {
		return lifecycleState == LifecycleState.RUNNING;
	}

	/** Package-visible lifecycle snapshot for deterministic lifecycle tests. */
	LifecycleState getLifecycleState() {
		return lifecycleState;
	}

	/**
	 * Gets the set of connected peer Convex instances from the primary propagator.
	 *
	 * @return Set of peer Convex connections (defensive copy)
	 * @deprecated Use {@code getPropagator().getPeers()} directly
	 */
	@Deprecated
	public Set<Convex> getPeerNodes() {
		if (propagators.isEmpty()) return java.util.Collections.emptySet();
		return propagators.get(0).getPeers();
	}

	/**
	 * Gets the connection manager from the primary propagator.
	 *
	 * @return LatticeConnectionManager instance, or null if no propagators
	 * @deprecated Access via {@code getPropagator().getConnectionManager()} directly
	 */
	@Deprecated
	public LatticeConnectionManager getConnectionManager() {
		return propagators.isEmpty() ? null : propagators.get(0).getConnectionManager();
	}

	/**
	 * Gets the primary propagator (index 0).
	 *
	 * @return Primary LatticePropagator instance, or null if none configured
	 */
	public LatticePropagator getPropagator() {
		return propagators.isEmpty() ? null : propagators.get(0);
	}

	/**
	 * Gets all propagators managed by this server.
	 *
	 * @return List of propagators (index 0 is primary if present)
	 */
	public List<LatticePropagator> getPropagators() {
		return List.copyOf(propagators);
	}

	/**
	 * Adds a propagator to this server. The first added propagator becomes the
	 * primary (index 0) — NodeServer will use its returned snapshot value for
	 * synchronous root sync and its store for explicit pull acquisition.
	 *
	 * @param propagator The propagator to add
	 */
	public synchronized void addPropagator(LatticePropagator propagator) {
		if (propagator == null) throw new IllegalArgumentException("Propagator must not be null");
		requireNewLifecycle("addPropagator");
		if (propagators.isEmpty() && propagator.getStore()!=store) {
			throw new IllegalArgumentException(
				"Primary propagator must use the NodeServer host store");
		}
		propagators.add(propagator);
	}

	/**
	 * Sets complete-value inbound admission/projection policy before launch.
	 *
	 * <p>The filter runs only after a full value has been decoded or acquired and
	 * before the authoritative merge. Returning null rejects the value. This is an
	 * interest/admission projection, not signature verification; the selected
	 * path's lattice still validates the returned value.</p>
	 */
	public synchronized void setIngressFilter(LatticeIngressFilter filter) {
		requireNewLifecycle("setIngressFilter");
		if (filter==null) throw new IllegalArgumentException("Ingress filter must not be null");
		this.ingressFilter=filter;
	}

	/**
	 * Sets the projection applied by the primary publication pipeline before its
	 * store, root pointer and outbound deltas are updated. Secondary propagators
	 * retain their own independent filters.
	 */
	public synchronized void setPublicationFilter(LatticeFilter<V> filter) {
		requireNewLifecycle("setPublicationFilter");
		if (filter==null) throw new IllegalArgumentException("Publication filter must not be null");
		this.publicationFilter=filter;
	}

	/**
	 * Sets the handler for complete application-defined messages whose core
	 * {@link MessageType} is {@link MessageType#UNKNOWN}. The predicate executes on
	 * the bounded ordered dispatcher and therefore must not block. Returning false
	 * records a rejected message against the originating connection.
	 *
	 * <p>Complete UNKNOWN messages may arrive on a zero-trust inbound connection.
	 * The handler is responsible for its application signature, audience and replay
	 * rules; transport arrival alone conveys no authority.</p>
	 *
	 * @param handler application message validator/handler, or null to reject all
	 */
	public synchronized void setApplicationMessageHandler(Predicate<Message> handler) {
		requireNewLifecycle("setApplicationMessageHandler");
		this.applicationMessageHandler=handler;
	}

	/**
	 * Sets an application observer for accepted inbound lattice values.
	 *
	 * <p>The observer is intentionally generic: NodeServer supplies the accepted
	 * path and value but knows nothing about their application meaning. It executes
	 * on the ordered dispatcher after merge and publication, so it must not block.
	 * An exception is logged and contained because the merge has already completed.</p>
	 *
	 * @param listener application observer, or null for none
	 */
	public synchronized void setInboundLatticeListener(InboundLatticeListener listener) {
		requireNewLifecycle("setInboundLatticeListener");
		this.inboundLatticeListener=listener;
	}

	/**
	 * Starts non-blocking possession verification for a physically inbound route.
	 *
	 * <p>This method is a transport primitive, not identity discovery. The caller
	 * must derive {@code expectedKey} from application data that its lattice has
	 * already accepted and must first admit that key to the selected propagator's
	 * bounded desired-peer set. A successful challenge marks the connection trusted
	 * and explicitly upgrades it into an outbound route for that propagator.</p>
	 *
	 * @param connection physically inbound connection to authenticate
	 * @param propagator immutable view already assigned to the connection
	 * @param expectedKey remote node key expected to answer the challenge
	 * @throws IllegalArgumentException for null arguments
	 * @throws IllegalStateException if the connection is not assigned to the supplied view
	 */
	public void authenticateInboundRoute(AConnection connection,
			LatticePropagator propagator,AccountKey expectedKey) {
		if (connection==null || propagator==null || expectedKey==null) {
			throw new IllegalArgumentException(
				"Connection, propagator and expected key must not be null");
		}
		if (outboundPropagators.containsKey(connection)) return;
		if (resolveInboundPropagator(connection)!=propagator) {
			throw new IllegalStateException(
				"Inbound connection is not assigned to the supplied propagator");
		}
		inboundVerifier.maybeStart(connection,propagator,expectedKey);
	}

	/**
	 * Publishes one authoritative application snapshot through the primary
	 * propagator, then asynchronously fans the same source snapshot to secondary
	 * filtered views.
	 */
	private V publishApplicationRoot(V value) {
		if (propagators.isEmpty()) {
			throw new IllegalStateException("NodeServer has no primary publication pipeline");
		}
		ACell announced;
		try {
			announced=propagators.get(0).processSnapshot(value);
		} catch (IOException e) {
			throw new StoreException("NodeServer sync failed: persistence error",e);
		}
		for (int i=1; i<propagators.size(); i++) {
			propagators.get(i).triggerBroadcast(value);
		}
		@SuppressWarnings("unchecked")
		V typed=(V)announced;
		return typed;
	}

	/**
	 * Sets the operator policy that assigns an inbound network connection to its
	 * single owning propagator.
	 *
	 * <p>The selected propagator determines both the lattice view exposed by
	 * LATTICE_QUERY and the store used to acquire a LATTICE_VALUE before merge.
	 * Returning null denies lattice protocol access on that connection. A non-null
	 * decision is permanent for the physical connection: later selector results
	 * cannot move it to another store.
	 *
	 * <p>No default policy is installed. For an intentionally public single-view
	 * node, an operator may explicitly use
	 * {@code node.setInboundPropagatorSelector(c -> node.getPropagator())}. Private
	 * deployments can select by verified connection identity or other external
	 * policy. If one Peer needs access to multiple views, it must use distinct
	 * physical connections.
	 *
	 * @param selector operator policy, or null to deny all inbound lattice traffic
	 * @throws IllegalStateException if called after the first launch begins
	 */
	public synchronized void setInboundPropagatorSelector(
			Function<AConnection, LatticePropagator> selector) {
		requireNewLifecycle("setInboundPropagatorSelector");
		this.inboundPropagatorSelector = selector;
	}

	/**
	 * Resolves and permanently binds a connection to one configured propagator.
	 * Null decisions are not cached, allowing an external policy to wait for
	 * authentication before granting a capability.
	 */
	private LatticePropagator resolveInboundPropagator(AConnection connection) {
		if (connection == null) {
			// Connection-less delivery is local/in-process and uses the authoritative
			// primary pipeline. Network capabilities always require explicit policy.
			return propagators.isEmpty() ? null : propagators.get(0);
		}

		// Reverse traffic on a manager-owned outbound client was admitted by that
		// manager's successful remote-key challenge, not by inbound operator policy.
		LatticePropagator outbound = outboundPropagators.get(connection);
		if (outbound != null) return outbound;

		LatticePropagator bound = inboundPropagators.get(connection);
		if (bound != null) return bound;

		Function<AConnection, LatticePropagator> selector = inboundPropagatorSelector;
		if (selector == null) return null;
		LatticePropagator selected = selector.apply(connection);
		if (selected == null) return null;
		if (!propagators.contains(selected)) {
			throw new IllegalStateException("Inbound policy selected a propagator not owned by this NodeServer");
		}

		bound = inboundPropagators.putIfAbsent(connection, selected);
		if (bound != null && bound != selected) {
			throw new IllegalStateException("Inbound connection is already bound to a different propagator");
		}
		return (bound != null) ? bound : selected;
	}

	/** Configuration and topology are immutable after the first launch begins. */
	private void requireNewLifecycle(String operation) {
		if (lifecycleState != LifecycleState.NEW) {
			throw new IllegalStateException(operation + " is configuration-only and must precede first launch");
		}
	}

	// ========== Explicit Persistence and Shutdown ==========

	/**
	 * Persists and durably flushes the given lattice value to the primary
	 * propagator's store. Delegates to the primary propagator's explicit persist
	 * method.
	 *
	 * @param value The lattice value to persist
	 * @throws IOException If an IO error occurs during persistence
	 */
	public void persistSnapshot(ACell value) throws IOException {
		if (!config.isPersist()) return;
		if (propagators.isEmpty()) return;
		propagators.get(0).persist(value);
	}

	/**
	 * Completes a physical durability barrier for the primary store if root
	 * publication has made it dirty since its last successful checkpoint.
	 * Call {@link ALatticeCursor#sync()} first when the current cursor value has
	 * not yet been published.
	 *
	 * @return true if a durability barrier was completed
	 * @throws IOException If the barrier fails
	 */
	public boolean checkpoint() throws IOException {
		if (!config.isPersist() || propagators.isEmpty()) return false;
		return propagators.get(0).checkpoint();
	}

	/** Removes this exact registered hook once shutdown ownership returns to the caller. */
	private void removeShutdownHook() {
		if (!shutdownHookRegistered) return;
		Shutdown.removeHook(Shutdown.SERVER, shutdownHook);
		shutdownHookRegistered = false;
	}

	/**
	 * Persists final state during JVM shutdown, before Etch closes its files.
	 * Called by the {@link Shutdown} hook at {@link Shutdown#SERVER} priority.
	 */
	private void shutdownPersist() {
		LifecycleState state = lifecycleState;
		if (state == LifecycleState.NEW || state == LifecycleState.STOPPED) return;
		try {
			close();
		} catch (IOException e) {
			log.warn("Error during shutdown persist", e);
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (lifecycleState == LifecycleState.NEW || lifecycleState == LifecycleState.STOPPED) {
			return;
		}
		if (lifecycleState == LifecycleState.STARTING) {
			throw new IllegalStateException("Cannot close NodeServer re-entrantly while launch is in progress");
		}

		log.trace("Closing NodeServer");

		acceptingInbound = false;
		lifecycleState = LifecycleState.STOPPING;

		// Stop admission first. Already accepted messages are then drained before the
		// final persistence snapshot is captured.
		if (networkServer != null) {
			networkServer.close();
		}
		inboundVerifier.close();
		// Stop maintenance immediately, even if draining the dispatcher later times out.
		// Connection state itself is retained until the dispatcher has stopped because
		// the current message may still update it.
		stopMaintenance();

		// Incomplete remote values have not been merged and are safe to cancel. Wait
		// for their Acquirors before any caller may close the propagator stores.
		stopAcquisitions();

		// On timeout this throws with lifecycleState=STOPPING and inboundThread retained. The
		// caller may retry close() after the blocking merge/store operation returns.
		stopInboundDispatcher();

		// #566: the dispatcher can no longer add per-connection state.
		connectionStats.clear();
		inboundPropagators.clear();
		outboundPropagators.clear();

		// Drain every propagator and release its network resources before performing
		// the final store-only durability work.
		V snapshot = cursor.get();
		for (LatticePropagator p : propagators) {
			p.triggerAndClose(snapshot);
			p.getConnectionManager().close();
		}

		IOException checkpointFailure = null;
		if (config.isPersist()) {
			for (LatticePropagator p : propagators) {
				try {
					// triggerAndClose has already filtered, reconciled and announced the
					// final view. Persist that capability-safe value, never the unfiltered
					// authoritative snapshot passed to a secondary propagator.
					p.persist(p.getLastAnnouncedValue());
				} catch (IOException e) {
					if (checkpointFailure == null) {
						checkpointFailure = e;
					} else {
						checkpointFailure.addSuppressed(e);
					}
				}
			}
		}

		lifecycleState = LifecycleState.STOPPED;
		removeShutdownHook();
		log.debug("NodeServer closed");
		if (checkpointFailure != null) throw checkpointFailure;
	}
}
