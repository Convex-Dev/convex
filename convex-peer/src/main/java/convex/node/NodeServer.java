package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.lattice.ALattice;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.PathCursor;
import convex.lattice.cursor.Root;
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
 * @param <V> The type of lattice values managed by this node server
 */
public class NodeServer<V extends ACell> implements Closeable {
	
	private static final Logger log = LoggerFactory.getLogger(NodeServer.class.getName());
	
	/**
	 * The lattice instance that defines merge semantics for values
	 */
	private final ALattice<V> lattice;
	
	/**
	 * Cursor for the current local lattice value
	 */
	private final ACursor<V> cursor;
	
	/**
	 * Network server instance for handling connections
	 */
	private AServer networkServer;
	
	/**
	 * Store for persisting and retrieving lattice values
	 */
	private final AStore store;
	
	/**
	 * Message receiver action for handling incoming lattice sync messages
	 */
	private Consumer<Message> receiveAction;
	
	/**
	 * Set of connected peer node addresses
	 */
	private Set<InetSocketAddress> peerNodes;
	
	/**
	 * Port this server is listening on
	 */
	private Integer port;
	
	/**
	 * Whether the server is currently running
	 */
	private boolean running = false;
	
	/**
	 * Creates a new NodeServer instance for the specified lattice.
	 * 
	 * @param lattice The lattice instance defining merge semantics
	 * @param store The store for persisting lattice values
	 * @param port The port to listen on (null for default/random port)
	 */
	public NodeServer(ALattice<V> lattice, AStore store, Integer port) {
		this.lattice = lattice;
		this.store = store;
		this.port = port;
		
		// Initialize value cursor with lattice zero value
		V initialValue = lattice.zero();
		this.cursor = Root.create(initialValue);
		
		this.peerNodes = new java.util.HashSet<>();
		
		// Initialize receive action for handling incoming messages
		this.receiveAction = this::handleIncomingMessage;
		
		// Network server will be created in launch() method
		this.networkServer = null;
	}
	
