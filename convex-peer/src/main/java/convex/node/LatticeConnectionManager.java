package convex.node;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.AConvexConnected;
import convex.api.Convex;
import convex.api.ConvexRemote;
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
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
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
 * <p>When a key pair is configured, newly opened connections remain in a limbo set
 * until challenge/response proves the expected remote identity. Limbo connections
 * cannot participate in propagation and have no access to the manager's store. A
 * successful challenge atomically promotes the connection to the active set, grants
 * reverse DATA_REQUEST access and applies the trusted receive limit. Without a key
 * pair, connections are admitted unverified as an explicit local policy choice.
 *
 * <p>Extends {@link AConnectionManager} for shared connection infrastructure.
 *
 * @see DesiredPeer
 */
public class LatticeConnectionManager extends AConnectionManager {

	private static final Logger log = LoggerFactory.getLogger(LatticeConnectionManager.class.getName());

	/** Receive limit before the remote AccountKey is verified. */
	private volatile int untrustedMessageLimit = NodeConfig.DEFAULT_MAX_MESSAGE_SIZE;

	/** Receive limit after challenge/response proves the expected remote AccountKey. */
	private volatile int trustedMessageLimit = NodeConfig.DEFAULT_MAX_TRUSTED_MESSAGE_SIZE;

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

	/** Connections awaiting a successful identity challenge. */
	private final ConcurrentHashMap<AccountKey, PendingConnection> pendingConnections = new ConcurrentHashMap<>();

	/** Futures awaiting the next admitted connection for a desired peer. */
	private final ConcurrentHashMap<AccountKey, CompletableFuture<Convex>> connectionWaiters =
		new ConcurrentHashMap<>();

	/** Wakes the single maintenance thread when desired-peer state changes. */
	private final Semaphore maintenanceSignal = new Semaphore(0);

	/** Serialises admission, removal and replacement across pending and active maps. */
	private final Object connectionLock = new Object();

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

	/**
	 * Configures the two receive tiers for manager-owned outbound connections.
	 * Opening a socket or discovering a signed NodeInfo does not select the trusted
	 * tier. Promotion occurs only after the live endpoint answers a challenge with
	 * the AccountKey under which that Peer was registered.
	 */
	public void setInboundMessageLimits(int untrustedLimit, int trustedLimit) {
		validateMessageLimit(untrustedLimit);
		validateMessageLimit(trustedLimit);
		if (trustedLimit < untrustedLimit) {
			throw new IllegalArgumentException("Trusted message limit must be at least the untrusted limit: "
				+ trustedLimit + " < " + untrustedLimit);
		}
		this.untrustedMessageLimit = untrustedLimit;
		this.trustedMessageLimit = trustedLimit;

		// Apply a configuration change to existing remote connections without racing
		// promotion or accidentally granting a pending connection the trusted tier.
		synchronized (connectionLock) {
			connections.forEach((peerKey, convex) -> {
				AccountKey verifiedKey = convex.getVerifiedPeer();
				configureReceiveLimit(convex, verifiedKey != null && verifiedKey.equals(peerKey));
			});
			pendingConnections.forEach((peerKey, pending) -> configureReceiveLimit(pending.connection, false));
		}
	}

	private static void validateMessageLimit(int limit) {
		if (limit <= 0 || limit > convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("Message limit must be between 1 and "
				+ convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH + ": " + limit);
		}
	}

	private void configureReceiveLimit(Convex convex, boolean trusted) {
		if (convex instanceof ConvexRemote remote) {
			remote.setMaxInboundMessageLength(trusted ? trustedMessageLimit : untrustedMessageLimit);
		}
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
		maintenanceSignal.release();
		log.debug("LatticeConnectionManager started");
	}

