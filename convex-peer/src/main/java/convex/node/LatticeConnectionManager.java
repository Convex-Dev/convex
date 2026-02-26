package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.cvm.Keywords;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.data.Strings;
import convex.net.IPUtils;

/**
 * Manages outbound peer connections for lattice propagation with identity-based
 * peer tracking and automatic reconnection.
 *
 * <p>Peers are identified by {@link AccountKey} and tracked as {@link DesiredPeer}
 * entries that mirror the P2PLattice {@code NodeInfo} structure. The connection
 * manager maintains active connections to desired peers and automatically
 * reconnects with exponential backoff when connections drop.
 *
 * <p>The primary API is {@link #addPeer(AccountKey)} — declare intent to connect
 * and the manager handles lookup, connection, and reconnection. For cases where
 * a connection or address is already known, use {@link #addPeer(AccountKey, Convex)}
 * or {@link #addPeer(AccountKey, InetSocketAddress)}.
 *
 * <p>Each connection has this manager's {@link AStore} set on it, establishing a
 * security boundary: peers can only resolve data that exists in this store via
 * DATA_REQUEST.
 *
 * <p>Designed for composition with {@link LatticePropagator} (broadcast) and
 * {@link NodeServer} (sync and data acquisition).
 *
 * @see DesiredPeer
 */
