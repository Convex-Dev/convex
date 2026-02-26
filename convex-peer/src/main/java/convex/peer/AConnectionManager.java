package convex.peer;

import java.io.Closeable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.data.AccountKey;
import convex.core.message.Message;

/**
 * Abstract base class for connection managers that maintain outbound peer
 * connections keyed by {@link AccountKey}.
 *
 * <p>Provides shared infrastructure: connection map, queries, dead connection
 * pruning, PING-based liveness testing, and broadcast. Subclasses implement
 * peer selection policy and lifecycle.
 *
 * @see ConnectionManager — consensus peer connections (stake-weighted)
 * @see convex.node.LatticeConnectionManager — lattice peer connections (desired-peer set)
 */
public abstract class AConnectionManager implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(AConnectionManager.class.getName());

	/** Timeout for PING liveness checks (milliseconds). */
	static final long PING_TIMEOUT_MS = 2000;

	/**
	 * Active outbound connections keyed by peer identity.
	 */
	protected final ConcurrentHashMap<AccountKey, Convex> connections = new ConcurrentHashMap<>();

	// ========== Connection Queries ==========

	/**
	 * Gets the connection to a specific peer. Returns null if the peer is not
	 * connected or the connection has been closed (pruning the stale entry).
	 *
	 * @param peerKey Public key of the peer
	 * @return Convex connection, or null if not connected
	 */
	public Convex getConnection(AccountKey peerKey) {
		if (peerKey == null) return null;
		Convex c = connections.get(peerKey);
		if (c == null) return null;
		if (!c.isConnected()) {
			connections.remove(peerKey);
			log.debug("Pruned closed connection to {}", peerKey);
			return null;
		}
		return c;
	}

	/**
	 * Checks if a specific peer is currently connected.
	 *
	 * @param peerKey Public key of the peer
	 * @return true if connected
	 */
	public boolean isConnected(AccountKey peerKey) {
		return getConnection(peerKey) != null;
	}

	/**
	 * Returns the number of active connections.
	 *
	 * @return Connection count
	 */
	public int getConnectionCount() {
		return connections.size();
	}

	/**
	 * Gets a defensive copy of all active connections.
	 *
	 * @return Map of AccountKey to Convex connection
	 */
	public Map<AccountKey, Convex> getConnections() {
		return new HashMap<>(connections);
	}

	/**
	 * Gets all active peer connections as a set.
	 *
	 * @return Defensive copy of the connection values
	 */
	public Set<Convex> getPeers() {
		return new HashSet<>(connections.values());
	}

	// ========== Connection Lifecycle ==========

	/**
	 * Closes and removes a connection to a specific peer.
	 *
	 * @param peerKey Peer key of the connection to close
	 * @param reason  Reason for closing (logged)
	 */
	protected void closeConnection(AccountKey peerKey, String reason) {
		if (peerKey == null) return;
		Convex conn = connections.remove(peerKey);
		if (conn != null) {
			log.info("Removed peer connection to {} Reason={}", peerKey, reason);
			closeSilently(conn);
		}
	}

	/**
	 * Closes all connections managed by this manager.
	 */
	public void closeAllConnections() {
		for (Convex conn : connections.values()) {
			closeSilently(conn);
		}
		connections.clear();
	}

	/**
	 * Closes a Convex connection, ignoring any exceptions.
	 *
	 * @param c Connection to close (may be null)
	 */
	protected static void closeSilently(Convex c) {
		if (c == null) return;
		try {
			c.close();
		} catch (Exception e) {
			// best effort
		}
	}

	// ========== Liveness ==========

	/**
	 * Tests whether a connection is alive by sending a PING and waiting for
	 * a response. Use for active liveness probing — more expensive than
	 * {@code isConnected()} but detects half-open connections.
	 *
	 * @param c Connection to test (may be null)
	 * @return true if the connection responded to PING within timeout
	 */
	protected boolean isAlive(Convex c) {
		if (c == null || !c.isConnected()) return false;
		try {
			c.pingSync(PING_TIMEOUT_MS);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	// ========== Pruning ==========

	/**
	 * Removes connections that are no longer connected (cheap channel-state
	 * check, no network I/O). Call from the maintenance loop.
	 */
	protected void pruneDeadConnections() {
		for (Map.Entry<AccountKey, Convex> entry : connections.entrySet()) {
			Convex c = entry.getValue();
			if (c == null || !c.isConnected()) {
				connections.remove(entry.getKey());
				log.debug("Pruned dead connection to {}", entry.getKey());
			}
		}
	}

	// ========== Broadcast ==========

	/**
	 * Broadcasts a message to all connected peers. Fire-and-forget:
	 * failures on individual peers are logged but do not prevent delivery
	 * to other peers.
	 *
	 * @param msg Message to broadcast
	 */
	public void broadcast(Message msg) {
		for (Convex peer : connections.values()) {
			if (peer != null && peer.isConnected()) {
				peer.trySend(msg);
			}
		}
	}
}
