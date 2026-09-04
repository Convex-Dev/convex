package convex.node;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.AConvexConnected;
import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Vectors;
import convex.core.store.AStore;
import convex.core.data.Strings;
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
import convex.core.message.AConnection;
import convex.core.util.Utils;
import convex.net.IPUtils;
import convex.peer.AConnectionManager;

/**
 * Maintains bounded connection intent and outbound routes for one
 * {@link LatticePropagator}.
 *
 * <p>This is a transport component. It does not read a lattice cursor or know
 * the schema of any discovery application. A higher layer may translate its
 * own validated discovery records into {@link #updateDiscoveredPeer} calls.</p>
 *
 * <p>The manager owns four separate kinds of state. They must not be treated as
 * interchangeable:</p>
 *
 * <ol>
 *   <li>{@link DesiredPeer} entries are bounded connection <em>intent</em>, not
 *       live sockets or trust assertions.</li>
 *   <li>Pending connections are manager-owned outbound sockets held in identity
 *       verification limbo.</li>
 *   <li>Active connections are admitted manager-owned outbound clients.</li>
 *   <li>Upgraded inbound routes are authenticated sockets physically owned by
 *       an application transport and assigned endpoint; this manager owns only their
 *       outbound capability.</li>
 * </ol>
 *
 * <p>The {@code addPeer} overloads all create or retain desired-peer intent.
 * {@link #addPeer(AccountKey)} waits for later discovery transport metadata,
 * {@link #addPeer(AccountKey, InetSocketAddress)} supplies an operator transport,
 * and {@link #addPeer(AccountKey, Convex)} begins admission of an already opened
 * manager-owned client. {@link #connectPeer(AccountKey, InetSocketAddress)} is the
 * deterministic connect-and-wait convenience API.</p>
 *
 * <p>When a key pair is configured, newly opened connections remain in a limbo set
 * until challenge/response proves the expected remote identity. Limbo connections
 * cannot participate in propagation and have no access to the manager's store. A
 * successful challenge atomically promotes the connection to the active set, grants
 * reverse DATA_REQUEST access and applies the trusted receive limit. Without a key
 * pair, connections are admitted unverified as an explicit zero-trust transport
 * policy; they do not appear in {@link #getAuthenticatedRouteKeys()}.</p>
 *
 * <p>Node-key admission protects routing and store-serving capabilities. It does
 * not authorise application values: signed owner verification remains part of the
 * lattice merge in accordance with CAD038.</p>
 *
 * <p><b>Threading.</b> Public calls, asynchronous challenge completions and the
 * maintenance worker may race. Multi-map admission/removal transitions are
 * serialised by one private lock; external futures, hooks and socket closes are
 * completed outside that lock.</p>
 *
 * <p>Extends {@link AConnectionManager} for shared connection infrastructure.</p>
 *
 * @see DesiredPeer
 */
public class LatticeConnectionManager extends AConnectionManager {

	private static final Logger log = LoggerFactory.getLogger(LatticeConnectionManager.class.getName());

	/** Receive limit before the remote AccountKey is verified. */
	private volatile int untrustedMessageLimit = LatticePropagatorConfig.DEFAULT_MAX_MESSAGE_SIZE;

	/** Receive limit after challenge/response proves the expected remote AccountKey. */
	private volatile int trustedMessageLimit = LatticePropagatorConfig.DEFAULT_MAX_TRUSTED_MESSAGE_SIZE;

	/** Hard cap for operator- and discovery-supplied desired peer identities. */
	private volatile int maxDesiredPeers = LatticePropagatorConfig.DEFAULT_MAX_DESIRED_PEERS;

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

	/** Futures awaiting the next admitted manager-owned outbound connection. */
	private final ConcurrentHashMap<AccountKey, CompletableFuture<Convex>> connectionWaiters =
		new ConcurrentHashMap<>();

	/**
	 * Physically inbound connections explicitly upgraded to authenticated outbound
	 * propagation routes. They remain owned by the listener/endpoint lifecycle and
	 * are never closed here.
	 */
	private final ConcurrentHashMap<AccountKey, AConnection> upgradedInboundRoutes =
		new ConcurrentHashMap<>();

	/** Futures awaiting the next authenticated inbound-to-outbound route upgrade. */
	private final ConcurrentHashMap<AccountKey, CompletableFuture<AConnection>> upgradedRouteWaiters =
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

	/** Delivery hook for reverse messages arriving on admitted outbound clients. */
	private volatile BiConsumer<Convex, Message> peerMessageHandler;

