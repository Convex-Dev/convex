package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
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
	 * Store for this server. Used for inbound message decoding and data requests.
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
	 * @param store The store for inbound message decoding and data requests
	 * @param config Configuration (or null for defaults)
	 */
	public NodeServer(ALattice<V> lattice, AStore store, NodeConfig config) {
		this.lattice = lattice;
		this.store = store;
		this.config = (config != null) ? config : NodeConfig.create();
		this.inboundQueue = new ArrayBlockingQueue<>(this.config.getInboundQueueSize());
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
		if (!dispatcherStopped) return;

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
	 * Supports PING, LATTICE_QUERY, LATTICE_VALUE, and DATA_REQUEST message types.
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
		recordReceived(stats);

		try {
			// Decode message payload using node's store before processing
			message.getPayload(store);
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
			switch (type) {
			case PING:
				processPing(message);
				break;
			case LATTICE_QUERY:
				processLatticeQuery(message);
				break;
			case LATTICE_VALUE:
				processLatticeValue(message);
				break;
			case DATA_REQUEST:
				processDataRequest(message);
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
	 * live cursor, so that subsequent DATA_REQUESTs can resolve child cells from
	 * the same store. Never announces directly — that is the propagator's job.
	 *
	 * <p>Payload format: [:LQ id [*path*]]
	 *
	 * @param message The LATTICE_QUERY message
	 * @throws BadFormatException If message format is invalid
	 */
	private void processLatticeQuery(Message message) throws BadFormatException {
		AVector<?> payload = RT.ensureVector(message.getPayload());
		if (payload == null || payload.count() < 2) {
			log.warn("Invalid LATTICE_QUERY message format");
			Result error = Result.create(message.getRequestID(), Strings.create("Invalid LATTICE_QUERY format"), ErrorCodes.ARGUMENT);
			message.returnResult(error);
			return;
		}

		ACell id = payload.get(1);
		AVector<?> pathVector = RT.ensureVector(payload.count() > 2 ? payload.get(2) : null);

		// Read from the propagator's announced cursor — its cells are already
		// in the propagator's store, so DATA_REQUEST can resolve any child
		// cells the requester needs. Each propagator owns its announced cursor;
		// this is the security boundary for cross-propagator data segregation.
		ACell valueAtPath;
		if (propagators.isEmpty()) {
			valueAtPath = null;
		} else {
			Root<ACell> announced = propagators.get(0).getAnnouncedCursor();
			valueAtPath = (pathVector != null && pathVector.count() > 0)
				? announced.get(pathVector.toCellArray())
				: announced.get();
		}

		Result result = Result.create(id, valueAtPath);
		message.returnResult(result);
		log.debug("Responded to LATTICE_QUERY at path with length: {}",
			(pathVector != null) ? pathVector.count() : 0);
	}

	/**
	 * Processes a DATA_REQUEST message by responding with available data from the store.
	 * Missing data is signaled by null values in the response, which encode to NULL_ENCODING.
	 *
	 * This method is compatible with convex.peer.Server's handling of missing data requests.
	 *
	 * Payload format: [:DR id hash1 hash2 ...]
	 *
	 * @param message The DATA_REQUEST message
	 * @throws BadFormatException If message format is invalid
	 */
	private void processDataRequest(Message message) throws BadFormatException {
		try {
			// Use the same pattern as QueryHandler.handleDataRequest
			// This creates a response with available data from the store,
			// and null values for missing data (which encode to NULL_ENCODING)
			Message response = message.makeDataResponse(store);
			boolean sent = message.returnMessage(response);
			if (!sent) {
				log.info("Can't send data request response due to full buffer");
			} else {
				log.debug("Missing data request handled");
			}
		} catch (BadFormatException e) {
			log.warn("Unable to deliver missing data due badly formatted DATA_REQUEST: {}", message);
		} catch (Exception e) {
			log.warn("Unable to deliver missing data due to exception:", e);
		}
	}

	private void processChallenge(Message message) {
		message.respondToChallenge(mergeContext.getSigningKey(), null);
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
		} catch (Throwable e) {
			// This is the robustness firewall between untrusted input and the dispatcher thread:
			// NO merge may propagate anything to the caller. We catch Throwable (not just
			// Exception) deliberately — a maliciously deep value can make a recursive merge
			// throw StackOverflowError, which is an Error and would otherwise escape and kill
			// the dispatcher thread. The cursor's updateAndGet never commits when the merge lambda
			// throws, so the atomic abort holds for Errors too and the prior value is retained.
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
	}

	/**
	 * Prunes closed connections from the stats map (#566). A backstop to the eager disconnect
	 * hook: called opportunistically from {@link #statsFor} when the map grows past
	 * {@link #MAX_TRACKED_CONNECTIONS} (bounding memory during connection churn) and
	 * periodically from {@link #maintenanceLoop} — so entries still drain if a disconnect
	 * event is missed or a transport does not surface one.
	 */
	void sweepClosedConnections() {
		connectionStats.keySet().removeIf(AConnection::isClosed);
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
