package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Acquiror;
import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.StoreException;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.Shutdown;
import convex.core.util.Utils;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.AccountKey;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.lattice.ALattice;
import convex.lattice.P2PLattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.RootLatticeCursor;
import convex.net.AServer;
import convex.net.impl.netty.NettyServer;
import convex.peer.Config;

/**
 * A networked node server for Lattice networks.
 *
 * This server handles binary protocol communication for syncing lattice values
 * with other nodes in the network. It provides a lightweight alternative to
 * the full Peer Server, focused specifically on lattice value synchronization.
 *
 * The server uses the binary protocol (VLQ-encoded message lengths followed by
 * message data) to exchange and merge lattice values with peer nodes.
 *
 * <p><b>Inbound capability boundary.</b> Network lattice traffic is denied until
 * operator policy assigns its physical connection to exactly one propagator. That
 * propagator supplies both the query view and the acquisition store. Partial values
 * are acquired completely in that store before they enter the ordered merge path;
 * NodeServer never searches another propagator or falls back to the primary store.
 *
 * Features:
 * - Automatic delta-based broadcasting of lattice updates to peers
 * - Efficient novelty detection using store announcement mechanism
 * - Manual sync capabilities for on-demand synchronization
 * - Support for hierarchical lattice paths
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
	private final ArrayBlockingQueue<Message> inboundQueue;

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

	/** Immutable connection-to-propagator capabilities established by operator policy. */
	private final ConcurrentHashMap<AConnection, LatticePropagator> inboundPropagators =
		new ConcurrentHashMap<>();

	/** Pending reverse data requests, isolated by physical connection and request ID. */
	private final ConcurrentHashMap<AConnection, ConcurrentHashMap<ACell, CompletableFuture<Result>>>
		pendingDataRequests = new ConcurrentHashMap<>();

	/** Active Acquirors retained until their owned workers have terminated. */
	private final ConcurrentHashMap<AConnection, Set<Acquiror>> activeAcquirors =
		new ConcurrentHashMap<>();
	private final Object acquisitionLifecycleLock = new Object();
	private boolean acceptingAcquisitions;

	/** Negative request IDs reserved for NodeServer acquisition sessions. */
	private final AtomicLong nextDataRequestID = new AtomicLong(-1L);

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
		this.inboundQueue = new ArrayBlockingQueue<>(this.config.getInboundQueueSize());
		this.acquisitionPermits = new Semaphore(this.config.getInboundQueueSize());
		this.port = this.config.getPort();
		this.cursor = Cursors.createLattice(lattice);

		// Hook sync callback: synchronous commit on the primary propagator,
		// async fan-out to secondaries.
		//
		// The primary's full pipeline (announce + setRootData + broadcast) runs
		// on the caller's thread, so cursor.sync() is a synchronous checkpoint:
		// returning successfully means the primary store accepted the root update.
		// Physical flushing is a separate store/operator policy.
		// IOException from announce or setRootData is wrapped and propagated to
		// the caller — sync failures are visible, not silently dropped.
		//
		// Secondary propagators use the existing async path; their broadcast
		// latency is independent of caller durability.
		//
		// The returned (announced/store-backed) value is CASed back into the
		// cursor by RootLatticeCursor.sync(), with lattice-merge fallback if a
		// concurrent app write changed the cursor during the announce.
		this.cursor.onSync(value -> {
			if (propagators.isEmpty()) return value;
			ACell announced;
			try {
				announced = propagators.get(0).processSnapshot(value);
			} catch (IOException e) {
				throw new StoreException("NodeServer sync failed: persistence error", e);
			}
			for (int i = 1; i < propagators.size(); i++) {
				propagators.get(i).triggerBroadcast(value);
			}
			@SuppressWarnings("unchecked")
			V typed = (V) announced;
			return typed;
		});

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
	@SuppressWarnings("unchecked")
	public synchronized void launch() throws IOException, InterruptedException {
		if (lifecycleState == LifecycleState.RUNNING || lifecycleState == LifecycleState.STARTING) {
			throw new IllegalStateException("NodeServer is already running");
		}
		if (lifecycleState == LifecycleState.STOPPING) {
			throw new IllegalStateException("NodeServer shutdown is incomplete; call close() again before launch");
		}

		// Validate close-time policy before opening any service. The config is
		// immutable, so discovering an unusable timeout only during close() would
		// leave the instance unable to complete its own shutdown.
		config.getInboundShutdownTimeout();

		// #567: validate a configured public URL before advertising it. Fail fast on a
		// misconfigured (private/loopback/malformed) URL rather than silently polluting the
		// [:p2p :nodes] registry with an unreachable address that peers waste reconnects on.
		AString urlCfg = config.getURL();
		if (urlCfg != null) {
			String reason = NodeConfig.validatePublicURL(urlCfg.toString(), config.isAllowPrivateURL());
			if (reason != null) {
				throw new IllegalStateException("Invalid node URL configuration: " + reason);
			}
		}

		lifecycleState = LifecycleState.STARTING;
		try {
			log.debug("Launching NodeServer on port {}", port);

			// Create primary propagator if none have been added
			if (propagators.isEmpty() && store != null) {
				LatticeConnectionManager connectionManager = new LatticeConnectionManager(store);
				AKeyPair signingKey = mergeContext.getSigningKey();
				if (signingKey != null) {
					connectionManager.setKeyPair(signingKey);
				}
				LatticePropagator primary = new LatticePropagator(store, connectionManager);
				if (!config.isPersist()) {
					primary.setPersistInterval(-1); // disable setRootData
				}
				propagators.add(primary);
			}

			// Outbound sockets begin at the public/untrusted cap. Their connection manager
			// promotes an individual socket to the trusted cap only after challenge/response
			// proves the expected remote AccountKey.
			for (LatticePropagator p : propagators) {
				p.getConnectionManager().setInboundMessageLimits(
					config.getMaxMessageSize(), config.getMaxTrustedMessageSize());
			}

			// Restore from primary propagator's store if configured
			if (config.isRestore() && !propagators.isEmpty()) {
				ACell restored = propagators.get(0).restore();
				if (restored != null) {
					cursor.set((V) restored);
					log.info("Restored lattice value from store");
				}
			}

			// Seed the primary's announced, store-backed view before opening the network.
			// A fresh or restored node can answer LATTICE_QUERY immediately without an
			// application-side sync solely to initialise query service.
			if (!propagators.isEmpty()) {
				ACell announced = propagators.get(0).processSnapshot(cursor.get());
				cursor.set((V) announced);
			}

			// Create and launch network server unless port is negative (local-only mode)
			boolean localOnly = (port != null && port < 0);
			if (!localOnly) {
				if (networkServer == null) {
					NettyServer nettyServer = new NettyServer(port);
					// Set the receive action for handling incoming messages
					nettyServer.setReceiveAction(receiveAction);
					// Use Netty's per-channel backpressure contract: event-loop threads only
					// enqueue; decode, merge, persistence and responses run on our dispatcher.
					nettyServer.setMessageDelivery(this::deliverIncomingMessage);
					nettyServer.setMaxClientConnections(config.getMaxConnections());
					nettyServer.setMaxMessageLength(config.getMaxMessageSize());
					networkServer = nettyServer;
					// #566: release per-connection stats eagerly when a connection closes. The
					// periodic sweep remains as a backstop for transports without a close signal.
					networkServer.setDisconnectAction(this::removeConnection);
				}

				if (port != null) {
					networkServer.setPort(port);
				}
				startInboundDispatcher();
				networkServer.launch();
				port = networkServer.getPort();
			}

			// #566: periodic sweep of closed connections from the inbound stats map, so an idle
			// node drains dead entries without relying on inbound traffic or a network close hook.
			maintenanceThread = Thread.ofVirtual()
					.name("NodeServer connection-stats maintenance")
					.start(this::maintenanceLoop);

			// Register shutdown hook to persist before Etch closes its files
			Shutdown.addHook(Shutdown.SERVER, this::shutdownPersist);

			// Start all propagator threads and connection managers
			for (LatticePropagator p : propagators) {
				p.getConnectionManager().start();
				p.start();
			}

			// Publication is part of the launch contract. If its synchronous checkpoint
			// fails, launch must fail with every service stopped rather than returning an
			// exception while a listener and background threads remain live.
			publishNodeInfo();

			lifecycleState = LifecycleState.RUNNING;
			log.debug("NodeServer started successfully on port {}", port);
		} catch (IOException | InterruptedException | RuntimeException | Error e) {
			abortFailedLaunch(e);
			throw e;
		}
	}

	/**
	 * Stops every service that may have started after the listener opened, attaching
	 * cleanup failures to the original launch failure. This deliberately differs from
	 * {@link #close()}: it does not submit a final persistence snapshot. Retrying the
	 * checkpoint while unwinding the failure would obscure whether publication succeeded
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

		Thread maintenance = maintenanceThread;
		maintenanceThread = null;
		if (maintenance != null) maintenance.interrupt();

		// A timed-out dispatcher may still be inside cursor.sync() or store decoding.
		// Keep the propagators and store-facing services available until a later
		// close() confirms that the sole ordered consumer has actually terminated.
		if (!acquisitionsStopped || !dispatcherStopped) return;

		connectionStats.clear();

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
	}

	private static void addCleanupFailure(Throwable failure, Throwable cleanupError) {
		if (cleanupError != failure) failure.addSuppressed(cleanupError);
	}

	/**
	 * Publishes this node's info into the {@code :p2p :nodes} lattice if the node
	 * is publicly accessible (URL configured) and has a signing key.
	 *
	 * <p>Only advertises when both conditions are met:
	 * <ul>
	 *   <li>A public URL is configured (never localhost or private addresses)</li>
	 *   <li>A signing key is available in the merge context</li>
	 * </ul>
	 */
	private void publishNodeInfo() {
		// Only advertise if we have a public URL
		AString url = config.getURL();
		if (url == null) return;

		// Only advertise if we have a signing key
		AKeyPair keyPair = mergeContext.getSigningKey();
		if (keyPair == null) return;

		AString type = Strings.create("Convex Lattice Node");
		String versionStr = Utils.getVersion();
		AString version = Strings.create(versionStr != null ? versionStr : "unknown");

		// #561: stamp the published NodeInfo from the merge context (driver-supplied time),
		// not from a system-clock read inside the lattice builder.
		AHashMap<Keyword, ACell> nodeInfo = P2PLattice.createNodeInfo(
			Vectors.of(url), type, version, null, mergeContext.currentTimestampValue());

		AHashMap<ACell, SignedData<ACell>> entry = P2PLattice.createSignedEntry(keyPair, nodeInfo);

		// Navigate to :p2p :nodes and merge the signed entry
		cursor.path(Keywords.P2P, Keywords.NODES).merge(entry);
		// Publication is part of launch: make it durable and queryable immediately.
		cursor.sync();

		log.info("Published NodeInfo: url={}, type={}, version={}", url, type, version);
	}

	/**
	 * Updates desired peers on all propagator connection managers from the
	 * current {@code [:p2p :nodes]} lattice value. Called when an incoming
	 * LATTICE_VALUE changes P2P data.
	 */
	@SuppressWarnings("unchecked")
	private void maybeUpdateDesiredPeers() {
		try {
			ACell nodesValue = cursor.get(Keywords.P2P, Keywords.NODES);
			if (nodesValue == null) return;

			AKeyPair kp = mergeContext.getSigningKey();
			AccountKey ownKey = (kp != null) ? kp.getAccountKey() : null;

			AHashMap<ACell, SignedData<ACell>> nodesMap =
				(AHashMap<ACell, SignedData<ACell>>) nodesValue;

			for (LatticePropagator p : propagators) {
				p.getConnectionManager().updateDesiredPeers(nodesMap, ownKey);
			}
		} catch (Exception e) {
			log.debug("Error updating desired peers from P2P lattice: {}", e.getMessage());
		}
	}

	/**
	 * Handles an incoming message from a peer node.
	 * Supports PING, LATTICE_QUERY, LATTICE_VALUE, CHALLENGE and explicit rejection
	 * of unscoped DATA_REQUEST messages.
	 * Processing exceptions are contained at this message boundary. A request with an
	 * ID receives an error result where possible; fire-and-forget message failures are
	 * logged. In particular, a durability failure after an inbound lattice merge does
	 * not impose a shutdown or retry policy on the node.
	 *
	 * @param message The incoming message
	 */
	void handleIncomingMessage(Message message) {
		log.debug("Received message from peer: {}", message);

		AConnection conn = message.getConnection();
		ConnectionStats stats = statsFor(conn);
		Throwable acquisitionFailure = acquisitionFailures.remove(message);
		LatticePropagator acquiredOwner = acquiredMessages.remove(message);
		boolean acquired = acquiredOwner != null;
		if (!acquired && acquisitionFailure == null) recordReceived(stats);
		if (acquisitionFailure != null) {
			if (acquisitionFailure instanceof VirtualMachineError fatal
					&& !(fatal instanceof StackOverflowError)) {
				throw fatal;
			}
			recordMergeReject(conn, stats);
			log.warn("Rejected lattice value after acquisition failure: {}",
				acquisitionFailure.getMessage());
			return;
		}

		LatticePropagator owner;
		try {
			owner = acquired ? acquiredOwner : resolveInboundPropagator(conn);
		} catch (Exception e) {
			recordDecodeError(conn, stats);
			log.warn("Rejected inbound connection ownership: {}", e.getMessage());
			return;
		}

		try {
			// An assigned connection decodes only into its propagator store. An
			// unassigned network connection uses storeless decoding so even a rejected
			// message cannot deposit attacker-controlled cells in the primary store.
			AStore decodeStore = (owner != null) ? owner.getStore() : ((conn == null) ? store : null);
			message.getPayload(decodeStore);
		} catch (MissingDataException e) {
			if (!acquired && owner != null && conn != null) {
				beginLatticeAcquisition(message, owner, stats);
				return;
			}
			recordDecodeError(conn, stats);
			log.warn("Rejected incomplete inbound message: {}", e.getMessage());
			return;
		} catch (Exception e) {
			// #566: an undecodable message counts against the connection and can trip the breaker.
			recordDecodeError(conn, stats);
			log.warn("Failed to decode incoming message: {}", e.getMessage());
			try {
				ACell id = message.getRequestID(); // safe: returns null if undecoded
				message.returnMessage(Message.createResult(Result.fromException(e).withID(id)));
			} catch (Exception e2) {
				// best effort -- connection may be bad
			}
			return;
		}

		try {
			MessageType type = message.getType();
			if (type == MessageType.RESULT && completeDataRequest(message, conn)) return;
			switch (type) {
			case PING:
				processPing(message);
				break;
			case LATTICE_QUERY:
				if (owner == null) {
					rejectUnscopedLatticeRequest(message);
				} else {
					processLatticeQuery(message, owner);
				}
				break;
			case LATTICE_VALUE:
				if (owner == null) {
					recordMergeReject(conn, stats);
					log.warn("Rejected LATTICE_VALUE on an unassigned connection");
				} else if (acquired) {
					processLatticeValue(message);
				} else {
					prepareLatticeValue(message, owner, stats);
				}
				break;
			case DATA_REQUEST:
				if (owner == null) {
					rejectUnscopedDataRequest(message);
				} else {
					processDataRequest(message, owner);
				}
				break;
			case CHALLENGE:
				processChallenge(message);
				break;
			default:
				log.debug("Unhandled message type: {}", type);
				break;
			}
		} catch (Exception e) {
			log.warn("Error handling message: {}", e.getMessage());
			try {
				ACell id = message.getRequestID();
				if (id != null) {
					message.returnResult(Result.fromException(e));
				}
			} catch (Exception e2) {
				// best effort
			}
		}
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

	private boolean offerInboundBlocking(Message message) {
		if (!acceptingInbound) return false;
		try {
			boolean offered = inboundQueue.offer(message, Config.DEFAULT_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
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
		if (payload == null || payload.count() < 2) {
			log.warn("Invalid LATTICE_QUERY message format");
			Result error = Result.create(message.getRequestID(), Strings.create("Invalid LATTICE_QUERY format"), ErrorCodes.ARGUMENT);
			message.returnResult(error);
			return;
		}

		ACell id = payload.get(1);
		AVector<?> pathVector = RT.ensureVector(payload.count() > 2 ? payload.get(2) : null);

		// Query and later DATA_REQUEST resolution use the same capability-bound
		// propagator view. Falling back to the primary would cross a store boundary.
		Root<ACell> announced = owner.getAnnouncedCursor();
		ACell valueAtPath = (pathVector != null && pathVector.count() > 0)
			? announced.get(pathVector.toCellArray())
			: announced.get();

		Result result = Result.create(id, valueAtPath);
		message.returnResult(result);
		log.debug("Responded to LATTICE_QUERY at path with length: {}",
			(pathVector != null) ? pathVector.count() : 0);
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

	private void processChallenge(Message message) {
		message.respondToChallenge(mergeContext.getSigningKey(), null);
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

	/**
	 * Persists a complete inbound value in its owning propagator store before
	 * allowing the ordered dispatcher to call lattice merge code.
	 */
	private void prepareLatticeValue(Message message, LatticePropagator owner,
			ConnectionStats stats) throws BadFormatException {
		try {
			Message complete = completeLatticeMessage(message, owner.getStore());
			processLatticeValue(complete);
		} catch (MissingDataException e) {
			beginLatticeAcquisition(message, owner, stats);
		} catch (IOException e) {
			recordMergeReject(message.getConnection(), stats);
			log.warn("Unable to persist inbound lattice value in its propagator store", e);
		}
	}

	/**
	 * Returns a message whose lattice value is known complete and persisted in the
	 * supplied propagator store. This is the sole gateway into processLatticeValue.
	 */
	private Message completeLatticeMessage(Message message, AStore acquisitionStore)
			throws BadFormatException, IOException {
		AVector<?> payload = RT.ensureVector(message.getPayload());
		if (payload == null || payload.count() < 3) {
			throw new BadFormatException("Invalid LATTICE_VALUE message format");
		}
		ACell value = payload.get(2);
		if (value == null) throw new BadFormatException("LATTICE_VALUE message missing value");

		// Persist into the quarantine/ingress store, then independently prove that
		// no missing descendant remains. Some stores deliberately retain a partial
		// top Ref at STORED status instead of throwing during storeTopRef.
		ACell complete = Cells.persist(value, acquisitionStore);
		HashSet<Hash> missing = new HashSet<>();
		Ref.get(complete).findMissing(missing, 1);
		if (!missing.isEmpty()) {
			throw new MissingDataException(acquisitionStore, missing.iterator().next());
		}
		if (!withinInboundSizeLimit(complete)) {
			throw new BadFormatException("Acquired lattice value exceeds inbound size limit");
		}

		AVector<?> completePayload = Vectors.create(payload.get(0), payload.get(1), complete);
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
			AVector<?> payload = RT.ensureVector(message.getPayload());
			if (payload == null || payload.count() < 3 || payload.get(2) == null) {
				throw new BadFormatException("Invalid LATTICE_VALUE message format");
			}

			try {
				return CompletableFuture.completedFuture(
					completeLatticeMessage(message, acquisitionStore));
			} catch (MissingDataException e) {
				Hash rootHash = payload.get(2).getHash();
				return acquireHash(connection, acquisitionStore, rootHash)
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

		CVMLong id = CVMLong.create(nextDataRequestID.getAndDecrement());
		CompletableFuture<Result> future = new CompletableFuture<>();
		ConcurrentHashMap<ACell, CompletableFuture<Result>> byID =
			pendingDataRequests.computeIfAbsent(connection, c -> new ConcurrentHashMap<>());
		byID.put(id, future);

		future.orTimeout(Config.DEFAULT_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
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
	 * delivery is first handed to a bounded dispatcher, so this synchronous durability
	 * work never blocks a shared Netty event-loop thread.
	 *
	 * <p>Payload format: [:LV [*path*] value]
	 *
	 * @param message The LATTICE_VALUE message
	 * @throws BadFormatException If message format is invalid
	 */
	private void processLatticeValue(Message message) throws BadFormatException {
		AConnection conn = message.getConnection();
		ConnectionStats stats = statsFor(conn);

		AVector<?> payload = RT.ensureVector(message.getPayload());
		if (payload == null || payload.count() < 2) {
			log.warn("Invalid LATTICE_VALUE message format");
			recordMergeReject(conn, stats);
			return;
		}

		ACell pathCell = payload.get(1);
		ACell value = payload.count() > 2 ? payload.get(2) : null;

		if (value == null) {
			log.warn("LATTICE_VALUE message missing value");
			recordMergeReject(conn, stats);
			return;
		}

		// #564: bound merge cost from untrusted peers — reject an oversized value before
		// the synchronous dispatcher merge runs.
		if (!withinInboundSizeLimit(value)) {
			recordMergeReject(conn, stats);
			return;
		}

		// Navigate to target path and merge
		ACell[] path = extractPath(pathCell);
		ALatticeCursor<ACell> target = cursor.path(path);

		ACell before = target.get();

		// A rejected merge leaves the cursor unchanged (atomic abort), so there is
		// nothing to sync or propagate.
		if (!mergeIncoming(target, value)) {
			recordMergeReject(conn, stats);
			return;
		}

		// #566: a successful merge resets the connection's consecutive-reject streak.
		recordAccept(stats);

		// A valid replay is accepted but must not trigger another announce/root write.
		// Lattice merges conventionally preserve identity on no-op; equals is the
		// defensive fallback for implementations that return an equivalent value.
		ACell after = target.get();
		if (before == after || (before != null && before.equals(after))) return;

		// Notify propagators that cursor state has changed. This is a synchronous
		// primary-store checkpoint on the dispatcher thread, never on a Netty event loop.
		cursor.sync();

		// If P2P node data changed, update desired peers on connection managers
		if (path.length > 0 && Keywords.P2P.equals(path[0])) {
			maybeUpdateDesiredPeers();
		}
	}

	/**
	 * Extracts path array from message path cell.
	 *
	 * @param pathCell Path cell from message (may be null, vector, or single key)
	 * @return Array of path keys (empty array for root)
	 */
	private ACell[] extractPath(ACell pathCell) {
		if (pathCell == null) {
			return new ACell[0]; // Empty path = root
		}

		AVector<?> pathVector = RT.ensureVector(pathCell);
		if (pathVector != null) {
			// Vector path
			long pathLen = pathVector.count();
			ACell[] path = new ACell[(int)pathLen];
			for (long i = 0; i < pathLen; i++) {
				path[(int)i] = pathVector.get(i);
			}
			return path;
		} else {
			// Single key path
			return new ACell[] { pathCell };
		}
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
	 * sink for the inbound (untrusted) connection lifecycle, invoked from three places:
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
		connectionStats.remove(conn);
		inboundPropagators.remove(conn);
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
		candidates.addAll(pendingDataRequests.keySet());
		candidates.addAll(activeAcquirors.keySet());
		for (AConnection connection : candidates) {
			if (connection.isClosed()) removeConnection(connection);
		}
	}

	/**
	 * Periodic maintenance loop (#566): a backstop that sweeps closed connections from the
	 * stats map even if the eager disconnect hook is missed or unavailable, so entries drain
	 * within one sweep interval. Exits promptly on interrupt when the server closes.
	 */
	private void maintenanceLoop() {
		while (lifecycleState == LifecycleState.STARTING
				|| lifecycleState == LifecycleState.RUNNING) {
			try {
				Thread.sleep(CONNECTION_SWEEP_INTERVAL);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			sweepClosedConnections();
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

	/**
	 * Pulls the latest lattice value from a specific peer and merges it locally.
	 *
	 * <p>The primary propagator only acquires the full value tree into its store.
	 * NodeServer then merges through the authoritative root cursor and synchronously
	 * checkpoints that merged root before it can be re-propagated.
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
			// may not yet have been checkpointed, and the raw peer value must never become
			// the persisted or announced root independently of the merged cursor.
			cursor.sync();
			return cursor.get();
		});
	}

	/**
	 * Pulls the latest lattice value from all connected peers and merges locally.
	 *
	 * <p>The primary propagator acquires full value trees from every peer in
	 * parallel. NodeServer merges all successful results through its root cursor,
	 * then performs one synchronous checkpoint of the combined result.
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
	 * propagating it. The caller checkpoints only after the root merge has completed.
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
	 * runs the primary persistence pipeline synchronously. A successful return confirms
	 * that the primary store accepted the logical root update; physical flushing is a
	 * separate store/operator policy. A primary-store failure throws {@link StoreException}
	 * without rolling back the in-memory cursor and does not confirm whether the store
	 * completed its root update. NodeServer remains running so recovery remains operator
	 * policy.
	 *
	 * @return The value cursor
	 */
	public ALatticeCursor<V> getCursor() {
		return cursor;
	}

	/**
	 * Sets the merge context used for all lattice merge operations.
	 * The context carries signing keys and owner verification through the
	 * lattice hierarchy (e.g. OwnerLattice, SignedLattice).
	 *
	 * <p><b>Configuration-only (#568).</b> This must be called before {@link #launch()}.
	 * The context is then read by pull operations and the inbound dispatcher thread
	 * ({@code publishNodeInfo}, {@code maybeUpdateDesiredPeers}, root merges),
	 * and is safely published to them via the happens-before edge of thread start — so
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
		this.mergeContext = context;
		// Propagate to lattice cursor so path-navigated cursors inherit it
		cursor.setContext(context);
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
		propagators.add(propagator);
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

	/**
	 * Persists the given lattice value to the primary propagator's store.
	 * Delegates to the primary propagator's explicit persist method.
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
		// Stop maintenance immediately, even if draining the dispatcher later times out.
		// Connection state itself is retained until the dispatcher has stopped because
		// the current message may still update it.
		if (maintenanceThread != null) {
			maintenanceThread.interrupt();
			maintenanceThread = null;
		}

		// Incomplete remote values have not been merged and are safe to cancel. Wait
		// for their Acquirors before any caller may close the propagator stores.
		stopAcquisitions();

		// On timeout this throws with lifecycleState=STOPPING and inboundThread retained. The
		// caller may retry close() after the blocking merge/store operation returns.
		stopInboundDispatcher();

		// #566: the dispatcher can no longer add per-connection state.
		connectionStats.clear();

		// Final sync: trigger all propagators with current value and wait for drain.
		V snapshot = cursor.get();
		for (LatticePropagator p : propagators) {
			p.triggerAndClose(snapshot);
			p.getConnectionManager().close();
		}

		lifecycleState = LifecycleState.STOPPED;
		log.debug("NodeServer closed");
	}
}