	/** Lifecycle hook for application protocols to initialise every admitted peer. */
	private volatile BiConsumer<AccountKey,Convex> peerAdmissionHandler;

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
	 * If set, subsequently admitted connections must verify the expected remote
	 * node key. Changing this value is not retroactive: it neither upgrades nor
	 * revokes clients which have already completed admission.
	 *
	 * @param keyPair Key pair for signing challenges, or null to disable verification
	 */
	public void setKeyPair(AKeyPair keyPair) {
		this.keyPair = keyPair;
	}

	/**
	 * Configures the hard desired-peer cap. Existing entries are not truncated.
	 *
	 * @param maxDesiredPeers maximum retained peer identities
	 */
	public void setMaxDesiredPeers(int maxDesiredPeers) {
		if (maxDesiredPeers <= 0) throw new IllegalArgumentException(
			"Maximum desired peers must be positive");
		synchronized (connectionLock) {
			if (desiredPeers.size() > maxDesiredPeers) throw new IllegalStateException(
				"Desired peer count already exceeds requested limit");
			this.maxDesiredPeers=maxDesiredPeers;
		}
	}

	/**
	 * Returns the configured hard desired-peer cap.
	 *
	 * @return maximum retained peer identities
	 */
	public int getMaxDesiredPeers() {
		return maxDesiredPeers;
	}

	/**
	 * Sets the owning propagation endpoint's delivery hook for unsolicited messages
	 * arriving on an admitted manager-owned outbound client. The handler is installed
	 * only at admission; verification-limbo connections cannot reach it.
	 *
	 * @param handler handler receiving the owning client and message, or null
	 */
	public void setPeerMessageHandler(BiConsumer<Convex, Message> handler) {
		this.peerMessageHandler = handler;
	}

	/**
	 * Sets a non-blocking hook invoked after each manager-owned peer is admitted.
	 * Handler failures are logged and do not undo admission.
	 *
	 * @param handler admission hook, or {@code null} for none
	 */
	public void setPeerAdmissionHandler(BiConsumer<AccountKey,Convex> handler) {
		this.peerAdmissionHandler=handler;
	}

	/**
	 * Configures the two receive tiers for manager-owned outbound connections.
	 * Opening a socket or discovering routing metadata does not select the trusted
	 * tier. Promotion occurs only after the live endpoint answers a challenge with
	 * the AccountKey under which that Peer was registered.
	 *
	 * @param untrustedLimit maximum encoded bytes before verification
	 * @param trustedLimit maximum encoded bytes after verification
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

	/**
	 * Revokes every logical route and closes only manager-owned physical clients.
	 * Upgraded inbound sockets remain owned by the propagator listener; clearing their
	 * map entries removes this manager's outbound capability without closing them.
	 */
	@Override
	public void closeAllConnections() {
		List<PendingConnection> pending;
		List<CompletableFuture<Convex>> waiters;
		List<CompletableFuture<AConnection>> routeWaiters;
		synchronized (connectionLock) {
			pending = new ArrayList<>(pendingConnections.values());
			pendingConnections.clear();
			waiters = new ArrayList<>(connectionWaiters.values());
			connectionWaiters.clear();
			routeWaiters = new ArrayList<>(upgradedRouteWaiters.values());
			upgradedRouteWaiters.clear();
			// The listener/endpoint owns these physical inbound connections. Closing
			// this manager revokes only their logical outbound capability.
			upgradedInboundRoutes.clear();
			super.closeAllConnections();
		}
		for (PendingConnection pc : pending) {
			pc.admission.completeExceptionally(new IllegalStateException("Connection manager closed"));
			closeSilently(pc.connection);
		}
		for (CompletableFuture<Convex> waiter : waiters) {
			waiter.completeExceptionally(new IllegalStateException("Connection manager closed"));
		}
		for (CompletableFuture<AConnection> waiter : routeWaiters) {
			waiter.completeExceptionally(new IllegalStateException("Connection manager closed"));
		}
	}

	// ========== Desired Peer Intent ==========

	/**
	 * Declares intent to connect to a peer identified by AccountKey. The
	 * connection manager will look up transport information from its known
	 * desired peers (which a discovery adapter may enrich via
	 * {@link #updateDiscoveredPeer(AccountKey, AVector, long)}) and connect
	 * when transport info becomes available.
	 *
	 * @param peerKey key of the peer to connect to
	 */
	public void addPeer(AccountKey peerKey) {
		if (peerKey == null) {
			log.warn("Attempted to add peer with null key");
			return;
		}
		if (!registerDesiredPeerIntent(peerKey,DesiredPeer.create(peerKey),false)) {
			log.warn("Ignoring desired peer {}: limit of {} reached",peerKey,maxDesiredPeers);
			return;
		}
		maintenanceSignal.release();
		log.debug("Added desired peer: {}", peerKey);
	}

