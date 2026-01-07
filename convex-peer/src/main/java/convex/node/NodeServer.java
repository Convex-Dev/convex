package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.lattice.ALattice;
import convex.lattice.cursor.ACursor;
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
	 * Messages are expected to contain lattice values encoded in binary format.
	 * 
	 * @param message The incoming message
	 */
	private void handleIncomingMessage(Message message) {
		// TODO: Implement message handling
		// 1. Decode binary message to extract lattice value
		// 2. Validate foreign value using lattice.checkForeign()
		// 3. Merge with local value using lattice.merge()
		// 4. Update local value and persist if needed
		// 5. Optionally propagate merged value to other peers
		
		log.debug("Received message from peer: {}", message);
		
		try {
			// TODO: Decode lattice value from binary data
			// Blob messageData = message.getMessageData();
			// V receivedValue = decodeLatticeValue(messageData);
			
			// TODO: Validate and merge using lattice instance
			// V currentValue = valueCursor.get();
			// if (lattice.checkForeign(receivedValue)) {
			//     V merged = lattice.merge(currentValue, receivedValue);
			//     valueCursor.set(merged);
			// }
		} catch (Exception e) {
			log.warn("Error handling incoming message", e);
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
			return lattice.merge(currentValue, receivedValue);
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
	public ACursor<V> getValueCursor() {
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

