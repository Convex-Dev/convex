package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import convex.core.lang.RT;
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
import convex.lattice.cursor.RootLatticeCursor;
import convex.net.AServer;
import convex.net.impl.netty.NettyServer;

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
	 * (if present) — NodeServer sets a merge callback on it to feed store-backed
	 * refs into the cursor. Additional propagators handle public/backup broadcast.
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

	/**
	 * Whether the server is currently running
	 */
	private boolean running = false;

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
		this.port = this.config.getPort();
		this.cursor = Cursors.createLattice(lattice);

		// Hook sync callback: cursor.sync() triggers all propagators
		this.cursor.onSync(value -> {
			for (LatticePropagator p : propagators) {
				p.triggerBroadcast(value);
			}
			return value;
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
	public void launch() throws IOException, InterruptedException {
		if (running) {
			throw new IllegalStateException("NodeServer is already running");
		}

		log.debug("Launching NodeServer on port {}", port);

		// Create primary propagator if none have been added
		if (propagators.isEmpty() && store != null) {
			LatticeConnectionManager connectionManager = new LatticeConnectionManager(store);
			LatticePropagator primary = new LatticePropagator(store, connectionManager);
			if (!config.isPersist()) {
				primary.setPersistInterval(-1); // disable setRootData
			}
			propagators.add(primary);
		}

		// Wire merge callback on primary propagator: feeds store-backed refs
		// into the cursor via lattice merge, preventing OOM from strong refs
		if (!propagators.isEmpty()) {
			propagators.get(0).setMergeCallback(persisted -> {
				cursor.updateAndGet(current -> {
					@SuppressWarnings("unchecked")
					V merged = lattice.merge(mergeContext, current, (V) persisted);
					return merged;
				});
			});
		}

		// Restore from primary propagator's store if configured
		if (config.isRestore() && !propagators.isEmpty()) {
			ACell restored = propagators.get(0).restore();
			if (restored != null) {
				cursor.set((V) restored);
				log.info("Restored lattice value from store");
			}
		}

		// Create and launch network server unless port is negative (local-only mode)
		boolean localOnly = (port != null && port < 0);
		if (!localOnly) {
			if (networkServer == null) {
				networkServer = new NettyServer(port);
				// Set the receive action for handling incoming messages
				networkServer.setReceiveAction(receiveAction);
			}

			if (port != null) {
				networkServer.setPort(port);
			}
			networkServer.launch();
			port = networkServer.getPort();
		}

		running = true;

		// Register shutdown hook to persist before Etch closes its files
		Shutdown.addHook(Shutdown.SERVER, this::shutdownPersist);

		// Start all propagator threads and connection managers
		for (LatticePropagator p : propagators) {
			p.getConnectionManager().start();
			p.start();
		}

		// Publish node info if publicly accessible
		publishNodeInfo();

		log.debug("NodeServer started successfully on port {}", port);
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

		AHashMap<Keyword, ACell> nodeInfo = P2PLattice.createNodeInfo(
			Vectors.of(url), type, version, null);

		AHashMap<ACell, SignedData<ACell>> entry = P2PLattice.createSignedEntry(keyPair, nodeInfo);

		// Navigate to :p2p :nodes and merge the signed entry
		cursor.path(Keywords.P2P, Keywords.NODES).merge(entry);

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
	 *
	 * @param message The incoming message
	 */
	private void handleIncomingMessage(Message message) {
		log.debug("Received message from peer: {}", message);

		try {
			// Decode message payload using node's store before processing
			message.getPayload(store);
		} catch (Exception e) {
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
	 * Processes a PING message by responding with a RESULT containing the same ID.
	 *
	 * @param message The PING message
	 */
	private void processPing(Message message) {
		ACell id = message.getRequestID();
		if (id == null) {
			log.warn("PING message missing ID");
			return;
		}

		Result result = Result.create(id, Strings.create("PONG"));
		message.returnResult(result);
		log.debug("Responded to PING with ID: {}", id);
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

		// Use the last announced value — already persisted in the store, so
		// DATA_REQUEST can resolve any child cells the requester needs
		ACell announced = propagators.isEmpty() ? null : propagators.get(0).getLastAnnouncedValue();
		ACell valueAtPath;
		if (pathVector != null && pathVector.count() > 0) {
			valueAtPath = RT.getIn(announced, pathVector.toCellArray());
		} else {
			valueAtPath = announced;
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

	/**
	 * Processes an incoming LATTICE_VALUE message from a peer.
	 *
	 * <p>Navigates to the target path via {@code cursor.path()}, merges the
	 * received value, then calls {@code cursor.sync()} to notify propagators. The
	 * sync is cheap (non-blocking queue offer) and the {@code LatestUpdateQueue}
	 * coalesces rapid incoming merges, so high-velocity messages are safe.
	 *
	 * <p>Payload format: [:LV [*path*] value]
	 *
	 * @param message The LATTICE_VALUE message
	 * @throws BadFormatException If message format is invalid
	 */
	private void processLatticeValue(Message message) throws BadFormatException {
		AVector<?> payload = RT.ensureVector(message.getPayload());
		if (payload == null || payload.count() < 2) {
			log.warn("Invalid LATTICE_VALUE message format");
			return;
		}

		ACell pathCell = payload.get(1);
		ACell value = payload.count() > 2 ? payload.get(2) : null;

		if (value == null) {
			log.warn("LATTICE_VALUE message missing value");
			return;
		}

		// Navigate to target path and merge
		ACell[] path = extractPath(pathCell);
		ALatticeCursor<ACell> target = cursor.path(path);
		mergeIncoming(target, value);

		// Notify propagators that cursor state has changed. This is non-blocking:
		// cursor.sync() offers the current snapshot to each propagator's LatestUpdateQueue,
		// which coalesces rapid incoming merges into a single latest value. The
		// propagator decides when to actually broadcast based on MIN_BROADCAST_DELAY.
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
	 * Merges an incoming value into a lattice cursor.
	 *
	 * <p>Does not notify propagators — the caller is responsible for calling
	 * {@code cursor.sync()} after the merge if relay is needed. This keeps merge
	 * and propagation as separate concerns.
	 *
	 * @param <T> Type of cursor value
	 * @param target Lattice cursor at the merge target (from {@code cursor.path(...)})
	 * @param value Value to merge
	 */
	@SuppressWarnings("unchecked")
	private <T extends ACell> void mergeIncoming(ALatticeCursor<T> target, ACell value) {
		try {
			target.merge((T) value);
		} catch (Exception e) {
			log.warn("Error during lattice merge", e);
		}
	}

	/**
	 * Pulls the latest lattice value from a specific peer and merges it locally.
	 *
	 * <p>Delegates to the primary propagator which acquires the full value tree
	 * into its store, feeds it into the cursor via the merge callback, and queues
	 * it for broadcast to other peers.
	 *
	 * @param convex Convex connection to the peer node
	 * @return CompletableFuture that completes with the current cursor value after merge
	 */
	public CompletableFuture<V> pull(Convex convex) {
		if (propagators.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("No propagators configured"));
		}
		// Delegate to primary propagator; return cursor value after merge callback has run
		return propagators.get(0).pull(convex).thenApply(v -> cursor.get());
	}

	/**
	 * Pulls the latest lattice value from all connected peers and merges locally.
	 *
	 * <p>Delegates to the primary propagator which queries each peer, acquires
	 * full value trees, and merges via the merge callback.
	 *
	 * @return true if all pulls completed successfully, false otherwise
	 */
	public boolean pull() {
		if (propagators.isEmpty()) {
			log.debug("No propagators configured — cannot pull");
			return true;
		}

		try {
			propagators.get(0).pull().get(30, TimeUnit.SECONDS);
			// Sync cursor so the full merged state (not just individual pulled
			// values) gets announced — ensures LATTICE_QUERY returns current data
			cursor.sync();
			return true;
		} catch (Exception e) {
			log.warn("Pull failed: {}", e.getMessage());
			return false;
		}
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
	 * Gets the cursor for the lattice value.
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
	 * @param context Merge context (must not be null — use LatticeContext.EMPTY for default)
	 */
	public void setMergeContext(LatticeContext context) {
		if (context == null) throw new IllegalArgumentException("Use LatticeContext.EMPTY instead of null");
		this.mergeContext = context;
		// Propagate to lattice cursor so path-navigated cursors inherit it
		cursor.withContext(context);
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
		if (networkServer != null && running) {
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
		return running;
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
		return propagators;
	}

	/**
	 * Adds a propagator to this server. The first added propagator becomes the
	 * primary (index 0) — NodeServer will set a merge callback on it during
	 * launch to feed store-backed refs into the cursor.
	 *
	 * @param propagator The propagator to add
	 */
	public void addPropagator(LatticePropagator propagator) {
		propagators.add(propagator);
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
		if (!running) return;
		try {
			close();
		} catch (IOException e) {
			log.warn("Error during shutdown persist", e);
		}
	}

	@Override
	public void close() throws IOException {
		if (!running) {
			return;
		}

		log.trace("Closing NodeServer");

		running = false;

		// Final sync: trigger all propagators with current value and wait for drain.
		// This guarantees persistence on the primary propagator (announce + setRootData
		// + mergeCallback). Broadcast to peers is best-effort.
		V snapshot = cursor.get();
		for (LatticePropagator p : propagators) {
			p.triggerAndClose(snapshot);
			p.getConnectionManager().close();
		}

		if (networkServer != null) {
			networkServer.close();
		}

		log.debug("NodeServer closed");
	}
}