	/**
	 * Stops the maintenance thread and closes all connections.
	 */
	@Override
	public void close() {
		running = false;
		maintenanceSignal.release();

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

	@Override
	public void closeAllConnections() {
		List<PendingConnection> pending;
		List<CompletableFuture<Convex>> waiters;
		synchronized (connectionLock) {
			pending = new ArrayList<>(pendingConnections.values());
			pendingConnections.clear();
			waiters = new ArrayList<>(connectionWaiters.values());
			connectionWaiters.clear();
			super.closeAllConnections();
		}
		for (PendingConnection pc : pending) {
			pc.admission.completeExceptionally(new IllegalStateException("Connection manager closed"));
			closeSilently(pc.connection);
		}
		for (CompletableFuture<Convex> waiter : waiters) {
			waiter.completeExceptionally(new IllegalStateException("Connection manager closed"));
		}
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
		maintenanceSignal.release();
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
		maintenanceSignal.release();
		log.debug("Added desired peer: {} at {}", peerKey, address);
	}

	/**
	 * Declares a persistent peer at a known address and returns a future for its
	 * next admitted connection. The maintenance thread is woken immediately; the
	 * returned future spans retries and completes only after any configured identity
	 * challenge succeeds.
	 *
	 * @param peerKey expected AccountKey of the remote node
	 * @param address transport address to connect to
	 * @return future completing with an admitted connection
	 */
	public CompletableFuture<Convex> connectPeer(AccountKey peerKey, InetSocketAddress address) {
		if (peerKey == null || address == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Peer key and address must not be null"));
		}
		addPeer(peerKey,address);
		return whenConnected(peerKey);
	}

	/**
	 * Returns a future for the current or next admitted connection to a desired
	 * peer. This is the deterministic signal for callers that must wait for
	 * challenge/response and avoids polling the maintenance loop.
	 *
	 * @param peerKey desired peer identity
	 * @return future completing with an admitted connection
	 */
	public CompletableFuture<Convex> whenConnected(AccountKey peerKey) {
		if (peerKey == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Peer key must not be null"));
		}
		Convex connected=getConnection(peerKey);
		if (connected!=null) return CompletableFuture.completedFuture(connected);

		CompletableFuture<Convex> waiter=connectionWaiters.computeIfAbsent(
			peerKey,k -> new CompletableFuture<>());
		// Close the admission-before-registration race.
		connected=getConnection(peerKey);
		if (connected!=null && connectionWaiters.remove(peerKey,waiter)) {
			waiter.complete(connected);
		}
		return waiter;
	}

	/**
	 * Registers a live connection to a peer.
	 *
	 * @param peerKey AccountKey of the peer
	 * @param convex Live connection to the peer
	 * @return Future completed when the connection is admitted, or exceptionally
	 *         when identity verification fails
	 */
	public CompletableFuture<Convex> addPeer(AccountKey peerKey, Convex convex) {
		if (peerKey == null || convex == null) {
			log.warn("Attempted to add peer with null key or connection");
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Peer key and connection must not be null"));
		}

		InetSocketAddress addr = convex.getHostAddress();
		if (addr != null) {
			desiredPeers.computeIfAbsent(peerKey, k -> DesiredPeer.create(k, addr));
		} else {
			desiredPeers.computeIfAbsent(peerKey, k -> DesiredPeer.create(k));
		}
		CompletableFuture<Convex> admission=beginAdmission(peerKey, convex);
		maintenanceSignal.release();
		return admission;
	}

	private CompletableFuture<Convex> beginAdmission(AccountKey peerKey, Convex convex) {
		AccountKey verifiedKey = convex.getVerifiedPeer();
		if (verifiedKey != null && !verifiedKey.equals(peerKey)) {
			SecurityException failure = new SecurityException(
				"Connection is verified as " + verifiedKey + ", not expected peer " + peerKey);
			closeSilently(convex);
			return CompletableFuture.failedFuture(failure);
		}

		AKeyPair kp = this.keyPair;
		if (verifiedKey != null || kp == null) {
			return admitConnection(peerKey, convex, verifiedKey != null);
		}

		configureLimbo(convex);
		convex.setKeyPair(kp);
		PendingConnection pending = new PendingConnection(convex);
		PendingConnection replaced;
		synchronized (connectionLock) {
			if (!desiredPeers.containsKey(peerKey)) {
				closeSilently(convex);
				return CompletableFuture.failedFuture(new IllegalStateException("Peer was removed before verification"));
			}
			replaced = pendingConnections.put(peerKey, pending);
		}
		if (replaced != null && replaced.connection != convex) {
			replaced.admission.completeExceptionally(new IllegalStateException("Connection replaced while awaiting verification"));
			closeSilently(replaced.connection);
		}

		log.debug("Holding connection to {} in limbo pending identity verification", peerKey);
		try {
			CompletableFuture<AccountKey> verification = convex.verifyPeer(peerKey);
			if (verification == null) throw new IllegalStateException("Peer verification returned no future");
			verification.whenComplete((result, ex) -> {
				try {
					if (ex != null) {
						failVerification(peerKey, pending, ex);
					} else if (!peerKey.equals(result) || !peerKey.equals(convex.getVerifiedPeer())) {
						failVerification(peerKey, pending,
							new SecurityException("Peer failed identity challenge for " + peerKey));
					} else {
						promoteVerified(peerKey, pending);
					}
				} catch (Exception callbackFailure) {
					failVerification(peerKey, pending, callbackFailure);
				}
			});
		} catch (Exception startFailure) {
			failVerification(peerKey, pending, startFailure);
		}
		return pending.admission;
	}

	private CompletableFuture<Convex> admitConnection(AccountKey peerKey, Convex convex, boolean trusted) {
		Convex replaced;
		PendingConnection pending;
		synchronized (connectionLock) {
			if (!desiredPeers.containsKey(peerKey)) {
				closeSilently(convex);
				return CompletableFuture.failedFuture(new IllegalStateException("Peer was removed before admission"));
			}
			configureStoreAccess(convex);
			configureReceiveLimit(convex, trusted);
			replaced = connections.put(peerKey, convex);
			pending = pendingConnections.remove(peerKey);
		}
		if (replaced != null && replaced != convex) closeSilently(replaced);
		if (pending != null && pending.connection != convex) {
			pending.admission.completeExceptionally(new IllegalStateException("Connection replaced by admitted peer"));
			closeSilently(pending.connection);
		}
		resetFailure(peerKey);
		completeConnectionWaiter(peerKey,convex);
		log.debug("Admitted {} connection to peer {} at {}", trusted ? "verified" : "unverified",
			peerKey, convex.getHostAddress());
		return CompletableFuture.completedFuture(convex);
	}

	private void completeConnectionWaiter(AccountKey peerKey, Convex convex) {
		CompletableFuture<Convex> waiter=connectionWaiters.remove(peerKey);
		if (waiter!=null) waiter.complete(convex);
	}

	/**
	 * Binds both local acquisition and remote data serving to this manager's store.
	 * The explicit request handler is important: setting a client's local store alone
	 * must never grant the remote endpoint read access to it.
	 */
	private void configureStoreAccess(Convex convex) {
		convex.setStore(store);
		if (convex instanceof AConvexConnected connected) {
			connected.setDataRequestHandler(this::handleDataRequest);
		}
	}

	/** Revokes manager-owned capabilities while a connection awaits admission. */
	private void configureLimbo(Convex convex) {
		configureReceiveLimit(convex, false);
		if (convex instanceof AConvexConnected connected) {
			connected.setDataRequestHandler(null);
		}
	}

	/**
	 * Hands store access off the network receive thread. Announce completes before a
	 * propagator broadcasts, so every cell referenced by that update is already
	 * serviceable from this exact store.
	 */
	private void handleDataRequest(Message message) {
		Thread.ofVirtual().name("Lattice data request").start(() -> {
			try {
				Message response = message.makeDataResponse(store);
				if (!message.returnMessage(response)) {
					log.debug("Unable to return lattice data: peer send buffer is full");
				}
			} catch (BadFormatException e) {
				log.warn("Ignoring malformed lattice DATA_REQUEST: {}", e.getMessage());
			} catch (Exception e) {
				log.warn("Unable to serve lattice DATA_REQUEST", e);
			}
		});
	}

	/**
	 * Removes a peer from both the desired set and active connections.
	 *
	 * @param peerKey AccountKey of the peer to remove
	 */
	public void removePeer(AccountKey peerKey) {
		if (peerKey == null) return;
		Convex removed;
		PendingConnection pending;
		CompletableFuture<Convex> waiter;
		synchronized (connectionLock) {
			desiredPeers.remove(peerKey);
			removed = connections.remove(peerKey);
			pending = pendingConnections.remove(peerKey);
			waiter = connectionWaiters.remove(peerKey);
		}
		if (removed != null) {
			closeSilently(removed);
			log.debug("Removed peer: {}", peerKey);
		}
		if (pending != null) {
			pending.admission.completeExceptionally(new IllegalStateException("Peer removed while awaiting verification"));
			closeSilently(pending.connection);
			log.debug("Removed pending peer: {}", peerKey);
		}
		if (waiter != null) {
			waiter.completeExceptionally(new IllegalStateException("Peer removed before connection"));
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
		AtomicBoolean changed=new AtomicBoolean(false);

		for (Map.Entry<ACell, SignedData<ACell>> entry : nodesMap.entrySet()) {
			try {
				AccountKey peerKey = RT.ensureAccountKey(entry.getKey());
				if (peerKey == null || peerKey.equals(ownKey)) continue;

				SignedData<ACell> signed = entry.getValue();
				if (signed == null || !(signed.getValue() instanceof AHashMap<?,?> rawInfo)) continue;

				AHashMap<Keyword, ACell> nodeInfo = (AHashMap<Keyword, ACell>) rawInfo;
				DesiredPeer updated = DesiredPeer.fromNodeInfo(peerKey, nodeInfo);
				if (updated == null) continue;
				desiredPeers.compute(peerKey, (key, existing) -> {
					if (existing == null) {
						changed.set(true);
						return updated;
					}
					if (updated.timestamp > existing.timestamp) {
						updated.failCount = existing.failCount;
						updated.nextRetryTime = existing.nextRetryTime;
						changed.set(true);
						return updated;
					}
					return existing;
				});
			} catch (RuntimeException e) {
				// One owner's malformed record must not suppress valid sibling entries.
				log.debug("Ignoring malformed NodeInfo entry: {}",e.getMessage());
			}
		}
		if (changed.get()) maintenanceSignal.release();
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

	/**
	 * Returns the number of open connections awaiting identity verification.
	 * These connections are not visible to propagation and cannot serve store data.
	 */
	public int getPendingConnectionCount() {
		return pendingConnections.size();
	}

	/** Returns true when a peer connection is held in verification limbo. */
	public boolean isVerificationPending(AccountKey peerKey) {
		return peerKey != null && pendingConnections.containsKey(peerKey);
	}

	// ========== Maintenance Loop ==========

	private void maintenanceLoop() {
		while (running) {
			try {
				maintenanceSignal.tryAcquire(MAINTENANCE_INTERVAL,TimeUnit.MILLISECONDS);
				if (!running) break;
				maintenanceSignal.drainPermits();
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
		pruneDeadPendingConnections();

		long now = System.currentTimeMillis();

		for (Map.Entry<AccountKey, DesiredPeer> entry : desiredPeers.entrySet()) {
			AccountKey peerKey = entry.getKey();
			DesiredPeer desired = entry.getValue();

			synchronized (connectionLock) {
				if (connections.containsKey(peerKey) || pendingConnections.containsKey(peerKey)) continue;
			}
			if (now < desired.nextRetryTime) continue;

			InetSocketAddress target = resolveTransport(desired);
			if (target == null) continue;

			try {
				// Install the untrusted cap as part of connection construction. Applying it
				// after connect would leave a race in which the remote endpoint could send a
				// protocol-sized frame before its identity challenge has even started.
				Convex convex = ConvexRemote.connect(target, untrustedMessageLimit);
				synchronized (connectionLock) {
					if (desiredPeers.get(peerKey) != desired) {
						closeSilently(convex);
						continue;
					}
				}
				beginAdmission(peerKey, convex);
				log.debug("Opened connection to peer {} at {}; awaiting admission", peerKey, target);
			} catch (Exception e) {
				recordFailure(desired, now);
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

	private void promoteVerified(AccountKey peerKey, PendingConnection pending) {
		Convex replaced;
		synchronized (connectionLock) {
			if (pendingConnections.get(peerKey) != pending || !desiredPeers.containsKey(peerKey)
					|| !pending.connection.isConnected()) {
				failVerification(peerKey, pending,
					new IllegalStateException("Verified connection is no longer eligible for admission"));
				return;
			}
			configureStoreAccess(pending.connection);
			configureReceiveLimit(pending.connection, true);
			replaced = connections.put(peerKey, pending.connection);
			pendingConnections.remove(peerKey, pending);
		}
		if (replaced != null && replaced != pending.connection) closeSilently(replaced);
		resetFailure(peerKey);
		completeConnectionWaiter(peerKey,pending.connection);
		pending.admission.complete(pending.connection);
		log.info("Verified and admitted peer {} at {}", peerKey, pending.connection.getHostAddress());
	}

	private void failVerification(AccountKey peerKey, PendingConnection pending, Throwable failure) {
		boolean removed;
		DesiredPeer desired;
		synchronized (connectionLock) {
			removed = pendingConnections.remove(peerKey, pending);
			desired = removed ? desiredPeers.get(peerKey) : null;
		}
		if (!removed) return;
		pending.admission.completeExceptionally(failure);
		closeSilently(pending.connection);
		if (desired != null) recordFailure(desired, System.currentTimeMillis());
		log.debug("Rejected peer {} after failed identity challenge: {}", peerKey, failure.getMessage());
	}

	private void pruneDeadPendingConnections() {
		for (Map.Entry<AccountKey, PendingConnection> entry : pendingConnections.entrySet()) {
			PendingConnection pending = entry.getValue();
			if (!pending.connection.isConnected()) {
				failVerification(entry.getKey(), pending,
					new IllegalStateException("Connection closed while awaiting identity verification"));
			}
		}
	}

	private void resetFailure(AccountKey peerKey) {
		DesiredPeer desired = desiredPeers.get(peerKey);
		if (desired == null) return;
		synchronized (desired) {
			desired.failCount = 0;
			desired.nextRetryTime = 0;
		}
	}

	private static void recordFailure(DesiredPeer desired, long now) {
		synchronized (desired) {
			desired.failCount++;
			desired.nextRetryTime = now + calculateBackoff(desired.failCount);
		}
	}

	private static final class PendingConnection {
		final Convex connection;
		final CompletableFuture<Convex> admission = new CompletableFuture<>();

		PendingConnection(Convex connection) {
			this.connection = connection;
		}
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

		volatile int failCount = 0;
		volatile long nextRetryTime = 0;

		private DesiredPeer(AccountKey peerKey, AVector<AString> transports,
				AString type, AString version, long timestamp) {
			this.peerKey = peerKey;
			this.transports = transports;
			this.type = type;
			this.version = version;
			this.timestamp = timestamp;
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		public static DesiredPeer fromNodeInfo(AccountKey peerKey, AHashMap<Keyword, ACell> nodeInfo) {
			AVector<?> rawTransports = RT.ensureVector(nodeInfo.get(Keywords.TRANSPORTS));
			if (rawTransports == null) return null;
			for (long i=0; i<rawTransports.count(); i++) {
				if (RT.ensureString(rawTransports.get(i)) == null) return null;
			}
			AVector<AString> transports = (AVector) rawTransports;
			AString type = RT.ensureString(nodeInfo.get(Keywords.TYPE));
			AString version = RT.ensureString(nodeInfo.get(Keywords.VERSION));
			CVMLong ts = RT.ensureLong(nodeInfo.get(Keywords.TIMESTAMP));
			if (ts == null) return null;
			long timestamp = ts.longValue();
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
