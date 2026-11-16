package convex.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

import convex.api.AConvexConnected;
import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.BeliefMerge;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.MisbehaviourException;
import convex.core.exceptions.ValidationException;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.Cleanup;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;
import convex.net.PeerConnectionException;
import convex.net.PeerRefusedException;
import convex.net.RequestFailures;
import convex.net.Transports;

/**
 * Manages outbound peer connections for consensus Belief propagation.
 *
 * <p>Connections are stake-weighted: peers are selected randomly proportional
 * to their stake, and low-stake peers are probabilistically dropped to make
 * room for higher-stake alternatives. This ensures the connection pool
 * represents the economically relevant part of the network.
 *
 * <p>If a connection fails, it is pruned and a <b>new</b> random peer is
 * selected on the next maintenance cycle — no reconnection to the same peer.
 * For consensus this is essential: we cannot afford waiting on bad peers.
 *
 * <p>Extends {@link AConnectionManager} for shared connection infrastructure.
 */
public class ConnectionManager extends AConnectionManager {

	private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class.getName());

	/** Pause for each iteration of the connection maintenance loop. */
	static final long SERVER_CONNECTION_PAUSE = 500;

	/** Default pause before polling for beliefs. */
	static final long SERVER_POLL_DELAY = 2000;

	/** Timeout for a belief poll status request. */
	static final long POLL_TIMEOUT_MILLIS = 2000;

	/** Timeout for acquiring a belief after polling. */
	static final long POLL_ACQUIRE_TIMEOUT_MILLIS = 12000;

	/** Grace period before repeated STATUS timeouts retire an active connection. */
	static final long UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS = 60000;

	protected final Server server;

	private final SecureRandom random = new SecureRandom();
	/** Also read by the Belief propagator, to bound a fetch of missing data. */
	private volatile long pollDelay = SERVER_POLL_DELAY;
	private long lastPollTime;
	private long lastConnectionUpdate = Utils.getCurrentTimestamp();

	/** First STATUS timeout for each connection which has not responded since. */
	private final ConcurrentHashMap<AccountKey, Long> unresponsiveSince = new ConcurrentHashMap<>();

	/** Manager-owned sockets currently awaiting remote peer identification. */
	private final Set<Convex> pendingConnections=ConcurrentHashMap.newKeySet();

	/** One per site, so each warns once of each kind of failure that warrants it. */
	private final RequestFailures pollFailures=new RequestFailures(log);
	private final RequestFailures connectFailures=new RequestFailures(log);

	/** Background thread for connection maintenance and belief polling. */
	private Thread thread;

	/** Prevents late asynchronous connection attempts from being admitted after close. */
	private volatile boolean closed;

	public ConnectionManager(Server server) {
		this.server = server;
	}

	// ========== Lifecycle ==========

	/**
	 * Starts the connection manager's background maintenance thread.
	 */
	public synchronized void start() {
		if (closed) throw new IllegalStateException("Connection manager is closed");
		if (thread != null) return;
		Object _pollDelay = server.getConfig().get(Keywords.POLL_DELAY);
		this.pollDelay = (_pollDelay == null) ? SERVER_POLL_DELAY : Utils.toInt(_pollDelay);
		lastPollTime = server.getTimestamp();

		thread = Thread.ofVirtual().name("Connection Manager thread at " + server.getPort()).start(() -> {
			LoopFailures failures=new LoopFailures(log,server,"connection manager");
			while (server.isRunning() && !Thread.currentThread().isInterrupted()) {
				try {
					LoadMonitor.down();
					Thread.sleep(SERVER_CONNECTION_PAUSE);
					LoadMonitor.up();
					maintainConnections();
					maybePollBelief();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (Throwable e) {
					if (!failures.handle(e)) break;
				}
			}
		});
	}

	@Override
	public void close() {
		// Stop and close outside the lock: the maintenance thread and identification
		// callbacks on event loops take it, and closing a connection waits for its loop
		Thread managerThread;
		synchronized (this) {
			if (closed) return;
			closed = true;
			managerThread=thread;
			thread=null;
		}

		if (managerThread != null) {
			managerThread.interrupt();
			if (managerThread != Thread.currentThread()) {
				boolean interrupted=false;
				while (managerThread.isAlive()) {
					try {
						managerThread.join();
					} catch (InterruptedException e) {
						interrupted=true;
						managerThread.interrupt();
					}
				}
				if (interrupted) Thread.currentThread().interrupt();
			}
		}

		// Broadcast GOODBYE after maintenance has stopped, then close every route.
		Cleanup cleanup=new Cleanup();
		cleanup.run(() -> broadcast(Message.createGoodBye()));
		for (Convex pending : pendingConnections) cleanup.run(() -> closeSilently(pending));
		pendingConnections.clear();
		cleanup.run(this::closeAllConnections);
		unresponsiveSince.clear();
		cleanup.finish();
	}

	public double getLoad() {
		return LoadMonitor.getLoad(thread);
	}

	protected AStore getStore() {
		return server.getStore();
	}

	// ========== Connection Management ==========

	public boolean addConnection(AccountKey peerKey, Convex convex) {
		if (peerKey == null) throw new IllegalArgumentException("Peer key must not be null");
		if (convex == null) throw new IllegalArgumentException("Connection must not be null");
		Convex replaced=null;
		boolean accepted;
		synchronized (this) {
			accepted=!closed && convex.isConnected();
			if (accepted) {
				log.debug("Connected to Peer: {} at {}", peerKey, convex.getHostAddress());
				// A peer we chose to connect to is buffered for, not dropped, until it is far behind
				convex.setOutboundLimits(Config.PEER_OUTBOUND_QUEUE_SIZE, Config.PEER_OUTBOUND_QUEUE_BYTE_LIMIT);
				// The peer fetches data missing from our updates back along this connection.
				// It is served as for any inbound connection, so nothing new is exposed.
				if (convex instanceof AConvexConnected connected) {
					connected.setDataRequestDelivery(server::deliverDataRequest);
				}
				unresponsiveSince.remove(peerKey);
				replaced=connections.put(peerKey, convex);
			}
		}
		if (!accepted) {
			closeSilently(convex);
			return false;
		}
		if (replaced != null && replaced != convex) closeSilently(replaced);
		return true;
	}

	// ========== Maintenance ==========

	protected void maintainConnections() throws InterruptedException {
		// Prune dead connections first
		pruneDeadConnections();
		unresponsiveSince.keySet().removeIf(key -> !connections.containsKey(key));

		State s = server.getPeer().getConsensusState();
		long now = Utils.getCurrentTimestamp();
		long millisSinceLastUpdate = Math.max(0, now - lastConnectionUpdate);

		int targetPeerCount = getTargetPeerCount();
		int currentPeerCount = connections.size();
		double totalStake = s.computeStakes().get(null);

		AccountKey[] peers = connections.keySet().toArray(new AccountKey[0]);
		for (AccountKey p : peers) {
			Convex conn = getConnection(p);
			if (conn == null) {
				currentPeerCount--;
				continue;
			}

			// Always remove Peers not staked in consensus
			PeerStatus ps = s.getPeer(p);
			if ((ps == null) || (ps.getBalance() <= CPoSConstants.MINIMUM_EFFECTIVE_STAKE)) {
				closeConnection(p, "Insufficient stake");
				currentPeerCount--;
				continue;
			}

			// Drop Peers randomly if they have a small stake
			if ((millisSinceLastUpdate > 0) && (currentPeerCount >= targetPeerCount)) {
				double prop = ps.getTotalStakeShares() / totalStake;
				double keepChance = Math.min(1.0, prop * targetPeerCount);

				if (keepChance < 1.0) {
					double dropRate = millisSinceLastUpdate / (double) Config.PEER_CONNECTION_DROP_TIME;
					if (random.nextDouble() < (dropRate * (1.0 - keepChance))) {
						closeConnection(p, "Dropping minor peers");
						currentPeerCount--;
						continue;
					}
				}
			}
		}

		// Connect to new peers if below target
		currentPeerCount = connections.size();
		if (currentPeerCount < targetPeerCount) {
			tryRandomConnect(s);
		}

		lastConnectionUpdate = Utils.getCurrentTimestamp();
	}

	private void tryRandomConnect(State s) {
		// SECURITY: stake weighted connection is important to avoid bad / irrelevant peers
		// influencing the connection pool

		Set<AArrayBlob> potentialPeers = s.getPeers().keySet();
		URI target = null;
		AccountKey targetKey = null;
		double accStake = 0.0;
		for (ACell c : potentialPeers) {
			AccountKey peerKey = RT.ensureAccountKey(c);
			if (connections.containsKey(peerKey)) continue;
			if (server.getPeerKey().equals(peerKey)) continue;

			PeerStatus ps = s.getPeer(peerKey);
			if (ps == null) continue;
			AString hostName = ps.getHostname();
			if (hostName == null) continue;
			URI maybeAddress;
			try {
				maybeAddress=Transports.endpoint(hostName.toString());
				Transports.forEndpoint(maybeAddress);
			} catch (IllegalArgumentException e) {
				continue;
			}

			long peerStake = ps.getPeerStake();
			if (peerStake > CPoSConstants.MINIMUM_EFFECTIVE_STAKE) {
				double t = random.nextDouble() * (accStake + peerStake);
				if (t >= accStake) {
					target = maybeAddress;
					targetKey = peerKey;
				}
				accStake += peerStake;
			}
		}

		if (target != null) {
			URI connectTarget = target;
			connectToPeer(target,targetKey).exceptionally(e -> {
				connectFailures.log(e,"Failed to connect to Peer at {}",connectTarget);
				return null;
			});
		}
	}

	private int getTargetPeerCount() {
		Integer target;
		try {
			target = Utils.toInt(server.getConfig().get(Keywords.OUTGOING_CONNECTIONS));
		} catch (IllegalArgumentException ex) {
			target = null;
		}
		if (target == null) target = Config.DEFAULT_OUTGOING_CONNECTION_COUNT;
		return target;
	}

	// ========== Belief Polling ==========

	private void maybePollBelief() throws InterruptedException {
		long now=server.getTimestamp();
		if (now-lastPollTime<pollDelay) return;
		if (!server.getBeliefPropagator().hasBeliefPollCapacity()) return;

		ArrayList<AccountKey> peers = new ArrayList<>(connections.keySet());
		if (peers.isEmpty()) return;

		AccountKey peerKey = peers.get(random.nextInt(peers.size()));
		Convex connection = getConnection(peerKey);
		if (connection==null) return;

		try {
			pollBelief(peerKey,connection);
		} finally {
			// Space attempts from completion, including slow or failed acquisitions.
			lastPollTime=server.getTimestamp();
		}
	}

	/** Polls one outbound peer for anti-entropy and connection health. */
	void pollBelief(AccountKey peerKey, Convex connection) throws InterruptedException {
		try {
			Result result = connection.requestStatusSync(POLL_TIMEOUT_MILLIS);
			if (result.isError()) {
				handleStatusFailure(peerKey,connection,result);
				return;
			}
			unresponsiveSince.remove(peerKey);

			AMap<Keyword, ACell> status = API.ensureStatusMap(result.getValue());
			if (status == null) throw new BadFormatException("Status reply is not a status");
			Hash h = RT.ensureHash(status.get(Keywords.BELIEF));
			if (h==null) throw new BadFormatException("Status reply has no Belief hash");
			if (h.equals(server.getBelief().getHash())) return;
			if (!server.getBeliefPropagator().hasBeliefPollCapacity()) return;

			ACell acquired;
			try {
				acquired = connection.acquire(h).get(POLL_ACQUIRE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			} catch (ExecutionException e) {
				// Bad input in the polled peer's reply goes to the handler below
				if (e.getCause() instanceof ValidationException bad) throw bad;
				throw e;
			}
			if (!(acquired instanceof Belief belief)) throw new BadFormatException("Advertised Belief is not a Belief");
			// Checked here, where the polled peer is known
			State state=server.getPeer().getConsensusState();
			for (SignedData<Order> so: Belief.extractOrders(belief)) {
				// Only the Orders the merge would check: unstaked keys it drops unchecked
				if (!BeliefMerge.isValidPeer(state,so.getAccountKey())) continue;
				if (!so.checkSignature()) throw new BadSignatureException("Bad Order signature in polled Belief",so);
			}
			if (!server.queueBelief(belief)) {
				log.debug("Discarding acquired Belief because the local queue filled during polling");
			}
		} catch (InterruptedException e) {
			// Before the catch-all: an interrupt stops the manager, it is not a failed poll
			throw e;
		} catch (ValidationException e) {
			// The polled peer's own input, and the poll's other steps throw none
			handleBadInput(peerKey,connection,e);
		} catch (Throwable e) {
			Utils.checkRequestFailure(e);
			// Failures while closing are expected
			if (server.isLive()) pollFailures.log(e,"Belief poll of {} failed",peerKey);
		}
	}

	/** Ages out a connection only when connectivity failures persist. */
	private void handleStatusFailure(AccountKey peerKey, Convex connection, Result result) {
		ACell code=result.getErrorCode();
		if (!ErrorCodes.TIMEOUT.equals(code) && !ErrorCodes.CONNECT.equals(code)) {
			// A LOAD or application error is not evidence that the remote endpoint vanished.
			unresponsiveSince.remove(peerKey);
			log.debug("Peer status probe returned {}",result);
			return;
		}
		if (connections.get(peerKey)!=connection) return;

		long now=server.getTimestamp();
		long since=unresponsiveSince.computeIfAbsent(peerKey,key -> now);
		long unavailableFor=Math.max(0,now-since);
		if (unavailableFor>=UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS) {
			unresponsiveSince.remove(peerKey);
			closeConnection(peerKey,connection,"No STATUS response for "+unavailableFor+" ms");
		} else {
			log.debug("Peer status probe failed after {} ms unavailable: {}",unavailableFor,result);
		}
	}

	// ========== Peer Connection ==========

	/**
	 * Connects explicitly to a Peer at the given host address. Attempts
	 * challenge/response verification; falls back to status-based (untrusted)
	 * identification if verification is not supported.
	 *
	 * @param hostAddress Address to connect to
	 * @return Future completing with the Convex connection, or exceptionally on failure
	 */
	public CompletableFuture<Convex> connectToPeer(InetSocketAddress hostAddress) {
		return connectToPeer(Transports.endpoint(hostAddress));
	}

	/** Connects to an explicit transport endpoint and performs normal peer verification. */
	public CompletableFuture<Convex> connectToPeer(URI hostAddress) {
		return connectToPeer(hostAddress,null);
	}

	/** Authenticates known TLS peers by key; TCP retains its existing identification policy. */
	public CompletableFuture<Convex> connectToPeer(URI hostAddress, AccountKey expectedPeer) {
		CompletableFuture<Convex> result = new CompletableFuture<>();
		if (closed) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("Connection manager is closed"));
		}

		Convex opened=null;
		try {
			opened = ConvexRemote.connect(hostAddress,Transports.forEndpoint(hostAddress,expectedPeer));
			Convex convex=opened;
			synchronized (this) {
				if (closed) {
					convex.close();
					return CompletableFuture.failedFuture(
						new IllegalStateException("Connection manager is closed"));
				}
				pendingConnections.add(convex);
			}
			convex.setStore(server.getStore());
			convex.setKeyPair(server.getKeyPair());

			boolean requireExpectedPeer=expectedPeer!=null && "tls".equalsIgnoreCase(hostAddress.getScheme());
			CompletableFuture<AccountKey> identity=requireExpectedPeer
				? convex.verifyPeer(expectedPeer,server.getPeer().getNetworkID()) : identifyPeer(convex);
			identity.whenComplete((peerKey, ex) -> {
				pendingConnections.remove(convex);
				Throwable cause=(ex!=null)?PeerConnectionException.unwrap(ex):null;
				if (cause instanceof ValidationException bad) handleBadInput(null,convex,bad);
				if (cause instanceof MisbehaviourException) {
					result.completeExceptionally(new PeerRefusedException("Peer at "+hostAddress+" failed identity challenge",cause));
					return;
				}
				if (peerKey == null || ex != null) {
					convex.close();
					result.completeExceptionally(ex != null ? ex
						: new IOException("Unable to identify peer at " + hostAddress));
					return;
				}

				Convex existing=null;
				synchronized (ConnectionManager.this) {
					// Once closed, an existing route is about to be closed too
					if (!closed) {
						existing=getConnection(peerKey);
						if (existing == null && addConnection(peerKey, convex)) existing=convex;
					}
				}
				if (existing == null) {
					convex.close();
					result.completeExceptionally(new IllegalStateException(
						"Connection manager closed during peer identification"));
					return;
				}
				if (existing != convex) convex.close();
				result.complete(existing);
			});
		} catch (Throwable e) {
			// First, so the connection is not left open even for a JVM failure
			if (opened!=null) pendingConnections.remove(opened);
			closeSilently(opened);
			// Also restores an interrupt, which throwing cleared, for the caller
			Utils.checkRequestFailure(e);
			result.completeExceptionally(e);
		}
		return result;
	}

	private CompletableFuture<AccountKey> identifyPeer(Convex convex) {
		Peer peer = server.getPeer();
		Hash networkID = peer.getNetworkID();

		return convex.verifyPeer(null, networkID).thenCompose(verified -> {
			if (verified != null) {
				log.info("Verified peer: {} at {}", verified, convex.getHostAddress());
				return CompletableFuture.completedFuture(verified);
			}
			return fallbackIdentify(convex);
		}).exceptionallyCompose(ex -> {
			Throwable cause=PeerConnectionException.unwrap(ex);
			// A reply that proves misbehaviour is never overridden by the remote's own status
			if (cause instanceof MisbehaviourException) return CompletableFuture.failedFuture(ex);
			if (cause instanceof ValidationException bad) handleBadInput(null,convex,bad);
			log.debug("Peer verification not available at {}: {}", convex.getHostAddress(), ex.getMessage());
			return fallbackIdentify(convex);
		});
	}

	private CompletableFuture<AccountKey> fallbackIdentify(Convex convex) {
		return convex.requestStatus().thenApply(result -> {
			if (result.isError()) {
				log.info("Bad status from remote peer: {}", result);
				return null;
			}
			AMap<Keyword, ACell> status = API.ensureStatusMap(result.getValue());
			if (status == null) return null;
			AccountKey peerKey = RT.ensureAccountKey(status.get(Keywords.PEER));
			if (peerKey != null) {
				log.info("Identified peer via status (unverified): {} at {}", peerKey, convex.getHostAddress());
			}
			return peerKey;
		});
	}

	// ========== Challenge Handling ==========

	/**
	 * Processes an incoming CHALLENGE message. Validates contextID against
	 * this peer's network ID if present.
	 */
	public void processChallenge(Message m, Peer thisPeer) {
		Hash networkID = thisPeer.getNetworkID();
		m.respondToChallenge(thisPeer.getKeyPair(), ctx -> {
			Hash h = RT.ensureHash(ctx);
			return (h == null) || h.equals(networkID);
		});
		// After the reply, for a local client that stays open; a remote one may be closed first
		AVector<ACell> v=RT.ensureVector(m.getPayload());
		if ((v==null)||(v.count()!=3)||!(v.get(2) instanceof SignedData<?> challenge)) {
			handleBadInput(m.getConnection(),new BadFormatException("Invalid challenge format"));
		} else if (!challenge.checkSignature()) {
			handleBadInput(m.getConnection(),new BadSignatureException("Bad challenge signature",challenge));
		}
	}

	// ========== Bad input ==========

	/**
	 * Handles bad input that the remote end of a connection sent, given the failure that
	 * rejected it. False input, a {@link MisbehaviourException}, closes the connection
	 * unless it is local; malformed input keeps it.
	 *
	 * @param connection Connection the input came on
	 * @param failure Failure that rejected the input
	 */
	public void handleBadInput(AConnection connection, ValidationException failure) {
		if (connection==null) return;
		if (!disconnects(failure)) {
			log.debug("Rejected malformed input from {}: {}",connection.getRemoteAddress(),failure.getMessage());
			return;
		}
		// A local connection is this process's own client, such as the REST API, relaying
		// for others it must handle itself
		if (connection instanceof LocalConnection) {
			log.debug("Misbehaviour on a local connection, kept open: {}",failure.getMessage());
			return;
		}
		log.debug("Closing connection to {} after misbehaviour: {}",connection.getRemoteAddress(),failure.getMessage());
		connection.close();
	}

	/**
	 * Handles bad input from a peer this peer connected to, as
	 * {@link #handleBadInput(AConnection, ValidationException)} does for any connection.
	 *
	 * @param peerKey Key the peer was connected under, or null if it was not yet connected
	 * @param connection Connection to the peer
	 * @param failure Failure that rejected the input
	 */
	void handleBadInput(AccountKey peerKey, Convex connection, ValidationException failure) {
		if (!disconnects(failure)) {
			log.debug("Rejected malformed input from peer {}: {}",peerKey,failure.getMessage());
			return;
		}
		// Closed even if no longer mapped to its key: the misbehaving connection must go
		if (!closeConnection(peerKey,connection,"Misbehaviour: "+failure.getMessage())) closeSilently(connection);
	}

	/** This peer's policy: whether bad input closes the connection it came on. */
	private static boolean disconnects(ValidationException failure) {
		return failure instanceof MisbehaviourException;
	}

	/**
	 * Gets the pause between Belief polls, from the {@code :poll-delay} setting
	 *
	 * @return Poll delay in milliseconds
	 */
	long getPollDelay() {
		return pollDelay;
	}
}
