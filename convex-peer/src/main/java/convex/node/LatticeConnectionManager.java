package convex.node;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.AConvexConnected;
import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
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
import convex.net.PeerTarget;
import convex.net.Transports;
import convex.peer.AConnectionManager;

/**
 * Maintains connection intent and outbound routes for one
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
 *   <li>{@link DesiredPeer} entries are connection <em>intent</em>, not live
 *       sockets or trust assertions. Explicit peers named through the
 *       {@code addPeer} and {@code connectPeer} APIs are always kept. Discovered
 *       peers are only candidates: nodes with a supported transport to dial, bounded
 *       by {@link #setMaxDesiredPeers}, never blocking explicit intent or an inbound
 *       route upgrade.</li>
 *   <li>Pending connections are manager-owned outbound sockets held in identity
 *       verification limbo.</li>
 *   <li>Active connections are admitted manager-owned outbound clients.</li>
 *   <li>Upgraded inbound routes are authenticated sockets physically owned by
 *       an application transport and assigned endpoint; this manager owns only their
 *       outbound capability. They take none of the peer slots set by
 *       {@link #setPeerTargets}.</li>
 * </ol>
 *
 * <p>The {@code addPeer} overloads all create or retain desired-peer intent.
 * {@link #addPeer(AccountKey)} waits for later discovery transport metadata,
 * {@link #addPeer(AccountKey, InetSocketAddress)} supplies an operator transport,
 * and {@link #addPeer(AccountKey, Convex)} begins admission of an already opened
 * manager-owned client. {@link #connectPeer(AccountKey, InetSocketAddress)} is the
 * deterministic connect-and-wait convenience API. {@link #connectPeer(URI)} reaches a
 * node known only by its endpoint and admits it under the key it proves, after which
 * it is explicit intent like any other.</p>
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

	/** Hard cap for discovered candidates; explicit intent is not counted. */
	private volatile int maxDesiredPeers = LatticePropagatorConfig.DEFAULT_MAX_DESIRED_PEERS;
	private int ambientPeers=LatticePropagatorConfig.DEFAULT_AMBIENT_PEERS;
	/** Normal root propagation is the heartbeat; a live route is probed only after this much silence. */
	private volatile long keepAlive=LatticePropagatorConfig.DEFAULT_KEEP_ALIVE;
	private int activePeers=LatticePropagatorConfig.DEFAULT_ACTIVE_PEERS;
	/** Reverse DATA_REQUESTs being served; the setter replaces it, so callers release the one they acquired. */
	private volatile Semaphore dataRequestPermits=new Semaphore(LatticePropagatorConfig.DEFAULT_INBOUND_QUEUE_SIZE);

	// ========== Constants ==========

	/** Interval between maintenance loop iterations (milliseconds). */
	static final long MAINTENANCE_INTERVAL = 1_000L;
	static final long ACTIVE_RETENTION_MS = 120_000L;
	/** Time a probe may take: a round trip plus queueing behind outbound lattice data. */
	static final long PROBE_TIMEOUT_MS = 30_000L;
	/** New, unverified sockets have a separate, short admission deadline. */
	static final long ADMISSION_TIMEOUT_MS = 5_000L;
	static final int MAX_CONCURRENT_DIALS = 4;
	/** Attempts to reach a node known only by its endpoint and learn its key, before giving up. */
	static final int MAX_ENDPOINT_ATTEMPTS = 3;

	/** Initial reconnection delay (milliseconds). */
	static final long INITIAL_BACKOFF_MS = 1_000L;

	/** Maximum reconnection delay (milliseconds). */
	static final long MAX_BACKOFF_MS = 30_000L;

	private static final Result DATA_REQUEST_LOAD=Result.error(ErrorCodes.LOAD,
		"Lattice data request capacity exhausted");

	// ========== State ==========

	/**
	 * Candidate peers. Maintenance keeps a stable ambient subset, recent active
	 * communicators and explicit connections live, rather than dialling every entry.
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

	/** Futures awaiting the ambient target, met by routes to peers not named explicitly. Lock held. */
	private final List<CompletableFuture<Void>> ambientWaiters=new ArrayList<>();

	/** Futures awaiting a maintenance pass that finds no authenticated route. Lock held. */
	private final List<CompletableFuture<Void>> isolationWaiters=new ArrayList<>();

	/** Wakes the single maintenance thread when desired-peer state changes. */
	private final Semaphore maintenanceSignal = new Semaphore(0);

	/** Serialises admission, removal and replacement across pending and active maps. */
	private final Object connectionLock = new Object();

	/** Local policy state, bounded by desiredPeers and independent of discovery revisions. */
	private final Map<AccountKey,PeerActivity> activity=new HashMap<>();
	/** Desired peers that are discovered candidates rather than explicit intent. Lock held. */
	private int candidateCount;
	/** Reservations include socket opening, before identity verification begins. */
	private final Map<AccountKey,DesiredPeer> dialling=new HashMap<>();
	/**
	 * Nodes to identify by challenge, until one proves a key it may be admitted under or
	 * its attempts run out: keyed by endpoint for {@link #connectPeer(URI)}, and by the
	 * intent itself for {@link #connectPeer(PeerTarget)}. Lock held.
	 */
	private final Map<Object,EndpointIntent> endpointIntents=new LinkedHashMap<>();
	/** Futures awaiting the end of this node's route to a peer. Lock held. */
	private final Map<AccountKey,CompletableFuture<Void>> disconnectWaiters=new HashMap<>();
	private final Map<AccountKey,HealthCheck> health=new HashMap<>();
	private final LongSupplier clock;
	private ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
	private Function<AConnection,CompletableFuture<?>> inboundProbe;
	private Consumer<AConnection> inboundFailure;

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

	/** Hooks reporting authenticated contact and failures, for an application's own records. */
	private volatile Consumer<AccountKey> contactHandler;
	private volatile Consumer<AccountKey> failureHandler;

	/** Maintenance thread for reconnection. */
	private Thread maintenanceThread;

	/** Whether the maintenance loop is running. */
	private volatile boolean running = false;

	/** Whether newly opened or supplied connections may still be admitted. */
	private volatile boolean accepting = true;

	// ========== Constructor ==========

	/**
	 * Creates a new LatticeConnectionManager with the given store.
	 *
	 * @param store Store to set on peer connections (security boundary)
	 */
	public LatticeConnectionManager(AStore store) {
		this(store,monotonicClock());
	}

	private static LongSupplier monotonicClock() {
		long origin=System.nanoTime();
		return () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-origin);
	}

	/** Monotonic clock injection for deterministic maintenance tests. */
	LatticeConnectionManager(AStore store,LongSupplier clock) {
		if (store == null) throw new IllegalArgumentException("Store must not be null");
		this.store = store;
		this.clock=clock;
	}

	/**
	 * Sets soft targets for the routes this node opens. Explicit connections and
	 * outstanding work may exceed them; peers reached only inbound do not count.
	 */
	public void setPeerTargets(int ambient,int active) {
		if (ambient<0 || active<0) throw new IllegalArgumentException("Peer targets must be non-negative");
		List<CompletableFuture<Void>> met;
		synchronized (connectionLock) {
			ambientPeers=ambient;
			activePeers=active;
			met=takeMetAmbientWaiters();
		}
		for (CompletableFuture<Void> waiter:met) waiter.complete(null);
		maintenanceSignal.release();
	}

	/**
	 * Returns a future that completes once this node holds routes it opened to at least
	 * the ambient target of peers, and to at least one, not counting peers named with
	 * {@link #addPeer} or {@link #connectPeer}. A node seeding its connections from
	 * explicitly named peers can then release them, as other peers can replace them.
	 * Completes at once if the target is already met, and fails when the manager
	 * closes. Routes lost later do not undo it.
	 *
	 * @return future completing when the ambient target is met
	 */
	public CompletableFuture<Void> whenAmbientTargetMet() {
		synchronized (connectionLock) {
			if (!accepting) {
				return CompletableFuture.failedFuture(new IllegalStateException("Connection manager is closed"));
			}
			if (isAmbientTargetMet()) return CompletableFuture.completedFuture(null);
			CompletableFuture<Void> waiter=new CompletableFuture<>();
			ambientWaiters.add(waiter);
			return waiter;
		}
	}

	/**
	 * Whether routes this node opened to peers not named explicitly meet the ambient
	 * target. A node without ambient slots still needs one, else nothing could replace
	 * the peers it was told to use. Lock held.
	 */
	private boolean isAmbientTargetMet() {
		int routes=0;
		for (AccountKey key:connections.keySet()) {
			PeerActivity state=activity.get(key);
			if (state!=null && !state.explicit) routes++;
		}
		return routes>=Math.max(1,ambientPeers);
	}

	/**
	 * Returns a future that completes once this node holds no authenticated route,
	 * opened by either side: at once if it holds none now, as when it starts, otherwise
	 * when a maintenance pass finds the last one gone. A node can then seed its
	 * connections again. Fails when the manager closes.
	 *
	 * @return future completing when this node has no route
	 */
	public CompletableFuture<Void> whenIsolated() {
		synchronized (connectionLock) {
			if (!accepting) {
				return CompletableFuture.failedFuture(new IllegalStateException("Connection manager is closed"));
			}
			if (isIsolated()) return CompletableFuture.completedFuture(null);
			CompletableFuture<Void> waiter=new CompletableFuture<>();
			isolationWaiters.add(waiter);
			return waiter;
		}
	}

	/** Whether this node holds no route, outbound or upgraded inbound. Lock held. */
	private boolean isIsolated() {
		return connections.isEmpty() && upgradedInboundRoutes.isEmpty();
	}

	/** Removes the isolation waiters to complete, once no route is left. Lock held. */
	private List<CompletableFuture<Void>> takeIsolationWaiters() {
		if (isolationWaiters.isEmpty() || !isIsolated()) return List.of();
		List<CompletableFuture<Void>> isolated=new ArrayList<>(isolationWaiters);
		isolationWaiters.clear();
		return isolated;
	}

	/** Removes the ambient waiters to complete, once the target is met. Lock held. */
	private List<CompletableFuture<Void>> takeMetAmbientWaiters() {
		if (ambientWaiters.isEmpty() || !isAmbientTargetMet()) return List.of();
		List<CompletableFuture<Void>> met=new ArrayList<>(ambientWaiters);
		ambientWaiters.clear();
		return met;
	}

	/**
	 * Sets how long a live route may stay silent before it is probed, and so how
	 * often a healthy route is reported as contact. Nodes without a route are never
	 * probed.
	 *
	 * @param keepAlive quiet time in milliseconds
	 */
	public void setKeepAlive(long keepAlive) {
		if (keepAlive<=0) throw new IllegalArgumentException("Keep-alive must be positive");
		this.keepAlive=keepAlive;
		maintenanceSignal.release();
	}

	/** Marks useful directed communication, never routine gossip or keepalives. */
	public void markActive(AccountKey peerKey) {
		synchronized (connectionLock) {
			PeerActivity state=activity.get(peerKey);
			if (state==null) return;
			state.lastActive=clock.getAsLong();
		}
		maintenanceSignal.release();
	}

	/** Retains a peer for an asynchronous operation, optionally recording application activity. */
	public <T> CompletableFuture<T> withPeer(AccountKey peerKey,boolean useful,
			Supplier<CompletableFuture<T>> operation) {
		PeerActivity state;
		synchronized (connectionLock) {
			state=activity.get(peerKey);
			if (state!=null) {
				state.requests++;
				if (useful) state.lastActive=clock.getAsLong();
			}
		}
		try {
			return operation.get().whenComplete((value,error) -> {
				synchronized (connectionLock) {
					if (state!=null) {
						state.requests--;
						if (useful && error==null) state.lastActive=clock.getAsLong();
					}
				}
				maintenanceSignal.release();
			});
		} catch (RuntimeException | Error e) {
			synchronized (connectionLock) { if (state!=null) state.requests--; }
			throw e;
		}
	}

	/** Installs the endpoint's correlated PING support for listener-owned routes. */
	void setInboundProbe(Function<AConnection,CompletableFuture<?>> probe,
			Consumer<AConnection> failure) {
		inboundProbe=probe;
		inboundFailure=failure;
	}

	/** Records decoded inbound traffic without promoting the peer into the active set. */
	void received(AccountKey key,Object route) {
		synchronized (connectionLock) {
			HealthCheck check=health.get(key);
			if (check==null) return;
			// The endpoint reports the physical socket after decoding lattice deltas;
			// the client observer reports its wrapper for other incoming messages.
			boolean matches=check.route==route || (route instanceof AConnection physical
				&& check.route instanceof AConvexConnected peer && peer.usesConnection(physical));
			if (matches) {
				check.lastReceived=clock.getAsLong();
				check.probing=false;
			}
		}
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
	 * Configures the hard cap on discovered candidates. Explicit peers are not
	 * counted. Existing entries are not truncated.
	 *
	 * @param maxDesiredPeers maximum retained discovered candidates
	 */
	public void setMaxDesiredPeers(int maxDesiredPeers) {
		if (maxDesiredPeers <= 0) throw new IllegalArgumentException(
			"Maximum desired peers must be positive");
		synchronized (connectionLock) {
			if (candidateCount > maxDesiredPeers) throw new IllegalStateException(
				"Discovered candidate count already exceeds requested limit");
			this.maxDesiredPeers=maxDesiredPeers;
		}
	}

	/**
	 * Returns the configured hard cap on discovered candidates.
	 *
	 * @return maximum retained discovered candidates
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
	 * Sets hooks reporting what this manager learns about other nodes. Contact is an
	 * authenticated connection: a verified outbound admission or an upgraded inbound
	 * route, reported again for each quiet interval in which the route is heard from or
	 * answers a probe. Failure is a dial or identity challenge that failed for a desired
	 * peer, or an authenticated route that stopped answering. Hooks run outside the
	 * manager's locks and must not block; their failures are logged.
	 *
	 * @param contacted contact hook, or {@code null} for none
	 * @param failed failure hook, or {@code null} for none
	 */
	public void setContactHandlers(Consumer<AccountKey> contacted,Consumer<AccountKey> failed) {
		this.contactHandler=contacted;
		this.failureHandler=failed;
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

	/**
	 * Configures how many reverse DATA_REQUESTs from admitted peers are served at
	 * once. A request beyond the limit is answered with a :LOAD result. Requests
	 * already being served keep their permits.
	 *
	 * @param limit maximum data requests served concurrently
	 */
	void setDataRequestLimit(int limit) {
		if (limit<=0) throw new IllegalArgumentException("Data request limit must be positive");
		this.dataRequestPermits=new Semaphore(limit);
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
		if (workers.isShutdown()) workers=Executors.newVirtualThreadPerTaskExecutor();
		accepting = true;
		running = true;
		maintenanceThread = Thread.ofVirtual().name("Lattice connection maintenance").start(this::maintenanceLoop);
		maintenanceSignal.release();
		log.debug("LatticeConnectionManager started");
	}

	/**
	 * Stops admission and maintenance, then closes all connections at once.
	 */
	@Override
	public synchronized void close() {
		stopMaintenance();
		closeAllConnections();
		workers.shutdownNow();
		log.debug("LatticeConnectionManager closed");
	}

	/**
	 * Closes as {@link #close()} does, once the messages already sent on each
	 * manager-owned route have been written to its socket. Admission and maintenance
	 * stop before the wait, so no route is retired or replaced while its last
	 * messages leave. The wait ends at the timeout, or at once if the caller is
	 * interrupted. A route that cannot report written messages is not waited for.
	 * Delivery stays best effort: written is not acknowledged.
	 *
	 * @param timeout maximum milliseconds to wait for sent messages to be written;
	 *        zero or less closes at once
	 */
	public synchronized void close(long timeout) {
		stopMaintenance();
		if (timeout>0) awaitWritten(timeout);
		close();
	}

	/** Stops admission and the maintenance thread, waiting for a pass in progress. */
	private void stopMaintenance() {
		accepting = false;
		running = false;
		maintenanceSignal.release();

		if (maintenanceThread != null) {
			maintenanceThread.interrupt();
			if (maintenanceThread != Thread.currentThread()) {
				boolean interrupted=false;
				while (maintenanceThread.isAlive()) {
					try {
						maintenanceThread.join();
					} catch (InterruptedException e) {
						interrupted=true;
						maintenanceThread.interrupt();
					}
				}
				if (interrupted) Thread.currentThread().interrupt();
			}
			maintenanceThread = null;
		}
	}

	private void awaitWritten(long timeout) {
		ArrayList<CompletableFuture<?>> written=new ArrayList<>();
		for (Convex peer:connections.values()) {
			written.add(peer.flushOutbound().exceptionally(e -> null));
		}
		try {
			// Failures complete normally above, so only the timeout ends the wait early
			CompletableFuture.allOf(written.toArray(CompletableFuture[]::new))
				.get(timeout,TimeUnit.MILLISECONDS);
		} catch (TimeoutException | ExecutionException e) {
			log.debug("Lattice messages not written to every route within {} ms; closing",timeout);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Revokes every logical route and closes only manager-owned physical clients.
	 * Upgraded inbound sockets remain owned by the propagator listener; clearing their
	 * map entries removes this manager's outbound capability without closing them.
	 */
	@Override
	public void closeAllConnections() {
		List<Convex> active;
		List<PendingConnection> pending;
		List<CompletableFuture<Convex>> waiters;
		List<CompletableFuture<AConnection>> routeWaiters;
		List<CompletableFuture<Void>> stateWaiters;
		List<EndpointIntent> unidentified;
		List<CompletableFuture<Void>> disconnected;
		synchronized (connectionLock) {
			active = new ArrayList<>(connections.values());
			connections.clear();
			pending = new ArrayList<>(pendingConnections.values());
			pendingConnections.clear();
			// Attempts in flight find their intent gone and close what they opened
			unidentified = new ArrayList<>(endpointIntents.values());
			endpointIntents.clear();
			waiters = new ArrayList<>(connectionWaiters.values());
			connectionWaiters.clear();
			routeWaiters = new ArrayList<>(upgradedRouteWaiters.values());
			upgradedRouteWaiters.clear();
			stateWaiters = new ArrayList<>(ambientWaiters);
			ambientWaiters.clear();
			stateWaiters.addAll(isolationWaiters);
			isolationWaiters.clear();
			disconnected = new ArrayList<>(disconnectWaiters.values());
			disconnectWaiters.clear();
			// The listener/endpoint owns these physical inbound connections. Closing
			// this manager revokes only their logical outbound capability.
			upgradedInboundRoutes.clear();
			dialling.clear();
			health.clear();
		}
		for (Convex connection : active) closeSilently(connection);
		for (PendingConnection pc : pending) {
			pc.admission.completeExceptionally(new IllegalStateException("Connection manager closed"));
			closeSilently(pc.connection);
		}
		for (CompletableFuture<Convex> waiter : waiters) {
			waiter.completeExceptionally(new IllegalStateException("Connection manager closed"));
		}
		for (EndpointIntent intent : unidentified) {
			intent.admission.completeExceptionally(new IllegalStateException("Connection manager closed"));
		}
		for (CompletableFuture<AConnection> waiter : routeWaiters) {
			waiter.completeExceptionally(new IllegalStateException("Connection manager closed"));
		}
		for (CompletableFuture<Void> waiter : stateWaiters) {
			waiter.completeExceptionally(new IllegalStateException("Connection manager closed"));
		}
		// Every route this manager opened is now closed
		for (CompletableFuture<Void> waiter : disconnected) waiter.complete(null);
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
			log.warn("Ignoring desired peer {}: connection manager is closed",peerKey);
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
			log.warn("Ignoring desired peer {}: connection manager is closed",peerKey);
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
				"Connection manager is closed"));
		}
		maintenanceSignal.release();
		return whenConnected(peerKey);
	}

	/**
	 * Connects to a node known only by its transport endpoint, admitting it under the
	 * key it proves. The maintenance thread dials the endpoint and challenges the node
	 * without an expected key, then again naming the key it proved, so a node that
	 * admits challengers can gossip back over this connection (see
	 * {@link LatticePropagator#setChallengerAdmission}). The key becomes an explicit peer
	 * at that endpoint, so reconnections challenge for that key. A node that cannot be reached
	 * or proves no key is retried briefly: after {@value #MAX_ENDPOINT_ATTEMPTS} failed
	 * attempts the future fails and the intent is dropped.
	 *
	 * <p>This proves only that the node at the endpoint holds the key it names, not that
	 * it is the node the caller meant; name the key with
	 * {@link #connectPeer(AccountKey, InetSocketAddress)} when it is known.</p>
	 *
	 * @param endpoint {@code tcp://} or {@code tls://} endpoint of the remote node
	 * @return future completing with the admitted connection, whose verified peer is
	 *         the key the node proved
	 */
	public CompletableFuture<Convex> connectPeer(URI endpoint) {
		if (endpoint == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Endpoint must not be null"));
		}
		try {
			Transports.forEndpoint(endpoint);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
		if (keyPair == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Identifying a node by its endpoint needs this node's key pair"));
		}
		CompletableFuture<Convex> admission;
		synchronized (connectionLock) {
			if (!accepting) {
				return CompletableFuture.failedFuture(new IllegalStateException("Connection manager is closed"));
			}
			admission=endpointIntents.computeIfAbsent(endpoint,k -> new EndpointIntent(endpoint)).admission;
		}
		maintenanceSignal.release();
		return admission;
	}

	/**
	 * Makes one bounded attempt to admit a peer resolved from its name. The maintenance
	 * thread dials the target's endpoints in turn, a few times under backoff, and
	 * challenges the node there: for the target's key if it names one, otherwise for
	 * whatever key the node holds, admitting it only if the target names no keys or
	 * lists the key it proves.
	 *
	 * <p>The admitted route is kept, safe from trimming, until it closes or the peer is
	 * released, but it is not redialled: when it closes, the peer is forgotten and
	 * {@link #whenDisconnected} completes, and what happens next is the caller's. A
	 * caller wanting the manager to reconnect by itself adds the peer as desired intent
	 * ({@link #connectPeer(AccountKey, InetSocketAddress)}). A peer that is already
	 * desired intent keeps it.</p>
	 *
	 * @param target resolved peer, as {@link convex.net.PeerResolver} gives it
	 * @return future completing with the admitted connection, or failing once the
	 *         attempts run out, if the target has no endpoint this node can dial, or
	 *         if it names only this node's key; cancelling it abandons the attempt
	 */
	public CompletableFuture<Convex> connectPeer(PeerTarget target) {
		if (target == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Peer target must not be null"));
		}
		List<URI> endpoints=new ArrayList<>();
		for (long i=0; i<target.endpoints().count(); i++) {
			try {
				URI endpoint=Transports.endpoint(target.endpoints().get(i).toString());
				Transports.forEndpoint(endpoint);
				endpoints.add(endpoint);
			} catch (IllegalArgumentException e) {
				// Not a transport this node has; try the others
			}
		}
		if (endpoints.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"No endpoint this node can dial for peer "+target));
		}
		AKeyPair kp=keyPair;
		if (kp == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Identifying a peer needs this node's key pair"));
		}
		AVector<AccountKey> keys=target.keys();
		if (keys.count()==1 && keys.get(0).equals(kp.getAccountKey())) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Peer "+target+" is this node"));
		}
		EndpointIntent intent=new EndpointIntent(target,endpoints);
		synchronized (connectionLock) {
			if (!accepting) {
				return CompletableFuture.failedFuture(new IllegalStateException("Connection manager is closed"));
			}
			endpointIntents.put(intent,intent);
		}
		intent.admission.whenComplete((admitted,error) -> {
			if (!intent.admission.isCancelled()) return;
			synchronized (connectionLock) {
				endpointIntents.remove(intent,intent);
			}
		});
		maintenanceSignal.release();
		return intent.admission;
	}

	/**
	 * Returns a future that completes once this node has no route to a peer that it
	 * opened, nor one being opened: at once if there is none, otherwise when the route
	 * closes, the peer is released or removed, or the manager closes. A caller holding a
	 * peer from {@link #connectPeer(PeerTarget)} uses it to learn the peer has gone.
	 *
	 * @param peerKey key of the peer
	 * @return future completing when the route has gone; cancelling it affects no one else
	 */
	public CompletableFuture<Void> whenDisconnected(AccountKey peerKey) {
		if (peerKey == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Peer key must not be null"));
		}
		synchronized (connectionLock) {
			if (!accepting || !hasOutboundRouteOrAttempt(peerKey)) return CompletableFuture.completedFuture(null);
			return disconnectWaiters.computeIfAbsent(peerKey,k -> new CompletableFuture<>()).copy();
		}
	}

	/**
	 * Adds explicit connection intent, optionally replacing only its dial metadata.
	 * Explicit intent is never refused for capacity: only operator calls create it,
	 * and a discovered candidate it promotes stops counting against the candidate cap.
	 *
	 * @return {@code false} only if the manager is closed
	 */
	private boolean registerDesiredPeerIntent(
			AccountKey peerKey,DesiredPeer desired,boolean replace) {
		synchronized (connectionLock) {
			if (!accepting) return false;
			DesiredPeer existing=desiredPeers.get(peerKey);
			if (existing!=null) {
				PeerActivity state=activity.get(peerKey);
				if (!state.explicit) {
					state.explicit=true;
					candidateCount--;
				}
				// Desired intent is redialled: a peer admitted from its target becomes so
				state.held=false;
				if (replace) {
					// An operator-supplied address replaces only the dial target. Keep
					// discovery revision metadata so stale discovery cannot overwrite it.
					if (existing.discovered) desired=existing.withTransports(desired.transports);
					desiredPeers.put(peerKey,desired);
				}
				return true;
			}
			desiredPeers.put(peerKey,desired);
			PeerActivity state=new PeerActivity();
			state.explicit=true;
			activity.put(peerKey,state);
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
		synchronized (connectionLock) {
			if (!accepting) {
				return CompletableFuture.failedFuture(
					new IllegalStateException("Connection manager is closed"));
			}
			Convex connected=getConnection(peerKey);
			if (connected!=null) return CompletableFuture.completedFuture(connected);
			return connectionWaiters.computeIfAbsent(peerKey,k -> new CompletableFuture<>());
		}
	}

	// ========== Authenticated Inbound Route Upgrade ==========

	/**
	 * Explicitly upgrades a physically inbound connection into an authenticated
	 * outbound propagation route.
	 *
	 * <p>An operator-assigned inbound connection is not sufficient. The connection
	 * must already carry a trusted key established by live challenge/response for
	 * the identity its application vouched for through
	 * {@link LatticePropagator#authenticateInboundRoute}; the inbound verifier is
	 * the only caller. Admission does not depend on desired-peer membership, so
	 * discovered candidates can never block it. This method never authenticates or
	 * assigns trust itself.</p>
	 *
	 * <p>A live route already held for the same key is probed first. If it answers
	 * within {@link #ADMISSION_TIMEOUT_MS} it is kept and this connection is retired;
	 * otherwise it is retired and this connection replaces it. A second proof of a
	 * key cannot take over a route that still works, and a node reconnecting over a
	 * dead socket regains its route within one probe.</p>
	 *
	 * @param connection authenticated inbound physical connection
	 * @return future completing with the route held for the key once decided: this
	 *   connection, or the live route kept in its place; it fails if the manager
	 *   closes first
	 * @throws IllegalArgumentException if {@code connection} is {@code null}
	 * @throws IllegalStateException if the connection or the manager is closed
	 * @throws SecurityException if the connection carries no authenticated key
	 */
	CompletableFuture<AConnection> upgradeInboundConnection(AConnection connection) {
		if (connection == null) throw new IllegalArgumentException("Connection must not be null");
		AccountKey peerKey = connection.getTrustedKey();
		if (peerKey == null) {
			throw new SecurityException("Untrusted inbound connection cannot become an outbound route");
		}
		if (connection.isClosed()) {
			throw new IllegalStateException("Closed inbound connection cannot become an outbound route");
		}
		synchronized (connectionLock) {
			if (!accepting) throw new IllegalStateException("Connection manager is closed");
		}
		CompletableFuture<AConnection> decided=new CompletableFuture<>();
		decideInboundRoute(peerKey,connection,decided);
		return decided;
	}

	/** Installs {@code connection} as the key's route unless a live route answers a probe first. */
	private void decideInboundRoute(AccountKey peerKey,AConnection connection,
			CompletableFuture<AConnection> decided) {
		boolean closed;
		AConnection held;
		synchronized (connectionLock) {
			closed=!accepting;
			held=closed ? null : getUpgradedInboundConnection(peerKey);
			if (!closed && (held==null || held==connection)) installInboundRoute(peerKey,connection);
		}
		if (closed) {
			decided.completeExceptionally(new IllegalStateException("Connection manager is closed"));
		} else if (held==null || held==connection) {
			inboundRouteInstalled(peerKey,connection,null,decided);
		} else {
			probeHeldRoute(peerKey,connection,held,decided);
		}
	}

	/** Keeps {@code held} if it answers a probe in time, otherwise replaces it with {@code connection}. */
	private void probeHeldRoute(AccountKey peerKey,AConnection connection,AConnection held,
			CompletableFuture<AConnection> decided) {
		CompletableFuture<?> probe;
		try {
			probe=probePeer(held);
		} catch (RuntimeException e) {
			probe=CompletableFuture.failedFuture(e);
		}
		probe.orTimeout(ADMISSION_TIMEOUT_MS,TimeUnit.MILLISECONDS).whenComplete((value,error) -> {
			// Decided off the thread that completed the probe, which may be the inbound dispatcher
			try {
				workers.execute(() -> heldRouteProbed(peerKey,connection,held,error,decided));
			} catch (RejectedExecutionException e) {
				decided.completeExceptionally(new IllegalStateException("Connection manager is closed"));
			}
		});
	}

	private void heldRouteProbed(AccountKey peerKey,AConnection connection,AConnection held,
			Throwable error,CompletableFuture<AConnection> decided) {
		boolean closed;
		boolean replace;
		AConnection current;
		synchronized (connectionLock) {
			closed=!accepting;
			current=closed ? null : getUpgradedInboundConnection(peerKey);
			replace=!closed && current==held && error!=null && !connection.isClosed();
			if (replace) installInboundRoute(peerKey,connection);
		}
		if (closed) {
			decided.completeExceptionally(new IllegalStateException("Connection manager is closed"));
		} else if (replace) {
			inboundRouteInstalled(peerKey,connection,held,decided);
		} else if (current!=held) {
			// The route changed while it was probed: decide again against the new one
			decideInboundRoute(peerKey,connection,decided);
		} else if (error==null) {
			log.debug("Kept the live inbound route for {} against another authenticated connection",peerKey);
			retireInbound(connection);
			decided.complete(held);
		} else {
			// This connection closed while the silent route was probed; health checks retire that
			decided.complete(held);
		}
	}

	/** Records {@code connection} as the key's upgraded route. Requires {@code connectionLock}. */
	private void installInboundRoute(AccountKey peerKey,AConnection connection) {
		upgradedInboundRoutes.put(peerKey, connection);
		if (!connections.containsKey(peerKey)) health.put(peerKey,new HealthCheck(connection,clock.getAsLong()));
	}

	/** Completes an upgrade once {@code connection} is installed, retiring the route it replaced. */
	private void inboundRouteInstalled(AccountKey peerKey,AConnection connection,AConnection replaced,
			CompletableFuture<AConnection> decided) {
		if (replaced!=null) retireInbound(replaced);
		notifyContact(peerKey);
		CompletableFuture<AConnection> waiter = upgradedRouteWaiters.remove(peerKey);
		if (waiter != null) waiter.complete(connection);
		maintenanceSignal.release();
		log.debug("Upgraded authenticated inbound connection to outbound propagation route for {}", peerKey);
		decided.complete(connection);
	}

	/** Hands a listener-owned connection back to its owner to disconnect. */
	private void retireInbound(AConnection connection) {
		Consumer<AConnection> failure=inboundFailure;
		if (failure!=null) failure.accept(connection);
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
		synchronized (connectionLock) {
			if (!accepting) {
				return CompletableFuture.failedFuture(
					new IllegalStateException("Connection manager is closed"));
			}
			AConnection upgraded = getUpgradedInboundConnection(peerKey);
			if (upgraded != null) return CompletableFuture.completedFuture(upgraded);
			return upgradedRouteWaiters.computeIfAbsent(peerKey,key -> new CompletableFuture<>());
		}
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
			if (convex != null) closeSilently(convex);
			log.warn("Attempted to add peer with null key or connection");
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Peer key and connection must not be null"));
		}

		InetSocketAddress addr = convex.getHostAddress();
		DesiredPeer desired=(addr!=null)
			? DesiredPeer.create(peerKey,addr) : DesiredPeer.create(peerKey);
		if (convex instanceof ConvexRemote remote && remote.getEndpoint()!=null) {
			desired=DesiredPeer.create(peerKey,remote.getEndpoint());
		}
		boolean registered;
		synchronized (connectionLock) {
			registered=registerDesiredPeerIntent(peerKey,desired,false);
			// Reserve the peer as a dial does, so maintenance cannot dial it before the
			// supplied connection is installed; a dial already in flight is superseded
			if (registered) dialling.put(peerKey,desired);
		}
		if (!registered) {
			closeSilently(convex);
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Connection manager is closed"));
		}
		CompletableFuture<Convex> admission;
		try {
			admission=beginOutboundAdmission(peerKey, convex);
		} finally {
			synchronized (connectionLock) { dialling.remove(peerKey,desired); }
		}
		maintenanceSignal.release();
		return admission;
	}

	/** Moves an opened manager-owned client through limbo into the active map. */
	private CompletableFuture<Convex> beginOutboundAdmission(AccountKey peerKey, Convex convex) {
		if (!accepting || !convex.isConnected()) {
			closeSilently(convex);
			return CompletableFuture.failedFuture(new IllegalStateException(
				accepting ? "Connection is closed" : "Connection manager is closed"));
		}
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
		PendingConnection pending = new PendingConnection(convex,clock.getAsLong());
		PendingConnection replaced=null;
		boolean eligible;
		synchronized (connectionLock) {
			eligible=accepting && desiredPeers.containsKey(peerKey);
			if (eligible) replaced = pendingConnections.put(peerKey, pending);
		}
		if (!eligible) {
			closeSilently(convex);
			return CompletableFuture.failedFuture(new IllegalStateException("Peer was removed before verification"));
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
		Convex replaced=null;
		PendingConnection pending=null;
		List<CompletableFuture<Void>> met=List.of();
		boolean eligible;
		synchronized (connectionLock) {
			eligible=accepting && desiredPeers.containsKey(peerKey);
			if (eligible) {
				grantOutboundCapabilities(convex);
				configureReceiveLimit(convex, trusted);
				replaced = connections.put(peerKey, convex);
				health.put(peerKey,new HealthCheck(convex,clock.getAsLong()));
				pending = pendingConnections.remove(peerKey);
				met=takeMetAmbientWaiters();
			}
		}
		for (CompletableFuture<Void> waiter:met) waiter.complete(null);
		if (!eligible) {
			closeSilently(convex);
			return CompletableFuture.failedFuture(new IllegalStateException("Peer was removed before admission"));
		}
		if (replaced != null && replaced != convex) closeSilently(replaced);
		if (pending != null && pending.connection != convex) {
			pending.admission.completeExceptionally(new IllegalStateException("Connection replaced by admitted peer"));
			closeSilently(pending.connection);
		}
		resetRetryBackoff(peerKey);
		if (trusted) notifyContact(peerKey);
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

	/** Reports authenticated contact, outside the manager's locks. */
	private void notifyContact(AccountKey peerKey) {
		report(contactHandler,peerKey);
	}

	/** Reports a failed dial, challenge or route, outside the manager's locks. */
	private void notifyFailure(AccountKey peerKey) {
		report(failureHandler,peerKey);
	}

	private static void report(Consumer<AccountKey> handler,AccountKey peerKey) {
		if (handler==null) return;
		try {
			handler.accept(peerKey);
		} catch (RuntimeException e) {
			log.warn("Contact handler failed for {}",peerKey,e);
		}
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
			connected.setReceiveObserver(() -> received(convex.getVerifiedPeer(),convex));
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
	 *
	 * <p>At most the configured number are served at once; beyond that the peer is
	 * told the node is loaded, without waiting on the receive thread.</p>
	 */
	private void handleDataRequest(Message message) {
		Semaphore permits=dataRequestPermits;
		if (!permits.tryAcquire()) {
			refuseDataRequest(message);
			return;
		}
		try {
			Thread.ofVirtual().name("Lattice data request").start(() -> serveDataRequest(message,permits));
		} catch (Throwable t) {
			permits.release();
			throw t;
		}
	}

	private void serveDataRequest(Message message,Semaphore permits) {
		try {
			Message response;
			try {
				response = message.makeDataResponse(store);
			} finally {
				// Before the non-blocking send, so a peer holding its reply finds the permit free
				permits.release();
			}
			if (!message.returnMessage(response)) {
				log.debug("Unable to return lattice data: peer send buffer is full");
			}
		} catch (BadFormatException e) {
			log.warn("Ignoring malformed lattice DATA_REQUEST: {}", e.getMessage());
		} catch (Exception e) {
			log.warn("Unable to serve lattice DATA_REQUEST", e);
		}
	}

	private static void refuseDataRequest(Message message) {
		ACell id=message.getRequestID();
		if (id==null || message.getConnection()==null) {
			log.debug("Dropped lattice DATA_REQUEST: data request capacity exhausted");
			return;
		}
		if (!message.returnMessage(Message.createResult(DATA_REQUEST_LOAD.withID(id)))) {
			log.debug("Unable to refuse lattice DATA_REQUEST: peer send buffer is full");
		}
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
			PeerActivity state=activity.remove(peerKey);
			if (state!=null && !state.explicit) candidateCount--;
			dialling.remove(peerKey);
			health.remove(peerKey);
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
		maintenanceSignal.release();
	}

	/**
	 * Withdraws the explicit intent {@link #addPeer} or {@link #connectPeer} declared
	 * for a peer and closes the route this node opened to it, as for a seed that other
	 * peers have replaced. A peer that discovery has listed with a dial target stays a
	 * candidate like any other while the candidate cap allows; otherwise it is
	 * forgotten, so an operator-supplied address alone never makes a candidate. A route
	 * the peer opened to this node is kept. A peer that is not explicit is left as it is.
	 *
	 * @param peerKey key of the peer to release
	 */
	public void releasePeer(AccountKey peerKey) {
		if (peerKey == null) return;
		Convex removed;
		PendingConnection pending=null;
		CompletableFuture<Convex> waiter;
		synchronized (connectionLock) {
			PeerActivity state=activity.get(peerKey);
			if (state==null || !state.explicit) return;
			DesiredPeer desired=desiredPeers.get(peerKey);
			if (desired.discovered && hasDialTarget(desired) && candidateCount<maxDesiredPeers) {
				state.explicit=false;
				candidateCount++;
			} else {
				desiredPeers.remove(peerKey);
				activity.remove(peerKey);
				dialling.remove(peerKey);
				pending=pendingConnections.remove(peerKey);
			}
			removed=connections.remove(peerKey);
			// Maintenance checks an inbound route the peer opened from its next pass
			if (removed!=null) health.remove(peerKey);
			waiter=connectionWaiters.remove(peerKey);
		}
		if (removed != null) {
			closeSilently(removed);
			log.debug("Released peer: {}", peerKey);
		}
		if (pending != null) {
			pending.admission.completeExceptionally(new IllegalStateException("Peer released while awaiting verification"));
			closeSilently(pending.connection);
		}
		if (waiter != null) {
			waiter.completeExceptionally(new IllegalStateException("Peer released before connection"));
		}
		maintenanceSignal.release();
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

	/**
	 * Adds or refreshes one candidate translated by an external discovery adapter.
	 *
	 * <p>The caller owns discovery validation and schema interpretation. This
	 * transport layer accepts only the expected node key, ordered transport URIs
	 * and a monotonically increasing revision. A new key with a supported transport
	 * becomes a candidate that maintenance may dial for an ambient slot, never
	 * explicit intent; candidates are bounded by {@link #setMaxDesiredPeers}. A node
	 * with nothing to dial, such as an outbound-only node behind NAT, is not a
	 * candidate, and a candidate whose newer record has nothing to dial leaves the
	 * pool. For explicit intent, only the transport metadata is refreshed. The
	 * operation merely wakes maintenance; it never opens a socket on the caller's
	 * thread.</p>
	 *
	 * @param peerKey expected remote node key
	 * @param transports ordered transport URIs, possibly empty but not null
	 * @param revision discovery-source revision used to reject stale replacement
	 * @return {@code true} if the peer is present after the operation; {@code false}
	 *         if a candidate has no supported transport, the candidate cap rejected a
	 *         new peer or the arguments are invalid
	 */
	public boolean updateDiscoveredPeer(
			AccountKey peerKey,AVector<AString> transports,long revision) {
		if (peerKey==null || transports==null) return false;
		for (long i=0; i<transports.count(); i++) {
			if (transports.get(i)==null) return false;
		}
		boolean dialable=resolveTransport(transports)!=null;
		boolean present=true;
		boolean changed=false;
		synchronized (connectionLock) {
			if (!accepting) return false;
			DesiredPeer existing=desiredPeers.get(peerKey);
			// A peer held from its target is reached only where its caller resolved it
			if (existing!=null && activity.get(peerKey).held) return true;
			if (existing==null) {
				if (!dialable || candidateCount>=maxDesiredPeers) return false;
				desiredPeers.put(peerKey,DesiredPeer.discovered(peerKey,transports,revision));
				activity.put(peerKey,new PeerActivity());
				candidateCount++;
				changed=true;
			} else if (!existing.discovered || revision>existing.revision) {
				if (dialable || activity.get(peerKey).explicit) {
					DesiredPeer updated=DesiredPeer.discovered(peerKey,transports,revision);
					updated.copyRetryState(existing);
					desiredPeers.put(peerKey,updated);
				} else {
					// Nothing left to dial, so no longer a candidate. Maintenance retires
					// any route this node opened to it; an inbound route stays.
					desiredPeers.remove(peerKey);
					activity.remove(peerKey);
					candidateCount--;
					present=false;
				}
				changed=true;
			}
		}
		if (changed) maintenanceSignal.release();
		return present;
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
			markActive(peerKey);
			return true;
		}
		AConnection route=getUpgradedInboundConnection(peerKey);
		boolean sent=route!=null && route.trySendMessage(message);
		if (sent) markActive(peerKey);
		return sent;
	}

	/** Broadcasts once per remote identity across both physical route forms. */
	@Override
	public int broadcast(Message message) {
		int accepted = super.broadcast(message);
		for (AConnection route : getSupplementalInboundRoutes()) {
			if (route.trySendMessage(message)) accepted++;
		}
		return accepted;
	}

	/** Sends a delta sequence once per remote identity. */
	@Override
	public int broadcastSequence(List<Message> messages) {
		int complete = super.broadcastSequence(messages);
		ArrayList<AConnection> routes = new ArrayList<>(getSupplementalInboundRoutes());
		Utils.shuffle(routes);

		for (AConnection route : routes) {
			boolean sent = true;
			for (Message message : messages) {
				if (!route.trySendMessage(message)) {
					sent = false;
					break;
				}
			}
			if (sent) {
				complete++;
			}
		}
		return complete;
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
		if (!accepting) return;
		pruneDeadConnections();
		pruneDeadUpgradedInboundConnections();
		pruneDeadPendingConnections();
		long now=clock.getAsLong();
		checkHealth(now);
		List<Convex> retired=new ArrayList<>();
		List<DesiredPeer> attempts=new ArrayList<>();
		List<EndpointIntent> identifications=new ArrayList<>();
		List<CompletableFuture<Void>> disconnected=new ArrayList<>();
		synchronized (connectionLock) {
			if (!accepting) return;
			forgetLostRoutes(disconnected);
			// A caller waits on each endpoint, and its attempts are few, so it dials first
			int identifying=0;
			for (EndpointIntent intent:endpointIntents.values()) {
				if (intent.dialling) identifying++;
			}
			for (EndpointIntent intent:endpointIntents.values()) {
				if (dialling.size()+pendingConnections.size()+identifying>=MAX_CONCURRENT_DIALS) break;
				if (intent.dialling || now<intent.nextRetryTime) continue;
				intent.dialling=true;
				intent.current=intent.endpoints.get(intent.failCount%intent.endpoints.size());
				identifying++;
				identifications.add(intent);
			}
			Set<AccountKey> selected=selectPeers(now);
			for (var entry:connections.entrySet()) {
				AccountKey key=entry.getKey();
				Convex peer=entry.getValue();
				PeerActivity state=activity.get(key);
				if (selected.contains(key) || (state!=null && state.requests>0)
						|| (peer instanceof AConvexConnected connected && connected.hasPendingRequests())) continue;
				if (connections.remove(key,peer)) {
					health.remove(key);
					retired.add(peer);
				}
			}
			// Listener-owned inbound sockets take no slot and are never trimmed here.
			// Their keys already have a route, so they are never dialled again.
			ArrayList<AccountKey> ordered=new ArrayList<>(selected);
			Utils.shuffle(ordered);
			ordered.sort((a,b) -> Boolean.compare(activity.get(b).explicit,activity.get(a).explicit));
			for (AccountKey key:ordered) {
				if (dialling.size()+pendingConnections.size()+identifying>=MAX_CONCURRENT_DIALS) break;
				if (hasRouteOrAttempt(key)) continue;
				DesiredPeer desired=desiredPeers.get(key);
				if (desired==null || !desired.isRetryDue(now) || !hasDialTarget(desired)) continue;
				dialling.put(key,desired);
				attempts.add(desired);
			}
		}
		for (Convex peer:retired) closeSilently(peer);
		for (CompletableFuture<Void> waiter:disconnected) waiter.complete(null);
		for (EndpointIntent intent:identifications) startIdentification(intent);
		for (DesiredPeer desired:attempts) startDial(desired);
		List<CompletableFuture<Void>> isolated;
		synchronized (connectionLock) {
			isolated=takeIsolationWaiters();
		}
		for (CompletableFuture<Void> waiter:isolated) waiter.complete(null);
	}

	/**
	 * Stable healthy incumbents first; random sampling fills vacant ambient slots.
	 * Slots hold routes this node opens: a peer reached only inbound takes none, so
	 * peers connecting in cannot displace the ones this node chose. Lock held.
	 */
	private Set<AccountKey> selectPeers(long now) {
		HashSet<AccountKey> selected=new HashSet<>();
		ArrayList<AccountKey> recent=new ArrayList<>();
		ArrayList<AccountKey> ambient=new ArrayList<>();
		for (var entry:desiredPeers.entrySet()) {
			AccountKey key=entry.getKey();
			PeerActivity state=activity.get(key);
			if (state.explicit) selected.add(key);
			else if (state.lastActive!=Long.MIN_VALUE && now-state.lastActive<ACTIVE_RETENTION_MS
					&& !reachedOnlyInbound(key)) recent.add(key);
		}
		recent.sort((a,b) -> Long.compare(activity.get(b).lastActive,activity.get(a).lastActive));
		for (int i=0;i<Math.min(activePeers,recent.size());i++) selected.add(recent.get(i));
		for (var entry:desiredPeers.entrySet()) {
			AccountKey key=entry.getKey();
			if (selected.contains(key) || reachedOnlyInbound(key)) continue;
			if (hasRouteOrAttempt(key)
					|| (entry.getValue().isRetryDue(now) && hasDialTarget(entry.getValue()))) ambient.add(key);
		}
		Utils.shuffle(ambient);
		ambient.sort((a,b) -> Integer.compare(routeRank(a),routeRank(b)));
		for (int i=0;i<Math.min(ambientPeers,ambient.size());i++) selected.add(ambient.get(i));
		return selected;
	}

	private int routeRank(AccountKey key) {
		if (connections.containsKey(key)) return 0;
		return hasRouteOrAttempt(key) ? 1 : 2;
	}

	/** An upgraded inbound route with no route or attempt of this node's own. Lock held. */
	private boolean reachedOnlyInbound(AccountKey key) {
		return upgradedInboundRoutes.containsKey(key) && !connections.containsKey(key)
			&& !pendingConnections.containsKey(key) && !dialling.containsKey(key);
	}

	private boolean hasRouteOrAttempt(AccountKey key) {
		return connections.containsKey(key) || upgradedInboundRoutes.containsKey(key)
			|| pendingConnections.containsKey(key) || dialling.containsKey(key);
	}

	/** A route this node opened to the peer, or one it is opening. Lock held. */
	private boolean hasOutboundRouteOrAttempt(AccountKey key) {
		return connections.containsKey(key) || pendingConnections.containsKey(key) || dialling.containsKey(key);
	}

	/**
	 * Forgets held peers whose route has gone, and takes the waiters of peers with no
	 * route left, for completion outside the lock. Lock held.
	 */
	private void forgetLostRoutes(List<CompletableFuture<Void>> disconnected) {
		for (var it=activity.entrySet().iterator(); it.hasNext();) {
			var entry=it.next();
			AccountKey key=entry.getKey();
			if (!entry.getValue().held || hasOutboundRouteOrAttempt(key)) continue;
			it.remove();
			desiredPeers.remove(key);
			log.debug("Forgot held peer {}: its route has gone",key);
		}
		for (var it=disconnectWaiters.entrySet().iterator(); it.hasNext();) {
			var entry=it.next();
			if (hasOutboundRouteOrAttempt(entry.getKey())) continue;
			it.remove();
			disconnected.add(entry.getValue());
		}
	}

	/** No DNS or socket work on the maintenance thread. */
	private static boolean hasDialTarget(DesiredPeer peer) {
		return resolveTransport(peer)!=null;
	}

	private void startDial(DesiredPeer desired) {
		AccountKey key=desired.peerKey;
		try {
			openPeer(desired).whenComplete((peer,error) -> {
				boolean eligible;
				boolean failed;
				synchronized (connectionLock) {
					eligible=accepting && dialling.get(key)==desired && desiredPeers.containsKey(key);
					if (error!=null || !eligible) dialling.remove(key,desired);
					failed=eligible && error!=null;
					if (failed) recordRetryFailure(desiredPeers.get(key),clock.getAsLong());
				}
				if (failed) notifyFailure(key);
				if (error!=null || !eligible) {
					if (peer!=null) closeSilently(peer);
					maintenanceSignal.release();
					return;
				}
				// Retain the reservation until beginOutboundAdmission has installed
				// either a pending or a live connection, including concurrent callbacks.
				try { beginOutboundAdmission(key,peer); }
				finally {
					synchronized (connectionLock) { dialling.remove(key,desired); }
					maintenanceSignal.release();
				}
			});
		} catch (RuntimeException e) {
			boolean failed;
			synchronized (connectionLock) {
				failed=dialling.remove(key,desired);
				if (failed) recordRetryFailure(desired,clock.getAsLong());
			}
			if (failed) notifyFailure(key);
			maintenanceSignal.release();
		}
	}

	/** Overridable asynchronous transport boundary for deterministic policy tests. */
	CompletableFuture<Convex> openPeer(DesiredPeer desired) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				URI target=resolveTransport(desired);
				if (target==null) throw new IllegalArgumentException("No supported transport endpoint");
				return ConvexRemote.connect(target,Transports.forEndpoint(target,desired.peerKey),untrustedMessageLimit);
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		},workers);
	}

	/** Overridable transport boundary for a node whose key is not yet known. */
	CompletableFuture<Convex> openEndpoint(URI endpoint) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return ConvexRemote.connect(endpoint,Transports.forEndpoint(endpoint),untrustedMessageLimit);
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		},workers);
	}

	/** Dials an endpoint and asks the node there to prove a key, then admits it under that key. */
	private void startIdentification(EndpointIntent intent) {
		URI endpoint=intent.current;
		CompletableFuture<Convex> opened;
		try {
			opened=openEndpoint(endpoint);
		} catch (RuntimeException e) {
			opened=CompletableFuture.failedFuture(e);
		}
		opened.whenComplete((peer,error) -> {
			if (error!=null) {
				failIdentification(intent,error);
				return;
			}
			CompletableFuture<AccountKey> identified;
			try {
				identified=identify(peer,intent.expectedKey());
			} catch (RuntimeException e) {
				identified=CompletableFuture.failedFuture(e);
			}
			identified.whenComplete((key,failure) -> {
				try {
					if (failure==null && key!=null && intent.admits(key)) {
						admitIdentified(intent,key,peer);
						return;
					}
					closeSilently(peer);
					failIdentification(intent,(failure!=null) ? failure
						: (key==null) ? new SecurityException("The node at "+endpoint+" proved no key")
						: new SecurityException("The node at "+endpoint+" proved key "+key+", which "
							+intent.target+" does not list"));
				} catch (RuntimeException callbackFailure) {
					closeSilently(peer);
					failIdentification(intent,callbackFailure);
				}
			});
		});
	}

	/**
	 * Challenges a newly opened client for the one key expected of it or, with none
	 * expected, for whatever key it holds and then again naming that key: a node
	 * admitting challengers acts only on a challenge that names it, since one naming no
	 * key could be replayed to any node. The client stays in limbo, without store access
	 * or the trusted receive limit, until it is admitted.
	 */
	private CompletableFuture<AccountKey> identify(Convex peer,AccountKey expected) {
		AKeyPair kp=keyPair;
		if (kp==null) throw new IllegalStateException("Identifying a node by its endpoint needs this node's key pair");
		if (!accepting) throw new IllegalStateException("Connection manager is closed");
		restrictOutboundToLimbo(peer);
		peer.setKeyPair(kp);
		if (expected!=null) {
			CompletableFuture<AccountKey> proved=peer.verifyPeer(expected,Message.LATTICE_PEER_CHALLENGE_CONTEXT);
			if (proved==null) throw new IllegalStateException("Peer verification returned no future");
			return proved.orTimeout(ADMISSION_TIMEOUT_MS,TimeUnit.MILLISECONDS);
		}
		CompletableFuture<AccountKey> learned=peer.verifyPeer(null,Message.LATTICE_PEER_CHALLENGE_CONTEXT);
		if (learned==null) throw new IllegalStateException("Peer verification returned no future");
		return learned.thenCompose(key -> {
			// This node itself needs no second challenge: admission refuses it
			if (key==null || key.equals(kp.getAccountKey())) return CompletableFuture.completedFuture(key);
			CompletableFuture<AccountKey> named=peer.verifyPeer(key,Message.LATTICE_PEER_CHALLENGE_CONTEXT);
			return (named!=null) ? named : CompletableFuture.<AccountKey>failedFuture(
				new IllegalStateException("Peer verification returned no future"));
		}).orTimeout(ADMISSION_TIMEOUT_MS,TimeUnit.MILLISECONDS);
	}

	/**
	 * Admits the client of a node that proved a key. From {@link #connectPeer(URI)} the
	 * node becomes explicit intent at its endpoint; from {@link #connectPeer(PeerTarget)}
	 * it is held, unless it is already desired intent. A node that proves this node's
	 * own key is refused at once.
	 */
	private void admitIdentified(EndpointIntent intent,AccountKey key,Convex peer) {
		AKeyPair kp=keyPair;
		boolean self=kp!=null && key.equals(kp.getAccountKey());
		boolean held=intent.target!=null;
		// A held peer is never redialled, so it has no transport to dial
		DesiredPeer desired=held ? DesiredPeer.create(key) : DesiredPeer.create(key,intent.current);
		boolean current;
		boolean named;
		synchronized (connectionLock) {
			intent.dialling=false;
			current=accepting && endpointIntents.remove(intent.id(),intent);
			PeerActivity state=activity.get(key);
			named=state!=null && state.explicit && !state.held;
			if (current && !self) {
				// Desired intent already declared for the key keeps its transports
				registerDesiredPeerIntent(key,desired,!(held && named));
				if (held && !named) activity.get(key).held=true;
				// Reserve the peer as a dial does, so maintenance cannot dial it before admission
				dialling.put(key,desired);
			}
		}
		if (!current || self) {
			closeSilently(peer);
			// A closed manager has already failed the intent
			if (current) intent.admission.completeExceptionally(new IllegalArgumentException(
				"The node at "+intent.current+" is this node"));
			maintenanceSignal.release();
			return;
		}
		CompletableFuture<Convex> admission;
		try {
			admission=beginOutboundAdmission(key,peer);
		} catch (RuntimeException e) {
			closeSilently(peer);
			admission=CompletableFuture.failedFuture(e);
		} finally {
			synchronized (connectionLock) { dialling.remove(key,desired); }
			maintenanceSignal.release();
		}
		admission.whenComplete((admitted,error) -> {
			if (error==null) {
				// A caller that gave up meanwhile leaves no held peer behind
				if (!intent.admission.complete(admitted) && held && !named) releasePeer(key);
				return;
			}
			// The caller is told this failed, so the intent it caused goes too; a peer
			// already named explicitly keeps its own
			if (!named) releasePeer(key);
			intent.admission.completeExceptionally(error);
		});
	}

	/** Counts a failed attempt; once the attempts run out, the intent fails and is dropped. */
	private void failIdentification(EndpointIntent intent,Throwable error) {
		Throwable cause=(error instanceof java.util.concurrent.CompletionException && error.getCause()!=null)
			? error.getCause() : error;
		String reason=(cause.getMessage()!=null) ? cause.getMessage() : cause.getClass().getSimpleName();
		boolean exhausted=false;
		int attempts;
		synchronized (connectionLock) {
			intent.dialling=false;
			attempts=intent.maxAttempts();
			if (endpointIntents.get(intent.id())==intent) {
				intent.failCount++;
				exhausted=intent.failCount>=attempts;
				if (exhausted) endpointIntents.remove(intent.id());
				else intent.nextRetryTime=clock.getAsLong()+calculateBackoff(intent.failCount);
			}
		}
		if (exhausted) {
			String what=(intent.target==null) ? "No node at "+intent.current+" proved a key"
				: "Peer "+intent.target+" was not admitted";
			intent.admission.completeExceptionally(new IllegalStateException(what+" in "+attempts
				+" attempts: "+reason,cause));
		} else {
			log.debug("Attempt to identify the node at {} failed: {}",intent.current,reason);
		}
		maintenanceSignal.release();
	}

	/** Probes are correlated requests, independently of activity used for peer selection. */
	CompletableFuture<?> probePeer(Object route) {
		return CompletableFuture.supplyAsync(() -> {
			if (route instanceof Convex peer) return peer.ping().thenApply(timestamp -> {
				if (timestamp==null) throw new IllegalStateException("Invalid PING response");
				return timestamp;
			});
			return inboundProbe.apply((AConnection)route);
		},workers).thenCompose(future -> future);
	}

	private void checkHealth(long now) {
		List<Map.Entry<AccountKey,HealthCheck>> probes=new ArrayList<>();
		List<Map.Entry<AccountKey,HealthCheck>> failures=new ArrayList<>();
		List<AccountKey> heard=new ArrayList<>();
		long quiet=keepAlive;
		synchronized (connectionLock) {
			Map<AccountKey,Object> routes=new HashMap<>(upgradedInboundRoutes);
			routes.putAll(connections);
			for (var entry:new ArrayList<>(health.entrySet())) {
				Object current=routes.get(entry.getKey());
				if (current==null) failures.add(entry);
				else if (current!=entry.getValue().route) health.remove(entry.getKey());
			}
			for (var entry:routes.entrySet()) {
				AccountKey key=entry.getKey();
				Object route=entry.getValue();
				HealthCheck check=health.computeIfAbsent(key,k -> new HealthCheck(route,now));
				if (check.probing) {
					if (now-check.probeStarted>=PROBE_TIMEOUT_MS) {
						failures.add(Map.entry(key,check));
					}
				} else if (now-check.lastReceived>=quiet) {
					if (route instanceof Convex || inboundProbe!=null) {
						check.probing=true;
						check.probeStarted=now;
						probes.add(Map.entry(key,check));
					}
				} else if (now-check.lastReported>=quiet) {
					// Heard from within the quiet interval: still in contact
					check.lastReported=now;
					heard.add(key);
				}
			}
		}
		for (AccountKey key:heard) notifyContact(key);
		for (var entry:failures) failRoute(entry.getKey(),entry.getValue());
		for (var entry:probes) {
			AccountKey key=entry.getKey();
			HealthCheck check=entry.getValue();
			long generation=check.probeStarted;
			try {
				probePeer(check.route).whenComplete((value,error) -> {
					synchronized (connectionLock) {
						if (health.get(key)!=check || !check.probing || check.probeStarted!=generation) return;
						if (error==null) {
							check.lastReceived=clock.getAsLong();
							check.probing=false;
						}
					}
					// A request timeout or send failure alone does not prove the peer
					// dead. Allow the full grace period for propagation or a late reply.
					if (error!=null) maintenanceSignal.release();
				});
			} catch (RuntimeException e) { maintenanceSignal.release(); }
		}
	}

	/** A late failed probe can never retire a replacement connection. */
	private void failRoute(AccountKey key,HealthCheck check) {
		boolean close=false;
		boolean retireInbound=false;
		boolean failed=false;
		synchronized (connectionLock) {
			if (health.get(key)!=check) return;
			Object current=connections.get(key);
			if (current==null) current=upgradedInboundRoutes.get(key);
			// Traffic may have resumed since maintenance collected the timeout.
			if (current==check.route && (!check.probing
					|| clock.getAsLong()-check.probeStarted<PROBE_TIMEOUT_MS)) return;
			health.remove(key,check);
			if (current!=null && current!=check.route) return;
			if (check.route instanceof Convex peer) close=connections.remove(key,peer);
			else retireInbound=upgradedInboundRoutes.remove(key,check.route);
			DesiredPeer desired=desiredPeers.get(key);
			failed=desired!=null;
			if (failed) recordRetryFailure(desired,clock.getAsLong());
		}
		if (failed) notifyFailure(key);
		if (close) closeSilently((Convex)check.route);
		// The endpoint owns socket failure handling. In particular, a silent NAT
		// return route must disconnect so its remote owner can reconnect it.
		if (retireInbound && inboundFailure!=null) inboundFailure.accept((AConnection)check.route);
		maintenanceSignal.release();
	}

	private static final class PeerActivity {
		boolean explicit;
		/**
		 * Admitted from a {@link PeerTarget}: explicit while its route lasts, never dialled
		 * by maintenance nor given a discovered address, and forgotten once the route goes.
		 */
		boolean held;
		long lastActive=Long.MIN_VALUE;
		int requests;
	}

	private static final class HealthCheck {
		final Object route;
		long lastReceived;
		/** When contact was last reported for this route; admission reports it first. */
		long lastReported;
		boolean probing;
		long probeStarted;
		HealthCheck(Object route,long now) { this.route=route; lastReceived=now; lastReported=now; }
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

	/** Returns the first supported endpoint without resolving DNS or losing its scheme. */
	static URI resolveTransport(DesiredPeer desired) {
		return resolveTransport(desired.transports);
	}

	private static URI resolveTransport(AVector<AString> transports) {
		if (transports == null || transports.isEmpty()) return null;

		for (long i = 0; i < transports.count(); i++) {
			AString uri = (AString) transports.get(i);
			if (uri == null) continue;
			try {
				URI endpoint=Transports.endpoint(uri.toString());
				Transports.forEndpoint(endpoint);
				return endpoint;
			} catch (IllegalArgumentException e) {
				// Try the next advertised transport.
			}
		}
		return null;
	}

	// ========== Backoff Calculation ==========

	/**
	 * Calculates bounded exponential retry delay with full lower-half jitter, the
	 * backoff this manager uses and callers that own their retries share.
	 *
	 * @param failCount failures so far, at least one
	 * @return delay before the next attempt in milliseconds
	 */
	public static long calculateBackoff(int failCount) {
		long base = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << Math.min(failCount - 1, 10));
		long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
		return base / 2 + jitter;
	}

	// ========== Verification ==========

	/** Completes the limbo-to-active transition after the expected key was proved. */
	private void completeVerifiedAdmission(AccountKey peerKey, PendingConnection pending) {
		Convex replaced=null;
		List<CompletableFuture<Void>> met=List.of();
		boolean eligible;
		synchronized (connectionLock) {
			eligible=accepting && pendingConnections.get(peerKey) == pending
				&& desiredPeers.containsKey(peerKey) && pending.connection.isConnected();
			if (eligible) {
				grantOutboundCapabilities(pending.connection);
				configureReceiveLimit(pending.connection, true);
				replaced = connections.put(peerKey, pending.connection);
				health.put(peerKey,new HealthCheck(pending.connection,clock.getAsLong()));
				pendingConnections.remove(peerKey, pending);
				met=takeMetAmbientWaiters();
			}
		}
		if (!eligible) {
			rejectPendingAdmission(peerKey, pending,
				new IllegalStateException("Verified connection is no longer eligible for admission"));
			return;
		}
		for (CompletableFuture<Void> waiter:met) waiter.complete(null);
		if (replaced != null && replaced != pending.connection) closeSilently(replaced);
		resetRetryBackoff(peerKey);
		notifyContact(peerKey);
		completePeerWaiter(peerKey,pending.connection);
		notifyPeerAdmitted(peerKey,pending.connection);
		pending.admission.complete(pending.connection);
		log.debug("Verified and admitted peer {} at {}", peerKey, pending.connection.getHostAddress());
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
		if (desired != null) {
			recordRetryFailure(desired, clock.getAsLong());
			notifyFailure(peerKey);
		}
		maintenanceSignal.release();
		log.debug("Rejected peer {} after failed identity challenge: {}", peerKey, failure.getMessage());
	}

	/** Rejects limbo clients whose sockets closed before identity verification. */
	private void pruneDeadPendingConnections() {
		for (Map.Entry<AccountKey, PendingConnection> entry : pendingConnections.entrySet()) {
			PendingConnection pending = entry.getValue();
			if (!pending.connection.isConnected() || clock.getAsLong()-pending.started>=ADMISSION_TIMEOUT_MS) {
				rejectPendingAdmission(entry.getKey(), pending,
					new IllegalStateException("Connection closed or timed out awaiting identity verification"));
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
		final long started;
		final CompletableFuture<Convex> admission = new CompletableFuture<>();

		PendingConnection(Convex connection,long started) {
			this.connection = connection;
			this.started=started;
		}
	}

	/** Intent to reach a node by its endpoints, until it proves a key it may be admitted under. Lock held. */
	private static final class EndpointIntent {
		/** The resolved peer, or null for a node known only by its endpoint. */
		final PeerTarget target;
		/** Endpoints to dial, in turn. */
		final List<URI> endpoints;
		final CompletableFuture<Convex> admission = new CompletableFuture<>();
		/** Failed attempts so far; the intent is dropped at {@link #maxAttempts()}. */
		int failCount;
		/** Earliest monotonic maintenance time for the next attempt. */
		long nextRetryTime;
		/** Whether an attempt is in flight, holding one of the concurrent dial slots. */
		boolean dialling;
		/** Endpoint of the attempt in flight or last made. */
		URI current;

		EndpointIntent(URI endpoint) {
			this.target=null;
			this.endpoints=List.of(endpoint);
			this.current=endpoint;
		}

		EndpointIntent(PeerTarget target,List<URI> endpoints) {
			this.target=target;
			this.endpoints=List.copyOf(endpoints);
			this.current=endpoints.get(0);
		}

		/** Key in {@code endpointIntents}: one intent per endpoint, or each target its own. */
		Object id() {
			return (target==null) ? endpoints.get(0) : this;
		}

		/** Each endpoint is tried at least once. */
		int maxAttempts() {
			return Math.max(MAX_ENDPOINT_ATTEMPTS,endpoints.size());
		}

		/** The one key to challenge for, or null to learn the node's key first. */
		AccountKey expectedKey() {
			return (target!=null && target.keys().count()==1) ? target.keys().get(0) : null;
		}

		boolean admits(AccountKey key) {
			return (target==null) || target.admits(key);
		}
	}

	// ========== DesiredPeer ==========

	/**
	 * Explicit connection intent or a discovered candidate for one remote node identity.
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
		/** Earliest monotonic maintenance time at which this peer may be retried. */
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
		public static DesiredPeer create(AccountKey peerKey, InetSocketAddress address) {
			return create(peerKey,Transports.endpoint(address));
		}

		/** Creates operator-supplied intent retaining the endpoint's transport scheme. */
		public static DesiredPeer create(AccountKey peerKey, URI endpoint) {
			AVector<AString> transports=Vectors.of(Strings.create(endpoint.toString()));
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
