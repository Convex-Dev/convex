package convex.node;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.crypto.AKeyPair;
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
import convex.core.store.AStore;
import convex.core.data.Strings;
import convex.net.IPUtils;
import convex.peer.AConnectionManager;

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
 * <p>Extends {@link AConnectionManager} for shared connection infrastructure.
 *
 * @see DesiredPeer
 */
public class LatticeConnectionManager extends AConnectionManager {

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

	/**
	 * Optional key pair for challenge/response peer verification. If null,
	 * peer verification is skipped and connections are unverified.
	 */
	private volatile AKeyPair keyPair;

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

	/**
	 * Sets the key pair used for challenge/response peer verification.
	 * If set, the manager will attempt to verify peers on connection.
	 *
	 * @param keyPair Key pair for signing challenges, or null to disable verification
	 */
	public void setKeyPair(AKeyPair keyPair) {
		this.keyPair = keyPair;
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

		closeAllConnections();
		log.debug("LatticeConnectionManager closed");
	}

	// ========== Peer Management ==========

	/**
	 * Declares intent to connect to a peer identified by AccountKey. The
	 * connection manager will look up transport information from its known
	 * desired peers (populated via {@link #updateDesiredPeers}) and connect
	 * when transport info becomes available.
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
	 * Declares intent to connect to a peer at a known address.
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
	 * Registers a live connection to a peer.
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
		AKeyPair kp = this.keyPair;
		if (kp != null && convex.getVerifiedPeer() == null) {
			convex.setKeyPair(kp);
			tryVerifyPeer(convex, peerKey);
		}
		connections.put(peerKey, convex);

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
	 *
	 * @param nodesMap The merged OwnerLattice value at {@code [:p2p :nodes]}
	 * @param ownKey This node's own AccountKey (skipped), or null
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
					incoming.failCount = existing.failCount;
					incoming.nextRetryTime = existing.nextRetryTime;
					return incoming;
				}
				return existing;
			});
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
		pruneDeadConnections();

		long now = System.currentTimeMillis();

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
				AKeyPair kp = this.keyPair;
				if (kp != null) {
					convex.setKeyPair(kp);
					tryVerifyPeer(convex, peerKey);
				}
				connections.put(peerKey, convex);
				desired.failCount = 0;
				desired.nextRetryTime = 0;
				log.info("Connected to peer {} at {} (verified={})",
					peerKey, target, convex.getVerifiedPeer() != null);
			} catch (Exception e) {
				desired.failCount++;
				desired.nextRetryTime = now + calculateBackoff(desired.failCount);
				log.debug("Failed to connect to peer {} (attempt {}): {}",
					peerKey, desired.failCount, e.getMessage());
			}
		}
	}

	// ========== Transport Resolution ==========

	static InetSocketAddress resolveTransport(DesiredPeer desired) {
		AVector<AString> transports = desired.transports;
		if (transports == null || transports.isEmpty()) return null;

		for (long i = 0; i < transports.count(); i++) {
			AString uri = (AString) transports.get(i);
			if (uri == null) continue;
			String uriStr = uri.toString();

			if (uriStr.startsWith("tcp://")) {
				uriStr = uriStr.substring(6);
			} else if (uriStr.contains("://")) {
				continue;
			}

			InetSocketAddress sa = IPUtils.toInetSocketAddress(uriStr);
			if (sa != null) return sa;
		}
		return null;
	}

	// ========== Backoff Calculation ==========

	static long calculateBackoff(int failCount) {
		long base = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << Math.min(failCount - 1, 10));
		long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
		return base / 2 + jitter;
	}

	// ========== Verification ==========

	private void tryVerifyPeer(Convex convex, AccountKey peerKey) {
		convex.verifyPeer(peerKey).whenComplete((result, ex) -> {
			if (result != null) {
				log.debug("Verified peer: {} at {}", peerKey, convex.getHostAddress());
			} else if (ex != null) {
				log.debug("Peer verification not available for {}: {}", peerKey, ex.getMessage());
			} else {
				log.debug("Peer verification failed for {}", peerKey);
			}
		});
	}

	// ========== DesiredPeer ==========

	/**
	 * Describes a peer this node wants to maintain a connection to.
	 */
	public static class DesiredPeer {

		public final AccountKey peerKey;
		public final AVector<AString> transports;
		public final AString type;
		public final AString version;
		public final long timestamp;

		int failCount = 0;
		long nextRetryTime = 0;

		private DesiredPeer(AccountKey peerKey, AVector<AString> transports,
				AString type, AString version, long timestamp) {
			this.peerKey = peerKey;
			this.transports = transports;
			this.type = type;
			this.version = version;
			this.timestamp = timestamp;
		}

		@SuppressWarnings("unchecked")
		public static DesiredPeer fromNodeInfo(AccountKey peerKey, AHashMap<Keyword, ACell> nodeInfo) {
			AVector<AString> transports = (AVector<AString>) nodeInfo.get(Keywords.TRANSPORTS);
			AString type = RT.ensureString(nodeInfo.get(Keywords.TYPE));
			AString version = RT.ensureString(nodeInfo.get(Keywords.VERSION));
			CVMLong ts = RT.ensureLong(nodeInfo.get(Keywords.TIMESTAMP));
			long timestamp = (ts != null) ? ts.longValue() : 0L;
			return new DesiredPeer(peerKey, transports, type, version, timestamp);
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		public static DesiredPeer create(AccountKey peerKey, InetSocketAddress address) {
			String uri = "tcp://" + address.getHostString() + ":" + address.getPort();
			AVector<AString> transports = (AVector) Vectors.of(Strings.create(uri));
			return new DesiredPeer(peerKey, transports, null, null, System.currentTimeMillis());
		}

		public static DesiredPeer create(AccountKey peerKey) {
			return new DesiredPeer(peerKey, null, null, null, System.currentTimeMillis());
		}

		@Override
		public String toString() {
			return "DesiredPeer{" + peerKey + ", transports=" + transports + "}";
		}
	}
}
