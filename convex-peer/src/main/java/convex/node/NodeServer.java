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

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.Hash;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
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
	 * Automatic lattice propagator for broadcasting updates
	 */
	private LatticePropagator<V> propagator;

	/**
	 * Message receiver action for handling incoming lattice sync messages
	 */
	private Consumer<Message> receiveAction;

	/**
	 * Set of connected peer Convex instances (maintains persistent connections)
	 */
	private Set<Convex> peerNodes;

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
	 * network listeners and automatic propagation.
	 *
	 * @throws IOException If an IO error occurs during launch
	 * @throws InterruptedException If the operation is interrupted
	 */
	public void launch() throws IOException, InterruptedException {
		if (running) {
			throw new IllegalStateException("NodeServer is already running");
		}

		log.debug("Launching NodeServer on port {}", port);

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

		// Start automatic lattice propagator
		propagator = new LatticePropagator<>(this, store);
		propagator.start();

		log.debug("NodeServer started successfully on port {}", port);
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
	 * Payload format: [:LQ id [*path*]]
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
	 * Processes a LATTICE_VALUE message with automatic missing data recovery.
	 *
	 * Uses speculative merge in a forked cursor to detect missing data,
	 * then pulls only what's needed before committing the merge.
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

		// Convert path to array
		ACell[] path = extractPath(pathCell);

		// Get sub-lattice at path for validation
		ALattice<?> subLattice = lattice.path(path);
		if (subLattice == null && path.length > 0) {
			log.warn("Invalid path for LATTICE_VALUE: path length {}", path.length);
			return;
		}

		// Merge with fork + acquire pattern
		if (path.length == 0) {
			// Root merge: use fork pattern for automatic recovery
			V receivedValue = (V) value;
			mergeValueWithAcquire(receivedValue, message);
		} else {
			// Path-specific merge: use existing logic with acquire fallback
			mergePathWithAcquire(path, value, message);
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
	 * Attempts to merge a value at root, forking the cursor to detect missing data
	 * and acquiring it automatically before committing the merge.
	 *
	 * This implements the "speculative fork + acquire" pattern:
	 * 1. Fork the cursor (cheap, copy-on-write)
	 * 2. Attempt merge in fork
	 * 3. If MissingDataException => acquire missing cells
	 * 4. Retry merge after acquisition
	 * 5. Commit successful merge to main cursor
	 * 6. Trigger immediate delta broadcast
	 *
	 * @param receivedValue Value to merge
	 * @param message Original message (for tracking sender)
	 */
	private void mergeValueWithAcquire(V receivedValue, Message message) {
		try {
			// Validate foreign value
			if (!lattice.checkForeign(receivedValue)) {
				log.debug("Rejected invalid foreign lattice value");
				return;
			}

			// Attempt merge with automatic acquisition on missing data
			V merged = mergeValueWithRetry(receivedValue, 3);
			if (merged != null) {
				cursor.set(merged);
				log.debug("Merged lattice value successfully");

				// Trigger immediate delta broadcast
				if (propagator != null) {
					propagator.triggerBroadcast();
				}
			}
		} catch (Exception e) {
			log.warn("Error during lattice merge with acquire", e);
		}
	}

	/**
	 * Merges a value with automatic retry on missing data.
	 *
	 * @param receivedValue Value to merge
	 * @param maxRetries Maximum number of acquisition retries
	 * @return Merged value, or null if merge failed
	 */
	private V mergeValueWithRetry(V receivedValue, int maxRetries) {
		int attempt = 0;
		while (attempt < maxRetries) {
			try {
				// Attempt merge
				V currentValue = cursor.get();
				V merged = lattice.merge(currentValue, receivedValue);

				// Try to persist (triggers MissingDataException if data missing)
				merged = Cells.persist(merged, store);
				return merged;

			} catch (MissingDataException e) {
				attempt++;
				log.debug("Missing data in lattice merge (attempt {}): {}, acquiring...",
					attempt, e.getMissingHash());

				// Acquire missing data from peers
				ACell acquired = acquireFromPeers(e.getMissingHash());
				if (acquired == null) {
					log.warn("Could not acquire missing data after {} attempts: {}",
						attempt, e.getMissingHash());
					return null;
				}

				log.debug("Acquired missing data, retrying merge");
				// Loop will retry merge

			} catch (IOException e) {
				log.warn("IO error during lattice merge", e);
				return null;
			}
		}

		log.warn("Failed to merge after {} acquisition attempts", maxRetries);
		return null;
	}

	/**
	 * Merges a value at a specific path with acquire fallback.
	 *
	 * @param path Path array
	 * @param value Value to merge at path
	 * @param message Original message
	 */
	@SuppressWarnings("unchecked")
	private void mergePathWithAcquire(ACell[] path, ACell value, Message message) {
		try {
			// Get sub-lattice at path
			ALattice<?> subLattice = lattice.path(path);
			PathCursor<ACell> pathCursor = PathCursor.create(cursor, path);
			ACell currentValueAtPath = pathCursor.get();

			boolean merged = false;

			// Check foreign value using sub-lattice
			if (subLattice != null) {
				ALattice<ACell> typedSubLattice = (ALattice<ACell>) subLattice;
				if (!typedSubLattice.checkForeign(value)) {
					log.debug("Rejected invalid foreign lattice value at path");
					return;
				}

				// Attempt merge with retry on missing data
				ACell mergedValue = mergePathValueWithRetry(typedSubLattice, currentValueAtPath, value, 3);
				if (mergedValue != null) {
					pathCursor.set(mergedValue);
					log.debug("Merged lattice value at path with length: {}", path.length);
					merged = true;
				}
			} else {
				// No sub-lattice, just set the value
				pathCursor.set(value);
				log.debug("Set lattice value at path with length: {}", path.length);
				merged = true;
			}

			// Trigger immediate delta broadcast after successful merge
			if (merged && propagator != null) {
				propagator.triggerBroadcast();
			}
		} catch (Exception e) {
			log.warn("Error during path merge with acquire", e);
		}
	}

	/**
	 * Merges a value at path with automatic retry on missing data.
	 *
	 * @param subLattice Sub-lattice for merge
	 * @param currentValue Current value at path
	 * @param receivedValue Received value to merge
	 * @param maxRetries Maximum acquisition retries
	 * @return Merged value, or null if failed
	 */
	private ACell mergePathValueWithRetry(ALattice<ACell> subLattice, ACell currentValue,
	                                       ACell receivedValue, int maxRetries) {
		int attempt = 0;
		while (attempt < maxRetries) {
			try {
				// Attempt merge
				ACell merged = subLattice.merge(currentValue, receivedValue);
				merged = Cells.persist(merged, store);
				return merged;

			} catch (MissingDataException e) {
				attempt++;
				log.debug("Missing data in path merge (attempt {}): {}, acquiring...",
					attempt, e.getMissingHash());

				ACell acquired = acquireFromPeers(e.getMissingHash());
				if (acquired == null) {
					log.warn("Could not acquire missing data: {}", e.getMissingHash());
					return null;
				}

				log.debug("Acquired missing data, retrying merge");
				// Loop will retry

			} catch (IOException e) {
				log.warn("IO error during path merge", e);
				return null;
			}
		}

		return null;
	}

	/**
	 * Acquires missing data from connected peers.
	 *
	 * Tries each peer in turn until the data is successfully acquired.
	 *
	 * @param missingHash Hash of missing data
	 * @return Acquired cell, or null if not found
	 */
	private ACell acquireFromPeers(Hash missingHash) {
		for (Convex peer : peerNodes) {
			if (peer == null || !peer.isConnected()) continue;

			try {
				// Use Convex.acquire() to pull missing data
				ACell acquired = peer.acquire(missingHash).get(5, TimeUnit.SECONDS);
				log.debug("Acquired missing data from peer {}: {}",
					peer.getHostAddress(), missingHash);
				return acquired;
			} catch (Exception e) {
				// Try next peer
				log.trace("Could not acquire from peer {}: {}",
					peer.getHostAddress(), e.getMessage());
			}
		}

		log.warn("Could not acquire missing data from any peer: {}", missingHash);
		return null;
	}
	
	/**
	 * Syncs lattice value with a remote peer node using the provided Convex connection.
	 * 
	 * @param convex Convex connection to the peer node
	 * @return Future that completes when sync is done, returning the merged value
	 */
	public CompletableFuture<V> syncWithPeer(Convex convex) {
		// Use the sync method which handles the LATTICE_QUERY
		return sync(convex);
	}
	
	/**
	 * Syncs with a target node by requesting its root lattice value and merging it.
	 * 
	 * Uses the provided Convex connection to send a LATTICE_QUERY with an empty path (root),
	 * receives the result, merges it with the local value, and returns the merged value.
	 * 
	 * @param convex Convex connection to the target node
	 * @return CompletableFuture that completes with the merged value after sync, or fails if sync fails
	 */
	public CompletableFuture<V> sync(Convex convex) {
		if (convex == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Convex connection cannot be null"));
		}
		
		log.debug("Syncing with target node: {}", convex.getHostAddress());
		
		return CompletableFuture.supplyAsync(() -> {
			try {
				// Check if connection is still valid
				if (!convex.isConnected()) {
					throw new RuntimeException("Convex connection is not connected");
				}
				
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
					String errorMsg = result.toString();
					log.warn("Sync failed with error: {}", errorMsg);
					throw new RuntimeException("Sync failed: " + errorMsg);
				}
				
				// Get the received value and merge it
				ACell receivedValue = result.getValue();
				
				// Cast and merge the value
				@SuppressWarnings("unchecked")
				V typedValue = (V) receivedValue;
				V merged = mergeValue(typedValue);
				
				log.debug("Sync completed successfully with target node: {}", convex.getHostAddress());
				return merged;
				
			} catch (TimeoutException e) {
				log.warn("Sync timeout with target node: {}", convex.getHostAddress(), e);
				throw new RuntimeException("Sync timeout: " + e.getMessage(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Sync interrupted with target node: {}", convex.getHostAddress(), e);
				throw new RuntimeException("Sync interrupted", e);
			} catch (Exception e) {
				log.warn("Sync failed with target node: {}", convex.getHostAddress(), e);
				throw new RuntimeException("Sync failed: " + e.getMessage(), e);
			}
		});
	}
	
	/**
	 * Syncs with all connected peer nodes.
	 * 
	 * Calls sync(convex) for each connected peer Convex instance and waits for all to complete.
	 * 
	 * @return true if all syncs completed successfully, false otherwise
	 */
	public boolean sync() {
		Set<Convex> peers = getPeerNodes();
		
		if (peers.isEmpty()) {
			log.debug("No peer nodes to sync with");
			return true;
		}
		
		log.debug("Syncing with {} peer nodes", peers.size());
		
		// Create sync futures for all peers
		List<CompletableFuture<V>> syncFutures = new ArrayList<>();
		for (Convex peer : peers) {
			// Only sync with connected peers
			if (peer != null && peer.isConnected()) {
				syncFutures.add(sync(peer));
			} else {
				log.debug("Skipping disconnected peer: {}", peer);
			}
		}
		
		if (syncFutures.isEmpty()) {
			log.debug("No connected peers to sync with");
			return true;
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
			// if (currentValue!=newValue) System.out.println("NodeServer Merge:\n"+currentValue+" => "+newValue);
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
		V currentValue = cursor.get();
		if (currentValue == null) {
			log.debug("No value to broadcast");
			return;
		}
		
		log.debug("Broadcasting lattice value to {} peers", peerNodes.size());
		
		// Send LATTICE_VALUE message to all connected peers
		for (Convex peer : peerNodes) {
			if (peer != null && peer.isConnected()) {
				try {
					// Create LATTICE_VALUE message with empty path (root) and current value
					// Payload format: [:LV [] value]
					AVector<ACell> emptyPath = Vectors.empty();
					AVector<?> valuePayload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, currentValue);
					Message valueMessage = Message.create(MessageType.LATTICE_VALUE, valuePayload);
					
					// Send message asynchronously (fire and forget)
					peer.message(valueMessage);
					log.debug("Broadcasted lattice value to peer: {}", peer.getHostAddress());
				} catch (Exception e) {
					log.debug("Failed to broadcast to peer: {}", peer.getHostAddress(), e);
				}
			}
		}
	}
	
	/**
	 * Adds a peer Convex connection.
	 * 
	 * @param convex Convex connection to the peer node
	 */
	public void addPeer(Convex convex) {
		if (convex == null) {
			log.warn("Attempted to add null peer connection");
			return;
		}
		convex.setStore(store);
		peerNodes.add(convex);
		log.debug("Added peer: {}", convex.getHostAddress());
	}
	
	/**
	 * Removes a peer Convex connection.
	 * 
	 * @param convex Convex connection to remove
	 */
	public void removePeer(Convex convex) {
		if (convex == null) {
			return;
		}
		boolean removed = peerNodes.remove(convex);
		if (removed) {
			log.debug("Removed peer: {}", convex.getHostAddress());
		}
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
	 * Updates the local lattice value and triggers an immediate delta broadcast.
	 *
	 * This is the recommended way to update the root lattice value from local code,
	 * as it ensures the change is immediately propagated to all connected peers.
	 *
	 * @param newValue The new lattice value to set
	 */
	public void updateLocal(V newValue) {
		cursor.set(newValue);
		if (propagator != null) {
			propagator.triggerBroadcast();
		}
	}

	/**
	 * Updates the local lattice value at a specific path and triggers an immediate delta broadcast.
	 *
	 * This is the recommended way to update path-specific values from local code,
	 * as it ensures the change is immediately propagated to all connected peers.
	 *
	 * @param value The value to set at the path
	 * @param path Path keys (varargs)
	 */
	public void updateLocalPath(ACell value, ACell... path) {
		cursor.set(value, path);
		if (propagator != null) {
			propagator.triggerBroadcast();
		}
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
	 * Gets the set of connected peer Convex instances.
	 *
	 * @return Set of peer Convex connections
	 */
	public Set<Convex> getPeerNodes() {
		return new java.util.HashSet<>(peerNodes);
	}

	/**
	 * Gets the automatic lattice propagator instance.
	 *
	 * @return LatticePropagator instance, or null if server is not launched
	 */
	public LatticePropagator<V> getPropagator() {
		return propagator;
	}

	@Override
	public void close() throws IOException {
		if (!running) {
			return;
		}

		log.trace("Closing NodeServer");

		running = false;

		// Stop automatic propagator first
		if (propagator != null) {
			propagator.close();
		}

		if (networkServer != null) {
			networkServer.close();
		}

		// Close all peer connections
		for (Convex peer : peerNodes) {
			if (peer != null) {
				try {
					peer.close();
					log.trace("Closed peer connection: {}", peer.getHostAddress());
				} catch (Exception e) {
					log.warn("Error closing peer connection: {}", peer.getHostAddress(), e);
				}
			}
		}
		peerNodes.clear();
		log.debug("NodeServer closed");
	}
}