	/**
	 * Declares intent to connect to a peer at a known address.
	 *
	 * @param peerKey key of the peer
	 * @param address network address to connect to
	 */
	public void addPeer(AccountKey peerKey, InetSocketAddress address) {
		if (peerKey == null || address == null) {
			log.warn("Attempted to add peer with null key or address");
			return;
		}
		if (!registerDesiredPeerIntent(peerKey,DesiredPeer.create(peerKey,address),true)) {
			log.warn("Ignoring desired peer {}: limit of {} reached",peerKey,maxDesiredPeers);
			return;
		}
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
		if (!registerDesiredPeerIntent(peerKey,DesiredPeer.create(peerKey,address),true)) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Desired peer limit of "+maxDesiredPeers+" reached"));
		}
		maintenanceSignal.release();
		return whenConnected(peerKey);
	}

	/** Adds bounded connection intent, optionally replacing only its dial metadata. */
	private boolean registerDesiredPeerIntent(
			AccountKey peerKey,DesiredPeer desired,boolean replace) {
		synchronized (connectionLock) {
			DesiredPeer existing=desiredPeers.get(peerKey);
			if (existing!=null) {
				if (replace) {
					// An operator-supplied address replaces only the dial target. Keep
					// discovery revision metadata so stale discovery cannot overwrite it.
					if (existing.discovered) desired=existing.withTransports(desired.transports);
					desiredPeers.put(peerKey,desired);
				}
				return true;
			}
			if (desiredPeers.size()>=maxDesiredPeers) return false;
			desiredPeers.put(peerKey,desired);
			return true;
		}
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

	// ========== Authenticated Inbound Route Upgrade ==========

	/**
	 * Explicitly upgrades a physically inbound connection into an authenticated
	 * outbound propagation route.
	 *
	 * <p>An operator-assigned inbound connection is not sufficient. The connection
	 * must already carry a trusted key established by live challenge/response, and
	 * that key must identify a desired peer learned from discovery or explicit
	 * operator configuration. This method never authenticates or assigns trust
	 * itself.</p>
	 *
	 * @param connection authenticated inbound physical connection
	 * @return the upgraded connection
	 * @throws IllegalArgumentException if {@code connection} is {@code null}
	 * @throws IllegalStateException if the connection is closed
	 * @throws SecurityException if authentication or desired-peer admission is absent
	 */
	public AConnection upgradeInboundConnection(AConnection connection) {
		if (connection == null) throw new IllegalArgumentException("Connection must not be null");
		AccountKey peerKey = connection.getTrustedKey();
		if (peerKey == null) {
			throw new SecurityException("Untrusted inbound connection cannot become an outbound route");
		}
		if (connection.isClosed()) {
			throw new IllegalStateException("Closed inbound connection cannot become an outbound route");
		}
		if (!desiredPeers.containsKey(peerKey)) {
			throw new SecurityException("Authenticated peer has no admitted node identity: " + peerKey);
		}

		upgradedInboundRoutes.put(peerKey, connection);
		CompletableFuture<AConnection> waiter = upgradedRouteWaiters.remove(peerKey);
		if (waiter != null) waiter.complete(connection);
		maintenanceSignal.release();
		log.info("Upgraded authenticated inbound connection to outbound propagation route for {}", peerKey);
		return connection;
	}

	/**
	 * Returns the live upgraded inbound route for a peer.
	 *
	 * @param peerKey remote node key
	 * @return upgraded route, or {@code null} if none is live
	 */
	public AConnection getUpgradedInboundConnection(AccountKey peerKey) {
		if (peerKey == null) return null;
		AConnection connection = upgradedInboundRoutes.get(peerKey);
		if (connection == null) return null;
		if (connection.isClosed() || !peerKey.equals(connection.getTrustedKey())) {
			upgradedInboundRoutes.remove(peerKey, connection);
			return null;
		}
		return connection;
	}

	/**
	 * Returns whether an inbound connection is live and promoted after authentication.
	 *
	 * @param peerKey remote node key
	 * @return {@code true} if a promoted route is live
	 */
	public boolean hasUpgradedInboundConnection(AccountKey peerKey) {
		return getUpgradedInboundConnection(peerKey) != null;
	}

	/**
	 * Waits for the explicit authentication-driven upgrade of an inbound connection.
	 * Unlike {@link #whenConnected(AccountKey)}, this never completes for an ordinary
	 * untrusted inbound socket or for a manager-owned outbound client.
	 *
	 * @param peerKey remote node key
	 * @return future completing with the upgraded route
	 */
	public CompletableFuture<AConnection> whenInboundConnectionUpgraded(AccountKey peerKey) {
		if (peerKey == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Peer key must not be null"));
		}
		AConnection upgraded = getUpgradedInboundConnection(peerKey);
		if (upgraded != null) return CompletableFuture.completedFuture(upgraded);

		CompletableFuture<AConnection> waiter = upgradedRouteWaiters.computeIfAbsent(
			peerKey, key -> new CompletableFuture<>());
		upgraded = getUpgradedInboundConnection(peerKey);
		if (upgraded != null && upgradedRouteWaiters.remove(peerKey, waiter)) {
			waiter.complete(upgraded);
		}
		return waiter;
	}

	/**
	 * Revokes an upgraded route without closing its listener-owned connection.
	 *
	 * @param connection listener-owned connection whose route is revoked
	 */
	public void removeUpgradedInboundConnection(AConnection connection) {
		if (connection == null) return;
		upgradedInboundRoutes.entrySet().removeIf(entry -> entry.getValue() == connection);
		maintenanceSignal.release();
	}

	// ========== Manager-owned Outbound Admission ==========

	/**
	 * Submits an existing live client for outbound admission.
	 *
	 * @param peerKey expected remote node key
	 * @param convex live client to admit
	 * @return future completed when the connection is admitted, or exceptionally
	 *         when identity verification fails
	 */
	public CompletableFuture<Convex> addPeer(AccountKey peerKey, Convex convex) {
		if (peerKey == null || convex == null) {
			log.warn("Attempted to add peer with null key or connection");
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Peer key and connection must not be null"));
		}

		InetSocketAddress addr = convex.getHostAddress();
		DesiredPeer desired=(addr!=null)
			? DesiredPeer.create(peerKey,addr) : DesiredPeer.create(peerKey);
		if (!registerDesiredPeerIntent(peerKey,desired,false)) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Desired peer limit of "+maxDesiredPeers+" reached"));
		}
		CompletableFuture<Convex> admission=beginOutboundAdmission(peerKey, convex);
		maintenanceSignal.release();
		return admission;
	}

	/** Moves an opened manager-owned client through limbo into the active map. */
	private CompletableFuture<Convex> beginOutboundAdmission(AccountKey peerKey, Convex convex) {
		AccountKey verifiedKey = convex.getVerifiedPeer();
		if (verifiedKey != null && !verifiedKey.equals(peerKey)) {
			SecurityException failure = new SecurityException(
				"Connection is verified as " + verifiedKey + ", not expected peer " + peerKey);
			closeSilently(convex);
			return CompletableFuture.failedFuture(failure);
		}

		AKeyPair kp = this.keyPair;
		if (verifiedKey != null || kp == null) {
			return installAdmittedOutbound(peerKey, convex, verifiedKey != null);
		}

		restrictOutboundToLimbo(convex);
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
			CompletableFuture<AccountKey> verification = convex.verifyPeer(
				peerKey, Message.LATTICE_PEER_CHALLENGE_CONTEXT);
			if (verification == null) throw new IllegalStateException("Peer verification returned no future");
			verification.whenComplete((result, ex) -> {
				try {
					if (ex != null) {
						rejectPendingAdmission(peerKey, pending, ex);
					} else if (result==null || !peerKey.equals(result)
							|| convex.getVerifiedPeer()==null
							|| !peerKey.equals(convex.getVerifiedPeer())) {
						rejectPendingAdmission(peerKey, pending,
							new SecurityException("Peer failed identity challenge for " + peerKey));
					} else {
						completeVerifiedAdmission(peerKey, pending);
					}
				} catch (Exception callbackFailure) {
					rejectPendingAdmission(peerKey, pending, callbackFailure);
				}
			});
		} catch (Exception startFailure) {
			rejectPendingAdmission(peerKey, pending, startFailure);
		}
		return pending.admission;
	}

	/** Installs a client whose admission policy requires no further challenge. */
	private CompletableFuture<Convex> installAdmittedOutbound(
			AccountKey peerKey,Convex convex,boolean trusted) {
		Convex replaced;
		PendingConnection pending;
		synchronized (connectionLock) {
			if (!desiredPeers.containsKey(peerKey)) {
				closeSilently(convex);
				return CompletableFuture.failedFuture(new IllegalStateException("Peer was removed before admission"));
			}
			grantOutboundCapabilities(convex);
			configureReceiveLimit(convex, trusted);
			replaced = connections.put(peerKey, convex);
			pending = pendingConnections.remove(peerKey);
		}
		if (replaced != null && replaced != convex) closeSilently(replaced);
		if (pending != null && pending.connection != convex) {
			pending.admission.completeExceptionally(new IllegalStateException("Connection replaced by admitted peer"));
			closeSilently(pending.connection);
		}
		resetRetryBackoff(peerKey);
		completePeerWaiter(peerKey,convex);
		notifyPeerAdmitted(peerKey,convex);
		log.debug("Admitted {} connection to peer {} at {}", trusted ? "verified" : "unverified",
			peerKey, convex.getHostAddress());
		return CompletableFuture.completedFuture(convex);
	}

	/** Completes the one-shot signal for the next admitted outbound client. */
	private void completePeerWaiter(AccountKey peerKey, Convex convex) {
		CompletableFuture<Convex> waiter=connectionWaiters.remove(peerKey);
		if (waiter!=null) waiter.complete(convex);
	}

	/** Runs the optional post-admission hook without allowing it to undo admission. */
	private void notifyPeerAdmitted(AccountKey peerKey,Convex convex) {
		BiConsumer<AccountKey,Convex> handler=peerAdmissionHandler;
		if (handler==null) return;
		try {
			handler.accept(peerKey,convex);
		} catch (RuntimeException e) {
			log.warn("Peer admission handler failed for {}",peerKey,e);
		}
	}

	/**
	 * Binds both local acquisition and remote data serving to this manager's store.
	 * The explicit request handler is important: setting a client's local store alone
	 * must never grant the remote endpoint read access to it.
	 */
	private void grantOutboundCapabilities(Convex convex) {
		convex.setStore(store);
		if (convex instanceof AConvexConnected connected) {
			connected.setDataRequestHandler(this::handleDataRequest);
			connected.setUnsolicitedMessageHandler(message -> {
				BiConsumer<Convex, Message> handler = peerMessageHandler;
				if (handler != null) handler.accept(convex, message);
			});
		}
	}

	/** Revokes manager-owned capabilities while a connection awaits admission. */
	private void restrictOutboundToLimbo(Convex convex) {
		configureReceiveLimit(convex, false);
		if (convex instanceof AConvexConnected connected) {
			connected.setDataRequestHandler(null);
			connected.setUnsolicitedMessageHandler(null);
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
	 * @param peerKey key of the peer to remove
	 */
	public void removePeer(AccountKey peerKey) {
		if (peerKey == null) return;
		Convex removed;
		PendingConnection pending;
		CompletableFuture<Convex> waiter;
		CompletableFuture<AConnection> routeWaiter;
		synchronized (connectionLock) {
			desiredPeers.remove(peerKey);
			removed = connections.remove(peerKey);
			pending = pendingConnections.remove(peerKey);
			waiter = connectionWaiters.remove(peerKey);
			upgradedInboundRoutes.remove(peerKey);
			routeWaiter = upgradedRouteWaiters.remove(peerKey);
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
		if (routeWaiter != null) {
			routeWaiter.completeExceptionally(new IllegalStateException("Peer removed before route upgrade"));
		}
	}

	// ========== Desired Peer Management ==========

	/**
	 * Returns a defensive copy of the desired-peer map.
	 *
	 * @return map from remote node key to connection intent
	 */
	public Map<AccountKey, DesiredPeer> getDesiredPeers() {
		return new HashMap<>(desiredPeers);
	}

	/** Returns whether discovery or explicit operator configuration admits this peer. */
	boolean isDesiredPeer(AccountKey peerKey) {
		return peerKey != null && desiredPeers.containsKey(peerKey);
	}

	/**
	 * Adds or refreshes one peer translated by an external discovery adapter.
	 *
	 * <p>The caller owns discovery validation and schema interpretation. This
	 * transport layer accepts only the expected node key, ordered transport URIs
	 * and a monotonically increasing revision. The operation is additive/update-only
	 * and merely wakes maintenance; it never opens a socket on the caller's thread.</p>
	 *
	 * @param peerKey expected remote node key
	 * @param transports ordered transport URIs, possibly empty but not null
	 * @param revision discovery-source revision used to reject stale replacement
	 * @return {@code true} if the peer is present after the operation; {@code false}
	 *         if the cap rejected a new peer or the arguments are invalid
	 */
	public boolean updateDiscoveredPeer(
			AccountKey peerKey,AVector<AString> transports,long revision) {
		if (peerKey==null || transports==null) return false;
		for (long i=0; i<transports.count(); i++) {
			if (transports.get(i)==null) return false;
		}
		boolean changed=false;
		synchronized (connectionLock) {
			DesiredPeer existing=desiredPeers.get(peerKey);
			if (existing==null) {
				if (desiredPeers.size()>=maxDesiredPeers) return false;
				desiredPeers.put(peerKey,DesiredPeer.discovered(peerKey,transports,revision));
				changed=true;
			} else if (!existing.discovered || revision>existing.revision) {
				DesiredPeer updated=DesiredPeer.discovered(peerKey,transports,revision);
				updated.copyRetryState(existing);
				desiredPeers.put(peerKey,updated);
				changed=true;
			}
		}
		if (changed) maintenanceSignal.release();
		return true;
	}

	// ========== Accessors ==========

	/**
	 * Returns the store exposed for admitted peer data resolution.
	 *
	 * @return serving store
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Returns whether the maintenance thread is running.
	 *
	 * @return {@code true} if running
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Returns the number of open connections awaiting identity verification.
	 * These connections are not visible to propagation and cannot serve store data.
	 *
	 * @return pending connection count
	 */
	public int getPendingConnectionCount() {
		return pendingConnections.size();
	}

	/**
	 * Returns whether a peer connection is held in verification limbo.
	 *
	 * @param peerKey expected remote node key
	 * @return {@code true} if verification is pending
	 */
	public boolean isVerificationPending(AccountKey peerKey) {
		return peerKey != null && pendingConnections.containsKey(peerKey);
	}

	/**
	 * Returns whether this manager has any outbound propagation route.
	 *
	 * @return {@code true} if at least one route is live
	 */
	public boolean hasPropagationRoutes() {
		pruneDeadConnections();
		pruneDeadUpgradedInboundConnections();
		if (!connections.isEmpty()) return true;
		return !upgradedInboundRoutes.isEmpty();
	}

	/**
	 * Returns the number of peer identities reachable for outbound propagation.
	 * A peer with both route forms is counted once.
	 *
	 * @return live propagation route count
	 */
	public int getPropagationRouteCount() {
		pruneDeadConnections();
		pruneDeadUpgradedInboundConnections();
		java.util.HashSet<AccountKey> keys = new java.util.HashSet<>(connections.keySet());
		keys.addAll(upgradedInboundRoutes.keySet());
		return keys.size();
	}

	/**
	 * Returns the node keys currently reachable over routes that have proved the
	 * remote key by challenge/response. This excludes configured, pending and
	 * merely operator-assigned connections.
	 *
	 * @return defensive set of authenticated route identities
	 */
	public Set<AccountKey> getAuthenticatedRouteKeys() {
		pruneDeadConnections();
		pruneDeadUpgradedInboundConnections();
		HashSet<AccountKey> keys=new HashSet<>();
		for (Map.Entry<AccountKey,Convex> entry:connections.entrySet()) {
			AccountKey key=entry.getKey();
			Convex peer=entry.getValue();
			if (peer!=null && peer.isConnected() && key.equals(peer.getVerifiedPeer())) {
				keys.add(key);
			}
		}
		for (Map.Entry<AccountKey,AConnection> entry:upgradedInboundRoutes.entrySet()) {
			AccountKey key=entry.getKey();
			AConnection route=getUpgradedInboundConnection(key);
			if (route!=null) keys.add(key);
		}
		return keys;
	}

	/**
	 * Non-blockingly sends one message to a specifically authenticated node route.
	 * A manager-owned outbound connection is eligible only when its verified key
	 * equals the requested peer; otherwise an independently upgraded inbound route
	 * is tried.
	 *
	 * @param peerKey destination node key
	 * @param message complete message to enqueue
	 * @return true if an authenticated route accepted the message
	 */
	public boolean trySendAuthenticated(AccountKey peerKey,Message message) {
		if (peerKey==null || message==null) return false;
		Convex peer=getConnection(peerKey);
		if (peer!=null && peerKey.equals(peer.getVerifiedPeer()) && peer.trySend(message)) {
			return true;
		}
		AConnection route=getUpgradedInboundConnection(peerKey);
		return route!=null && route.trySendMessage(message);
	}

	/** Broadcasts once per remote identity across both physical route forms. */
	@Override
	public int broadcast(Message message, boolean skipBusy) {
		int accepted = super.broadcast(message, skipBusy);
		for (AConnection route : getSupplementalInboundRoutes()) {
			if (skipBusy && route.isOutboundBusy()) continue;
			if (route.trySendMessage(message)) accepted++;
		}
		return accepted;
	}

	/** Broadcasts a priority message once per remote identity. */
	@Override
	public int broadcastPriority(Message message) {
		int accepted = super.broadcastPriority(message);
		for (AConnection route : getSupplementalInboundRoutes()) {
			if (route.trySendPriorityMessage(message)) accepted++;
		}
		return accepted;
	}

	/** Sends a delta sequence with root fallback once per remote identity. */
	@Override
	public BroadcastResult broadcastSequence(List<Message> messages, Message fallback, boolean skipBusy) {
		BroadcastResult outbound = super.broadcastSequence(messages, fallback, skipBusy);
		ArrayList<AConnection> routes = new ArrayList<>(getSupplementalInboundRoutes());
		Utils.shuffle(routes);

		int attempted = outbound.peers();
		int complete = outbound.complete();
		int fallbackCount = outbound.fallback();
		int dropped = outbound.dropped();
		for (AConnection route : routes) {
			if (skipBusy && route.isOutboundBusy()) continue;
			attempted++;
			boolean sent = true;
			for (Message message : messages) {
				if (!route.trySendMessage(message)) {
					sent = false;
					break;
				}
			}
			if (sent) {
				complete++;
			} else if (fallback != null && route.trySendMessage(fallback)) {
				fallbackCount++;
			} else {
				dropped++;
			}
		}
		return new BroadcastResult(attempted, complete, fallbackCount, dropped);
	}

	/**
	 * Returns live upgraded-inbound routes which do not duplicate an active
	 * manager-owned client for the same remote key. The manager-owned route wins so
	 * every broadcast attempts at most one physical socket per identity.
	 */
	private List<AConnection> getSupplementalInboundRoutes() {
		ArrayList<AConnection> routes=new ArrayList<>();
		for (AccountKey peerKey:upgradedInboundRoutes.keySet()) {
			if (getConnection(peerKey)!=null) continue;
			AConnection route=getUpgradedInboundConnection(peerKey);
			if (route!=null) routes.add(route);
		}
		return routes;
	}

	// ========== Maintenance Loop ==========

	/** Runs reconnect maintenance on one virtual thread until {@link #close()}. */
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
		pruneDeadUpgradedInboundConnections();
		pruneDeadPendingConnections();

		long now = System.currentTimeMillis();

		for (Map.Entry<AccountKey, DesiredPeer> entry : desiredPeers.entrySet()) {
			AccountKey peerKey = entry.getKey();
			DesiredPeer desired = entry.getValue();

			synchronized (connectionLock) {
				if (connections.containsKey(peerKey) || pendingConnections.containsKey(peerKey)
						|| upgradedInboundRoutes.containsKey(peerKey)) continue;
			}
			if (!desired.isRetryDue(now)) continue;

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
				beginOutboundAdmission(peerKey, convex);
				log.debug("Opened connection to peer {} at {}; awaiting admission", peerKey, target);
			} catch (Exception e) {
				recordRetryFailure(desired, now);
				log.debug("Failed to connect to peer {} (attempt {}): {}",
					peerKey, desired.getFailCount(), e.getMessage());
			}
		}
	}

	/** Revokes upgraded capabilities whose listener-owned socket is no longer valid. */
	private void pruneDeadUpgradedInboundConnections() {
		upgradedInboundRoutes.entrySet().removeIf(entry -> {
			AConnection connection = entry.getValue();
			return connection == null || connection.isClosed()
				|| !entry.getKey().equals(connection.getTrustedKey());
		});
	}

	// ========== Transport Resolution ==========

	/** Returns the first supported TCP transport in one desired-node entry. */
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

	/** Calculates bounded exponential retry delay with full lower-half jitter. */
	static long calculateBackoff(int failCount) {
		long base = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << Math.min(failCount - 1, 10));
		long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
		return base / 2 + jitter;
	}

	// ========== Verification ==========

	/** Completes the limbo-to-active transition after the expected key was proved. */
	private void completeVerifiedAdmission(AccountKey peerKey, PendingConnection pending) {
		Convex replaced;
		synchronized (connectionLock) {
			if (pendingConnections.get(peerKey) != pending || !desiredPeers.containsKey(peerKey)
					|| !pending.connection.isConnected()) {
				rejectPendingAdmission(peerKey, pending,
					new IllegalStateException("Verified connection is no longer eligible for admission"));
				return;
			}
			grantOutboundCapabilities(pending.connection);
			configureReceiveLimit(pending.connection, true);
			replaced = connections.put(peerKey, pending.connection);
			pendingConnections.remove(peerKey, pending);
		}
		if (replaced != null && replaced != pending.connection) closeSilently(replaced);
		resetRetryBackoff(peerKey);
		completePeerWaiter(peerKey,pending.connection);
		notifyPeerAdmitted(peerKey,pending.connection);
		pending.admission.complete(pending.connection);
		log.info("Verified and admitted peer {} at {}", peerKey, pending.connection.getHostAddress());
	}

	/** Rejects one still-current limbo client and schedules its desired peer retry. */
	private void rejectPendingAdmission(
			AccountKey peerKey,PendingConnection pending,Throwable failure) {
		boolean removed;
		DesiredPeer desired;
		synchronized (connectionLock) {
			removed = pendingConnections.remove(peerKey, pending);
			desired = removed ? desiredPeers.get(peerKey) : null;
		}
		if (!removed) return;
		pending.admission.completeExceptionally(failure);
		closeSilently(pending.connection);
		if (desired != null) recordRetryFailure(desired, System.currentTimeMillis());
		log.debug("Rejected peer {} after failed identity challenge: {}", peerKey, failure.getMessage());
	}

	/** Rejects limbo clients whose sockets closed before identity verification. */
	private void pruneDeadPendingConnections() {
		for (Map.Entry<AccountKey, PendingConnection> entry : pendingConnections.entrySet()) {
			PendingConnection pending = entry.getValue();
			if (!pending.connection.isConnected()) {
				rejectPendingAdmission(entry.getKey(), pending,
					new IllegalStateException("Connection closed while awaiting identity verification"));
			}
		}
	}

	/** Clears retry state after successful admission. */
	private void resetRetryBackoff(AccountKey peerKey) {
		DesiredPeer desired = desiredPeers.get(peerKey);
		if (desired == null) return;
		desired.resetRetry();
	}

	/** Advances one desired peer's reconnect backoff after a dial/admission failure. */
	private static void recordRetryFailure(DesiredPeer desired, long now) {
		desired.recordFailure(now);
	}

	/** Manager-owned client and its caller-visible admission result while in limbo. */
	private static final class PendingConnection {
		final Convex connection;
		final CompletableFuture<Convex> admission = new CompletableFuture<>();

		PendingConnection(Convex connection) {
			this.connection = connection;
		}
	}

	// ========== DesiredPeer ==========

	/**
	 * Bounded connection intent for one remote node identity.
	 *
	 * <p>Public fields are immutable transport metadata. Private mutable retry state
	 * belongs to the maintenance loop and is preserved across newer discovery
	 * revisions. Presence in this type is neither proof of identity nor proof that a
	 * physical route currently exists.</p>
	 */
	public static class DesiredPeer {

		/** Expected remote node key and map identity. */
		public final AccountKey peerKey;
		/** Ordered discovery or operator-supplied transport URIs. */
		public final AVector<AString> transports;
		/** True when metadata came from an external discovery adapter. */
		final boolean discovered;
		/** Monotonic discovery revision, or local creation time for operator intent. */
		public final long revision;

		/** Consecutive dial or admission failures used only for reconnect scheduling. */
		private int failCount = 0;
		/** Earliest wall-clock time at which maintenance may retry this peer. */
		private long nextRetryTime = 0;

		private DesiredPeer(AccountKey peerKey,AVector<AString> transports,
				boolean discovered,long revision) {
			this.peerKey = peerKey;
			this.transports = transports;
			this.discovered=discovered;
			this.revision=revision;
		}

		/** Creates intent translated by an external discovery adapter. */
		private static DesiredPeer discovered(AccountKey peerKey,
				AVector<AString> transports,long revision) {
			return new DesiredPeer(peerKey,transports,true,revision);
		}

		/**
		 * Creates operator-supplied intent with one TCP dial target.
		 *
		 * @param peerKey expected remote node key
		 * @param address TCP dial target
		 * @return desired-peer intent
		 */
		@SuppressWarnings({"unchecked", "rawtypes"})
		public static DesiredPeer create(AccountKey peerKey, InetSocketAddress address) {
			String uri = "tcp://" + address.getHostString() + ":" + address.getPort();
			AVector<AString> transports = (AVector) Vectors.of(Strings.create(uri));
			return new DesiredPeer(peerKey,transports,false,System.currentTimeMillis());
		}

		/**
		 * Creates intent whose transport must arrive through later directory data.
		 *
		 * @param peerKey expected remote node key
		 * @return desired-peer intent without a dial target
		 */
		public static DesiredPeer create(AccountKey peerKey) {
			return new DesiredPeer(peerKey,null,false,System.currentTimeMillis());
		}

		/** Replaces only the dial target while preserving discovery revision metadata. */
		private DesiredPeer withTransports(AVector<AString> transports) {
			DesiredPeer updated=new DesiredPeer(peerKey,transports,discovered,revision);
			updated.copyRetryState(this);
			return updated;
		}

		/** Copies reconnect scheduling without exposing mutable state as metadata. */
		private void copyRetryState(DesiredPeer source) {
			synchronized (source) {
				failCount=source.failCount;
				nextRetryTime=source.nextRetryTime;
			}
		}

		private synchronized boolean isRetryDue(long now) {
			return now>=nextRetryTime;
		}

		private synchronized int getFailCount() {
			return failCount;
		}

		private synchronized void resetRetry() {
			failCount=0;
			nextRetryTime=0;
		}

		private synchronized void recordFailure(long now) {
			failCount++;
			nextRetryTime=now+calculateBackoff(failCount);
		}

		@Override
		public String toString() {
			return "DesiredPeer{" + peerKey + ", transports=" + transports + "}";
		}
	}
}