public class LatticeConnectionManager implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LatticeConnectionManager.class.getName());

	// ========== Constants ==========

	/** Interval between maintenance loop iterations (milliseconds). */
	static final long MAINTENANCE_INTERVAL = 5_000L;

	/** Initial reconnection delay (milliseconds). */
	static final long INITIAL_BACKOFF_MS = 1_000L;

	/** Maximum reconnection delay (milliseconds). */
	static final long MAX_BACKOFF_MS = 30_000L;

	// ========== State ==========

	/**
	 * Active outbound connections keyed by peer identity.
	 */
	private final ConcurrentHashMap<AccountKey, Convex> connections = new ConcurrentHashMap<>();

	/**
	 * Desired peers — peers this node wants to stay connected to. The maintenance
	 * thread connects to any desired peer not in {@link #connections} and
	 * reconnects peers whose connections have dropped.
	 */
	private final ConcurrentHashMap<AccountKey, DesiredPeer> desiredPeers = new ConcurrentHashMap<>();

	/**
	 * Store set on peer connections. Determines what data peers can resolve
	 * via DATA_REQUEST — this is the security boundary.
	 */
	private final AStore store;

	/** Maintenance thread for reconnection. */
	private Thread maintenanceThread;

	/** Whether the maintenance loop is running. */
	private volatile boolean running = false;

	// ========== Constructor ==========

	/**
	 * Creates a new LatticeConnectionManager with the given store.
	 *
	 * @param store Store to set on peer connections (security boundary)
	 */
	public LatticeConnectionManager(AStore store) {
		if (store == null) throw new IllegalArgumentException("Store must not be null");
		this.store = store;
	}

	// ========== Lifecycle ==========

	/**
	 * Starts the maintenance thread for automatic reconnection.
	 * Safe to call multiple times — subsequent calls are no-ops.
	 */
	public synchronized void start() {
		if (running) return;
		running = true;
		maintenanceThread = Thread.ofVirtual().name("Lattice connection maintenance").start(this::maintenanceLoop);
		log.debug("LatticeConnectionManager started");
	}

	/**
	 * Stops the maintenance thread and closes all connections.
	 */
	@Override
	public void close() {
		running = false;

		if (maintenanceThread != null) {
			maintenanceThread.interrupt();
			try {
				maintenanceThread.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			maintenanceThread = null;
		}

		for (Convex peer : connections.values()) {
			closeSilently(peer);
		}
		connections.clear();

		log.debug("LatticeConnectionManager closed");
	}

	// ========== Peer Management ==========

	/**
	 * Declares intent to connect to a peer identified by AccountKey. The
	 * connection manager will look up transport information from its known
	 * desired peers (populated via {@link #updateDesiredPeers}) and connect
	 * when transport info becomes available.
	 *
	 * <p>If transport info is not yet known, the peer is added to the desired
	 * set with no transports. The maintenance thread will attempt connection
	 * once transport info arrives via {@link #updateDesiredPeers}.
	 *
	 * @param peerKey AccountKey of the peer to connect to
	 */
	public void addPeer(AccountKey peerKey) {
		if (peerKey == null) {
			log.warn("Attempted to add peer with null key");
			return;
		}
		desiredPeers.computeIfAbsent(peerKey, k -> DesiredPeer.create(k));
		log.debug("Added desired peer: {}", peerKey);
	}

	/**
	 * Declares intent to connect to a peer at a known address. Creates a
	 * desired peer entry with a {@code tcp://} transport URI derived from
	 * the address. The maintenance thread will connect if not already connected.
	 *
	 * @param peerKey AccountKey of the peer
	 * @param address Network address to connect to
	 */
	public void addPeer(AccountKey peerKey, InetSocketAddress address) {
		if (peerKey == null || address == null) {
			log.warn("Attempted to add peer with null key or address");
			return;
		}
		desiredPeers.put(peerKey, DesiredPeer.create(peerKey, address));
		log.debug("Added desired peer: {} at {}", peerKey, address);
	}

	/**
	 * Registers a live connection to a peer. Adds the peer to the desired set
	 * (using the connection's host address as transport) and records the
	 * connection as active. The store is set on the connection.
	 *
	 * @param peerKey AccountKey of the peer
	 * @param convex Live connection to the peer
	 */
	public void addPeer(AccountKey peerKey, Convex convex) {
		if (peerKey == null || convex == null) {
			log.warn("Attempted to add peer with null key or connection");
			return;
		}
		convex.setStore(store);
		connections.put(peerKey, convex);

		// Ensure desired peer entry exists with transport from connection
		InetSocketAddress addr = convex.getHostAddress();
		if (addr != null) {
			desiredPeers.computeIfAbsent(peerKey, k -> DesiredPeer.create(k, addr));
		} else {
			desiredPeers.computeIfAbsent(peerKey, k -> DesiredPeer.create(k));
		}
		log.debug("Added peer with connection: {} at {}", peerKey, addr);
	}

	/**
	 * Removes a peer from both the desired set and active connections.
	 * Closes the connection if one exists.
	 *
	 * @param peerKey AccountKey of the peer to remove
	 */
	public void removePeer(AccountKey peerKey) {
		if (peerKey == null) return;
		desiredPeers.remove(peerKey);
		Convex removed = connections.remove(peerKey);
		if (removed != null) {
			closeSilently(removed);
			log.debug("Removed peer: {}", peerKey);
		}
	}

	// ========== Connection Queries ==========

	/**
	 * Gets the active connection to a specific peer.
	 *
	 * @param peerKey AccountKey of the peer
	 * @return Convex connection, or null if not connected
	 */
	public Convex getConnection(AccountKey peerKey) {
		Convex c = connections.get(peerKey);
		if (c != null && !c.isConnected()) {
			connections.remove(peerKey);
			return null;
		}
		return c;
	}

	/**
	 * Checks if a specific peer is currently connected.
	 *
	 * @param peerKey AccountKey of the peer
	 * @return true if connected
	 */
	public boolean isConnected(AccountKey peerKey) {
		return getConnection(peerKey) != null;
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
	 * Gets all active peer connections as a set. This is the primary method
	 * used by {@link LatticePropagator} for broadcast and pull operations.
	 *
	 * @return Defensive copy of the connection values
	 */
	public Set<Convex> getPeers() {
		return new HashSet<>(connections.values());
	}

	/**
	 * Returns the number of active connections.
	 *
	 * @return Connection count
	 */
	public int getConnectionCount() {
		return connections.size();
	}

	// ========== Desired Peer Management ==========

	/**
	 * Gets a defensive copy of the desired peers map.
	 *
	 * @return Map of AccountKey to DesiredPeer
	 */
	public Map<AccountKey, DesiredPeer> getDesiredPeers() {
		return new HashMap<>(desiredPeers);
	}

	/**
	 * Updates the desired peer set from {@code [:p2p :nodes]} lattice data.
	 * Only adds or updates entries — does not remove peers that are no longer
	 * in the lattice (they may be configured peers).
	 *
	 * <p>Uses LWW timestamp ordering: only updates a desired peer entry if the
	 * incoming NodeInfo has a newer timestamp. Preserves reconnection state
	 * (failCount, nextRetryTime) across updates.
	 *
	 * @param nodesMap The merged OwnerLattice value at {@code [:p2p :nodes]},
	 *                 mapping AccountKey to SignedData containing NodeInfo
	 * @param ownKey This node's own AccountKey (skipped to avoid self-connection),
	 *               or null if unknown
	 */
	@SuppressWarnings("unchecked")
	public void updateDesiredPeers(AHashMap<ACell, SignedData<ACell>> nodesMap, AccountKey ownKey) {
		if (nodesMap == null) return;

		for (Map.Entry<ACell, SignedData<ACell>> entry : nodesMap.entrySet()) {
			AccountKey peerKey = RT.ensureAccountKey(entry.getKey());
			if (peerKey == null) continue;
			if (peerKey.equals(ownKey)) continue;

			SignedData<ACell> signed = entry.getValue();
			if (signed == null) continue;

			AHashMap<Keyword, ACell> nodeInfo = (AHashMap<Keyword, ACell>) signed.getValue();
			if (nodeInfo == null) continue;

			DesiredPeer updated = DesiredPeer.fromNodeInfo(peerKey, nodeInfo);
			desiredPeers.merge(peerKey, updated, (existing, incoming) -> {
				if (incoming.timestamp > existing.timestamp) {
					// Preserve reconnection state
					incoming.failCount = existing.failCount;
					incoming.nextRetryTime = existing.nextRetryTime;
					return incoming;
				}
				return existing;
			});
		}
	}

	// ========== Broadcast ==========

	/**
	 * Broadcasts a message to all connected peers. Fire-and-forget:
	 * failures on individual peers are logged but do not prevent
	 * delivery to other peers.
	 *
	 * @param msg Message to broadcast
	 */
	public void broadcast(Message msg) {
		for (Convex peer : connections.values()) {
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

	// ========== Accessors ==========

	/**
	 * Gets the store used by this connection manager.
	 *
	 * @return The store (security boundary for peer data resolution)
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Checks if the maintenance thread is running.
	 *
	 * @return true if running
	 */
	public boolean isRunning() {
		return running;
	}

	// ========== Maintenance Loop ==========

	/**
	 * Background loop that maintains connections to desired peers.
	 * Prunes dead connections and reconnects with exponential backoff.
	 */
	private void maintenanceLoop() {
		while (running) {
			try {
				Thread.sleep(MAINTENANCE_INTERVAL);
				maintainConnections();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.warn("Error in connection maintenance", e);
				if (!running) break;
			}
		}
		log.debug("Maintenance loop ended");
	}

	/**
	 * Single maintenance pass: prune dead connections, attempt reconnection
	 * for desired peers that are not currently connected.
	 */
	void maintainConnections() {
		long now = System.currentTimeMillis();

		// 1. Prune dead connections
		for (Map.Entry<AccountKey, Convex> entry : connections.entrySet()) {
			Convex c = entry.getValue();
			if (c == null || !c.isConnected()) {
				connections.remove(entry.getKey());
				log.debug("Pruned dead connection to {}", entry.getKey());
			}
		}

		// 2. Connect to desired peers that are not currently connected
		for (Map.Entry<AccountKey, DesiredPeer> entry : desiredPeers.entrySet()) {
			AccountKey peerKey = entry.getKey();
			DesiredPeer desired = entry.getValue();

			if (connections.containsKey(peerKey)) continue;
			if (now < desired.nextRetryTime) continue;

			InetSocketAddress target = resolveTransport(desired);
			if (target == null) continue;

			try {
				Convex convex = Convex.connect(target);
				convex.setStore(store);
				connections.put(peerKey, convex);
				desired.failCount = 0;
				desired.nextRetryTime = 0;
				log.info("Connected to peer {} at {}", peerKey, target);
			} catch (Exception e) {
				desired.failCount++;
				desired.nextRetryTime = now + calculateBackoff(desired.failCount);
				log.debug("Failed to connect to peer {} (attempt {}): {}",
					peerKey, desired.failCount, e.getMessage());
			}
		}
	}

	// ========== Transport Resolution ==========

	/**
	 * Resolves the first usable transport address from a desired peer's
	 * transport URIs. Currently supports {@code tcp://} URIs only.
	 *
	 * @param desired The desired peer entry
	 * @return InetSocketAddress to connect to, or null if no usable transport
	 */
	static InetSocketAddress resolveTransport(DesiredPeer desired) {
		AVector<AString> transports = desired.transports;
		if (transports == null || transports.isEmpty()) return null;

		for (long i = 0; i < transports.count(); i++) {
			AString uri = (AString) transports.get(i);
			if (uri == null) continue;
			String uriStr = uri.toString();

			// Strip tcp:// prefix if present
			if (uriStr.startsWith("tcp://")) {
				uriStr = uriStr.substring(6);
			} else if (uriStr.contains("://")) {
				// Skip non-TCP transports (wss://, https://, etc.) for now
				continue;
			}

			InetSocketAddress sa = IPUtils.toInetSocketAddress(uriStr);
			if (sa != null) return sa;
		}
		return null;
	}

	// ========== Backoff Calculation ==========

	/**
	 * Calculates reconnection delay with exponential backoff and jitter.
	 * Initial delay 1s, max 30s, per P2P_DESIGN.md §8.3.
	 *
	 * @param failCount Number of consecutive failures (1-based)
	 * @return Delay in milliseconds before next retry
	 */
	static long calculateBackoff(int failCount) {
		long base = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << Math.min(failCount - 1, 10));
		long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
		return base / 2 + jitter;
	}

	// ========== Helpers ==========

	private static void closeSilently(Convex convex) {
		if (convex == null) return;
		try {
			convex.close();
		} catch (Exception e) {
			// best effort
		}
	}

	// ========== DesiredPeer ==========

	/**
	 * Describes a peer this node wants to maintain a connection to. Fields
	 * mirror the P2PLattice {@code NodeInfo} structure so entries can be
	 * populated directly from {@code [:p2p :nodes]} lattice data.
	 *
	 * <p>Reconnection state ({@code failCount}, {@code nextRetryTime}) is
	 * managed by the maintenance thread and not propagated.
	 */
	public static class DesiredPeer {

		/** Peer identity (required). */
		public final AccountKey peerKey;

		/** Transport URIs from NodeInfo {@code :transports} (nullable). */
		public final AVector<AString> transports;

		/** Node software type from NodeInfo {@code :type} (nullable). */
		public final AString type;

		/** Software version from NodeInfo {@code :version} (nullable). */
		public final AString version;

		/** LWW timestamp from NodeInfo {@code :timestamp}. */
		public final long timestamp;

		/** Consecutive connection failure count. */
		int failCount = 0;

		/** Earliest time (millis) to retry connection. */
		long nextRetryTime = 0;

		private DesiredPeer(AccountKey peerKey, AVector<AString> transports,
				AString type, AString version, long timestamp) {
			this.peerKey = peerKey;
			this.transports = transports;
			this.type = type;
			this.version = version;
			this.timestamp = timestamp;
		}

		/**
		 * Creates a DesiredPeer from a P2PLattice NodeInfo map.
		 *
		 * @param peerKey Peer identity
		 * @param nodeInfo NodeInfo map with :transports, :type, :version, :timestamp
		 * @return DesiredPeer with fields extracted from the map
		 */
		@SuppressWarnings("unchecked")
		public static DesiredPeer fromNodeInfo(AccountKey peerKey, AHashMap<Keyword, ACell> nodeInfo) {
			AVector<AString> transports = (AVector<AString>) nodeInfo.get(Keywords.TRANSPORTS);
			AString type = RT.ensureString(nodeInfo.get(Keywords.TYPE));
			AString version = RT.ensureString(nodeInfo.get(Keywords.VERSION));
			CVMLong ts = RT.ensureLong(nodeInfo.get(Keywords.TIMESTAMP));
			long timestamp = (ts != null) ? ts.longValue() : 0L;
			return new DesiredPeer(peerKey, transports, type, version, timestamp);
		}

		/**
		 * Creates a DesiredPeer with a known address, wrapping it as a
		 * {@code tcp://host:port} transport URI.
		 *
		 * @param peerKey Peer identity
		 * @param address Network address
		 * @return DesiredPeer with a single TCP transport
		 */
		@SuppressWarnings({"unchecked", "rawtypes"})
		public static DesiredPeer create(AccountKey peerKey, InetSocketAddress address) {
			String uri = "tcp://" + address.getHostString() + ":" + address.getPort();
			AVector<AString> transports = (AVector) Vectors.of(Strings.create(uri));
			return new DesiredPeer(peerKey, transports, null, null, System.currentTimeMillis());
		}

		/**
		 * Creates a DesiredPeer with no transport info yet. The maintenance
		 * thread will not attempt connection until transport info arrives
		 * (e.g. via {@link LatticeConnectionManager#updateDesiredPeers}).
		 *
		 * @param peerKey Peer identity
		 * @return DesiredPeer with null transports
		 */
		public static DesiredPeer create(AccountKey peerKey) {
			return new DesiredPeer(peerKey, null, null, null, System.currentTimeMillis());
		}

		@Override
		public String toString() {
			return "DesiredPeer{" + peerKey + ", transports=" + transports + "}";
		}
	}
}
