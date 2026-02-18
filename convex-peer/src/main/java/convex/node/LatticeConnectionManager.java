package convex.node;

import java.io.Closeable;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.message.Message;
import convex.core.store.AStore;

/**
 * Manages outbound peer connections for lattice propagation.
 *
 * <p>Each LatticeConnectionManager owns a set of outbound {@link Convex} connections
 * and an {@link AStore}. When a peer is added, the store is set on the connection,
 * establishing a security boundary: peers can only resolve data that exists in this
 * store.
 *
 * <p>Designed for composition with {@link LatticePropagator} (which uses it for
 * broadcast) and {@link NodeServer} (which uses it for sync and data acquisition).
 * The same ConnectionManager can be shared between a propagator and a server, or
 * each can have its own — this is an operator/application decision.
 *
 * <p>The peer {@code ConnectionManager} in {@code convex.peer} follows the same
 * core pattern (connections + broadcast) but adds consensus-specific policy
 * (stake-weighted selection, challenge/response). Both can converge on a shared
 * foundation.
 */
public class LatticeConnectionManager implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LatticeConnectionManager.class.getName());

	/**
	 * Set of outbound peer connections. Thread-safe for concurrent access
	 * from broadcast thread and external addPeer/removePeer calls.
	 */
	private final Set<Convex> peers = ConcurrentHashMap.newKeySet();

	/**
	 * Store set on peer connections. Determines what data peers can resolve
	 * via DATA_REQUEST — this is the security boundary.
	 */
	private final AStore store;

	/**
	 * Creates a new LatticeConnectionManager with the given store.
	 *
	 * @param store Store to set on peer connections (security boundary)
	 */
	public LatticeConnectionManager(AStore store) {
		if (store == null) throw new IllegalArgumentException("Store must not be null");
		this.store = store;
	}

	/**
	 * Adds an outbound peer connection. Sets this manager's store on the
	 * connection, establishing the security boundary for data resolution.
	 *
	 * @param convex Peer connection to add
	 */
	public void addPeer(Convex convex) {
		if (convex == null) {
			log.warn("Attempted to add null peer connection");
			return;
		}
		convex.setStore(store);
		peers.add(convex);
		log.debug("Added peer: {}", convex.getHostAddress());
	}

	/**
	 * Removes an outbound peer connection.
	 *
	 * @param convex Peer connection to remove
	 */
	public void removePeer(Convex convex) {
		if (convex == null) return;
		boolean removed = peers.remove(convex);
		if (removed) {
			log.debug("Removed peer: {}", convex.getHostAddress());
		}
	}

	/**
	 * Gets a snapshot of current peer connections.
	 *
	 * @return Defensive copy of the peer set
	 */
	public Set<Convex> getPeers() {
		return new HashSet<>(peers);
	}

	/**
	 * Broadcasts a message to all connected peers. Fire-and-forget:
	 * failures on individual peers are logged but do not prevent
	 * delivery to other peers.
	 *
	 * @param msg Message to broadcast
	 */
	public void broadcast(Message msg) {
		for (Convex peer : peers) {
			if (peer != null && peer.isConnected()) {
				try {
					peer.message(msg);
				} catch (Exception e) {
					log.debug("Failed to broadcast to peer {}: {}",
						peer.getHostAddress(), e.getMessage());
				}
			}
		}
	}

	/**
	 * Gets the store used by this connection manager.
	 *
	 * @return The store (security boundary for peer data resolution)
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Closes all peer connections and clears the peer set.
	 */
	@Override
	public void close() {
		for (Convex peer : peers) {
			if (peer != null) {
				try {
					peer.close();
					log.trace("Closed peer connection: {}", peer.getHostAddress());
				} catch (Exception e) {
					log.warn("Error closing peer connection: {}", peer.getHostAddress(), e);
				}
			}
		}
		peers.clear();
		log.debug("LatticeConnectionManager closed ({} peers)", peers.size());
	}
}