	/**
	 * Launches the node server, binding to the configured port and starting
	 * network listeners.
	 * 
	 * @throws IOException If an IO error occurs during launch
	 * @throws InterruptedException If the operation is interrupted
	 */
	public void launch() throws IOException, InterruptedException {
		if (running) {
			throw new IllegalStateException("NodeServer is already running");
		}
		
		log.info("Launching NodeServer on port {}", port);
		
		// Create Netty server if not already created
		if (networkServer == null) {
			networkServer = new NettyServer(port);
			// Set the receive action for handling incoming messages
			((NettyServer) networkServer).setReceiveAction(receiveAction);
		}
		
		// Configure and launch network server
		if (port != null) {
			networkServer.setPort(port);
		}
		networkServer.launch();
		port = networkServer.getPort();
		
		running = true;
		log.info("NodeServer started successfully on port {}", port);
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
		} catch (BadFormatException e) {
			log.warn("Bad format in message: {}", message, e);
		} catch (Exception e) {
			log.warn("Error handling incoming message", e);
		}
	}
	
	/**
	 * Processes a PING message by responding with a RESULT containing the same ID.
	 * 
	 * @param message The PING message
	 */
	private void processPing(Message message) {
		ACell id = message.getID();
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
	 * Payload format: [:LQ id [*path*]]
	 * 
	 * @param message The LATTICE_QUERY message
	 * @throws BadFormatException If message format is invalid
	 */
	private void processLatticeQuery(Message message) throws BadFormatException {
		AVector<?> payload = RT.ensureVector(message.getPayload());
		if (payload == null || payload.count() < 2) {
			log.warn("Invalid LATTICE_QUERY message format");
			Result error = Result.create(message.getID(), Strings.create("Invalid LATTICE_QUERY format"), ErrorCodes.ARGUMENT);
			message.returnResult(error);
			return;
		}
		
		ACell id = payload.get(1);
		AVector<?> pathVector = RT.ensureVector(payload.count() > 2 ? payload.get(2) : null);
		
		// Convert path to array if it's a vector
		ACell[] path;
		if (pathVector != null) {
			path=pathVector.toCellArray();
		} else {
			// Empty path means root with empty cell array
			path = Cells.EMPTY_ARRAY;
		}
		
		// Get the value at the path
		V valueAtPath = cursor.get(path);
		
		Result result = Result.create(id, valueAtPath);
		// System.out.println("Lattice query: "+result);
		message.returnResult(result);
		log.debug("Responded to LATTICE_QUERY at path with length: {}", path.length);
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
	 * Processes a LATTICE_VALUE message by merging the received value at the specified path.
	 * 
	 * Payload format: [:LV [*path*] value]
	 * 
	 * @param message The LATTICE_VALUE message
	 * @throws BadFormatException If message format is invalid
	 */
	@SuppressWarnings("unchecked")
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
		
		// Convert path to array if it's a vector
		ACell[] path = null;
		if (pathCell != null) {
			AVector<?> pathVector = RT.ensureVector(pathCell);
			if (pathVector != null) {
				long pathLen = pathVector.count();
				path = new ACell[(int)pathLen];
				for (long i = 0; i < pathLen; i++) {
					path[(int)i] = pathVector.get(i);
				}
			} else {
				// Single key path
				path = new ACell[] { pathCell };
			}
		} else {
			// Empty path means root
			path = new ACell[0];
		}
		
		// Get sub-lattice at path
		ALattice<?> subLattice = lattice.path(path);
		if (subLattice == null && path.length > 0) {
			log.warn("Invalid path for LATTICE_VALUE: path length {}", path.length);
			return;
		}
		
		// Merge the value at the path
		if (path.length == 0) {
			// Root path: merge directly with root lattice
			V receivedValue = (V) value;
			mergeValue(receivedValue);
		} else {
			// Path-specific merge: use PathCursor
			PathCursor<ACell> pathCursor = PathCursor.create(cursor, path);
			ACell currentValueAtPath = pathCursor.get();
			
			// Check foreign value using sub-lattice
			if (subLattice != null) {
				ALattice<ACell> typedSubLattice = (ALattice<ACell>) subLattice;
				if (!typedSubLattice.checkForeign(value)) {
					log.debug("Rejected invalid foreign lattice value at path");
					return;
				}
				
				// Merge using sub-lattice
				ACell merged = typedSubLattice.merge(currentValueAtPath, value);
				pathCursor.set(merged);
				log.debug("Merged lattice value at path with length: {}", path.length);
			} else {
				// No sub-lattice, just set the value
				pathCursor.set(value);
				log.debug("Set lattice value at path with length: {}", path.length);
			}
		}
	}
	
	/**
	 * Syncs lattice value with a remote peer node.
	 * 
	 * @param peerAddress Address of the peer node
	 * @return Future that completes when sync is done, returning the merged value
	 */
	public CompletableFuture<V> syncWithPeer(InetSocketAddress peerAddress) {
		// TODO: Implement peer synchronization
		// 1. Establish connection to peer
		// 2. Send current local value via binary protocol
		// 3. Receive peer's lattice value
		// 4. Merge values using lattice.merge()
		// 5. Update valueCursor with merged value
		// 6. Return merged value
		
		log.debug("Syncing with peer: {}", peerAddress);
		return CompletableFuture.completedFuture(cursor.get()); // Stub
	}
	
	/**
	 * Syncs with a target node by requesting its root lattice value and merging it.
	 * 
	 * Connects to the target node, sends a LATTICE_QUERY with an empty path (root),
	 * receives the result, merges it with the local value, and returns the merged value.
	 * 
	 * @param targetNode Address of the target node to sync with
	 * @return CompletableFuture that completes with the merged value after sync, or fails if sync fails
	 */
	public CompletableFuture<V> sync(InetSocketAddress targetNode) {
		log.debug("Syncing with target node: {}", targetNode);
		
		return CompletableFuture.supplyAsync(() -> {
			ConvexRemote convex = null;
			try {
				// Connect to target node
				convex = ConvexRemote.connect(targetNode);
				log.debug("Connected to target node: {}", targetNode);
				
				// Create LATTICE_QUERY message with empty path (root)
				// Payload format: [:LQ id []]
				CVMLong queryId = CVMLong.create(System.currentTimeMillis());
				AVector<ACell> emptyPath = Vectors.empty();
				AVector<?> queryPayload = Vectors.create(MessageTag.LATTICE_QUERY, queryId, emptyPath);
				Message queryMessage = Message.create(MessageType.LATTICE_QUERY, queryPayload);
				
				// Send query and wait for result with timeout
				CompletableFuture<Result> resultFuture = convex.message(queryMessage);
				Result result = resultFuture.get(10, TimeUnit.SECONDS);
				
				// Check if result is an error
				if (result.isError()) {
					String errorMsg = result.getValue() != null ? result.getValue().toString() : "Unknown error";
					log.warn("Sync failed with error: {}", errorMsg);
					throw new RuntimeException("Sync failed: " + errorMsg);
				}
				
				// Get the received value and merge it
				ACell receivedValue = result.getValue();
				
				// Cast and merge the value
				@SuppressWarnings("unchecked")
				V typedValue = (V) receivedValue;
				V merged = mergeValue(typedValue);
				
				log.debug("Sync completed successfully with target node: {}", targetNode);
				return merged;
				
			} catch (TimeoutException e) {
				log.warn("Sync timeout with target node: {}", targetNode, e);
				throw new RuntimeException("Sync timeout: " + e.getMessage(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Sync interrupted with target node: {}", targetNode, e);
				throw new RuntimeException("Sync interrupted", e);
			} catch (Exception e) {
				log.warn("Sync failed with target node: {}", targetNode, e);
				throw new RuntimeException("Sync failed: " + e.getMessage(), e);
			} finally {
				// Always close the connection
				if (convex != null) {
					convex.close();
				}
			}
		});
	}
	
	/**
	 * Syncs with all connected peer nodes.
	 * 
	 * Calls sync(targetNode) for each connected peer node and waits for all to complete.
	 * 
	 * @return CompletableFuture that completes when all sync operations are done
	 */
	public boolean sync() {
		Set<InetSocketAddress> peers = getPeerNodes();
		
		if (peers.isEmpty()) {
			log.debug("No peer nodes to sync with");
			return true;
		}
		
		log.debug("Syncing with {} peer nodes", peers.size());
		
		// Create sync futures for all peers
		List<CompletableFuture<V>> syncFutures = new ArrayList<>();
		for (InetSocketAddress peer : peers) {
			syncFutures.add(sync(peer));
		}
		
		// Wait for all syncs to complete (or fail)
		CompletableFuture<Void> allSyncs = CompletableFuture.allOf(
			syncFutures.toArray(new CompletableFuture[0])
		);
		
		// Log completion status
		try {	
			allSyncs.join();
			return true;
		} catch (Exception e) {
			log.warn("Sync failed with error: {}", e.getMessage());
			return false;
		}
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
		
		// Atomically update the cursor by merging the current value with the received value
		// This ensures thread-safe updates even if multiple threads are merging concurrently
		V merged = cursor.updateAndGet(currentValue -> {
			V newValue= lattice.merge(currentValue, receivedValue);
			if (currentValue!=newValue) {
				System.out.println("NodeServer Merge:\n"+currentValue+" => "+newValue);
			}
			return newValue;
		});
		
		log.debug("Merged lattice value atomically");
		
		// TODO: Store new value in store if it's a new hash
		// This would involve checking if the merged value's hash is already in the store
		
		return merged;
	}
	
	/**
	 * Broadcasts the current lattice value to all connected peer nodes.
	 */
	public void broadcastValue() {
		// TODO: Implement value broadcasting
		// 1. Get current value from cursor
		// 2. Encode value to binary format
		// 3. Send to all connected peers via binary protocol
		
		// V currentValue = valueCursor.get();
		log.debug("Broadcasting lattice value to {} peers", peerNodes.size());
	}
	
	/**
	 * Adds a peer node address for connection.
	 * 
	 * @param peerAddress Address of the peer node
	 */
	public void addPeer(InetSocketAddress peerAddress) {
		peerNodes.add(peerAddress);
		log.debug("Added peer: {}", peerAddress);
	}
	
	/**
	 * Removes a peer node address.
	 * 
	 * @param peerAddress Address of the peer node to remove
	 */
	public void removePeer(InetSocketAddress peerAddress) {
		peerNodes.remove(peerAddress);
		log.debug("Removed peer: {}", peerAddress);
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
	public ACursor<V> getCursor() {
		return cursor;
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
	 * Gets the set of connected peer node addresses.
	 * 
	 * @return Set of peer addresses
	 */
	public Set<InetSocketAddress> getPeerNodes() {
		return new java.util.HashSet<>(peerNodes);
	}
	
	@Override
	public void close() throws IOException {
		if (!running) {
			return;
		}
		
		log.info("Closing NodeServer");
		
		running = false;
		
		if (networkServer != null) {
			networkServer.close();
		}
		
		peerNodes.clear();
		log.debug("NodeServer closed");
	}
}

