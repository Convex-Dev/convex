package convex.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

import convex.api.AConvexConnected;
import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;
import convex.net.Transports;

/**
 * Manages outbound peer connections for consensus Belief propagation.
 *
 * <p>Connections are stake-weighted: peers are selected randomly proportional
 * to their stake, and low-stake peers are probabilistically dropped to make
 * room for higher-stake alternatives. This ensures the connection pool
 * represents the economically relevant part of the network.
 *
 * <p>Links are verified by challenge and polled for Beliefs, verified links
 * first. A peer that fails to connect, verify or serve a poll backs off
 * exponentially, and a link failing for long is closed, so its place goes to
 * another peer picked by stake: for consensus we cannot afford waiting on bad
 * peers. See {@code PEER_PRIORITY.md}, Polling and Back-off.
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

	/** Grace period before a link that answers neither STATUS nor a challenge is closed. */
	static final long UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS = 60000;

	/** Doublings of a peer's wait after consecutive failures: the longest wait is 256 poll delays. */
	static final int MAX_BACKOFF_DOUBLINGS = 8;

	/**
	 * Back-off of one remote peer: its consecutive failures, the time from which its next
	 * attempt is allowed, and whether the latest failure was a failure to reach it at all.
	 */
	private record Backoff(int failures, long due, boolean unreachable) {}

	/** A challenge sent on a link not yet verified, and the time its answer is due by. */
	private record Challenge(Convex link, CompletableFuture<AccountKey> answer, long deadline) {}

	/** Outcome of one poll, as it counts towards the polled peer's back-off. */
	private enum PollOutcome { GOOD, UNREACHABLE, ANSWERED_WRONGLY, NO_FAULT }

	protected final Server server;

	private final SecureRandom random = new SecureRandom();
	/** Also read by the Belief propagator, to bound a fetch of missing data. */
	private volatile long pollDelay = SERVER_POLL_DELAY;
	private long lastPollTime;
	private long lastConnectionUpdate = Utils.getCurrentTimestamp();

	/** First request without a reply for each connection which has not replied since. */
	private final ConcurrentHashMap<AccountKey, Long> unresponsiveSince = new ConcurrentHashMap<>();

	/** Back-off of each remote peer that has failed since its last good poll. */
	private final ConcurrentHashMap<AccountKey, Backoff> backoffs = new ConcurrentHashMap<>();

	/** Challenges in flight on unverified links, read by later maintenance passes. */
	private final ConcurrentHashMap<AccountKey, Challenge> challenges = new ConcurrentHashMap<>();

	/** Manager-owned sockets currently awaiting remote peer identification. */
	private final Set<Convex> pendingConnections=ConcurrentHashMap.newKeySet();

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
				} catch (Exception e) {
					log.error("Unexpected error in connection manager loop", e);
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
		try {
			Message msg = Message.createGoodBye();
			broadcast(msg);
		} finally {
			for (Convex pending : pendingConnections) closeSilently(pending);
			pendingConnections.clear();
			closeAllConnections();
			unresponsiveSince.clear();
			backoffs.clear();
			challenges.clear();
		}
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
				// A new link is tried at once, but its peer keeps its failures until it serves a poll
				makeDue(peerKey);
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

		// After the stake checks, so no challenge goes to a link about to close
		verifyDueLinks();
		// A peer's back-off lasts while it is linked or may be dialled again
		backoffs.keySet().removeIf(key -> !connections.containsKey(key) && !isStakedPeer(s,key));
		challenges.keySet().removeIf(key -> !connections.containsKey(key));

		// Connect to new peers if below target
		currentPeerCount = connections.size();
		if (currentPeerCount < targetPeerCount) {
			tryRandomConnect(s);
		}

		lastConnectionUpdate = Utils.getCurrentTimestamp();
	}

	/**
	 * Dials one peer that is not linked, picked by stake among those whose wait has passed.
	 *
	 * @param s Consensus State to pick from
	 * @return The dial, or null if no peer was picked
	 */
	CompletableFuture<Convex> tryRandomConnect(State s) {
		// SECURITY: stake weighted connection is important to avoid bad / irrelevant peers
		// influencing the connection pool

		long now=server.getTimestamp();
		// With no verified link, failing to reach anyone is most likely this peer's own fault
		boolean isolated=!hasVerifiedLink();
		Set<AArrayBlob> potentialPeers = s.getPeers().keySet();
		URI target = null;
		AccountKey targetKey = null;
		double accStake = 0.0;
		for (ACell c : potentialPeers) {
			AccountKey peerKey = RT.ensureAccountKey(c);
			if (connections.containsKey(peerKey)) continue;
			if (server.getPeerKey().equals(peerKey)) continue;
			if (!mayDial(peerKey,now,isolated)) continue;

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

		if (target == null) return null;
		URI connectTarget = target;
		AccountKey chosen = targetKey;
		return dial(target,targetKey).whenComplete((linked,e) -> {
			// Close clears the records; a dial ending after it must not refill them
			if (closed) return;
			if (e != null) {
				log.debug("Failed to connect to Peer at {}: {}", connectTarget, e.getMessage());
				recordFailure(chosen,true);
			} else if (connections.get(chosen) != linked) {
				// Identification filed the link under the key that answered, not the one chosen
				log.debug("Peer at {} did not identify as {}", connectTarget, chosen);
				recordFailure(chosen,false);
			}
		});
	}

	/**
	 * Dials a peer at its registered address.
	 *
	 * @param hostAddress Address of the peer
	 * @param peerKey Key of the peer
	 * @return Future completing with the connection the address was reached on
	 */
	CompletableFuture<Convex> dial(URI hostAddress, AccountKey peerKey) {
		return connectToPeer(hostAddress,peerKey);
	}

	private boolean mayDial(AccountKey peerKey, long now, boolean isolated) {
		Backoff b=backoffs.get(peerKey);
		if ((b==null)||(now>=b.due())) return true;
		// A peer that answered wrongly waits even then, or it would be redialled on every pass
		return isolated && b.unreachable();
	}

	private static boolean isStakedPeer(State s, AccountKey peerKey) {
		PeerStatus ps=s.getPeer(peerKey);
		return (ps!=null) && (ps.getPeerStake()>CPoSConstants.MINIMUM_EFFECTIVE_STAKE);
	}

	// ========== Verification ==========

	/**
	 * Challenges each unverified link whose peer is due for the key the link is filed under,
	 * and reads the answers to challenges sent on earlier passes.
	 */
	void verifyDueLinks() {
		long now=server.getTimestamp();
		for (Map.Entry<AccountKey,Challenge> e : challenges.entrySet()) {
			AccountKey peerKey=e.getKey();
			Challenge challenge=e.getValue();
			Convex link=challenge.link();
			if (connections.get(peerKey)!=link) {
				challenges.remove(peerKey,challenge);
				continue;
			}
			if (challenge.answer().isDone()) {
				challenges.remove(peerKey,challenge);
				// Any answer is a reply, so it restarts the minute allowed without one
				unresponsiveSince.remove(peerKey);
				if (peerKey.equals(provedKey(challenge.answer()))) {
					makeDue(peerKey);
				} else {
					failLink(peerKey,link,false);
				}
			} else if (now>=challenge.deadline()) {
				challenges.remove(peerKey,challenge);
				challenge.answer().cancel(false);
				failLink(peerKey,link,true);
				noReply(peerKey,link);
			}
		}

		Hash networkID=server.getPeer().getNetworkID();
		for (Map.Entry<AccountKey,Convex> e : connections.entrySet()) {
			AccountKey peerKey=e.getKey();
			Convex link=e.getValue();
			if (!link.isConnected() || isVerified(peerKey,link)) continue;
			if (challenges.containsKey(peerKey) || !isDue(peerKey,now)) continue;
			challenges.put(peerKey,new Challenge(link,link.verifyPeer(peerKey,networkID),now+POLL_TIMEOUT_MILLIS));
		}
	}

	private static AccountKey provedKey(CompletableFuture<AccountKey> answer) {
		try {
			return answer.getNow(null);
		} catch (CancellationException | CompletionException e) {
			return null;
		}
	}

	/**
	 * Checks whether a link has proved the key it is filed under. A link's verified key only
	 * changes from null to the proved key while it is open, so a stale read costs one pass.
	 */
	private static boolean isVerified(AccountKey peerKey, Convex link) {
		return peerKey.equals(link.getVerifiedPeer());
	}

	private boolean hasVerifiedLink() {
		for (Map.Entry<AccountKey,Convex> e : connections.entrySet()) {
			Convex link=e.getValue();
			if (link.isConnected() && isVerified(e.getKey(),link)) return true;
		}
		return false;
	}

	// ========== Back-off ==========

	/**
	 * Gets the wait before the next attempt on a remote peer after consecutive failures.
	 *
	 * @param pollDelay Poll delay in milliseconds
	 * @param failures Consecutive failures, at least one
	 * @return Wait in milliseconds
	 */
	static long backoff(long pollDelay, int failures) {
		int doublings=Math.min(Math.max(failures,1)-1,MAX_BACKOFF_DOUBLINGS);
		return pollDelay<<doublings;
	}

	/**
	 * Checks whether a remote peer's wait has passed.
	 *
	 * @param peerKey Key of the remote peer
	 * @return true if an attempt on the peer is allowed now
	 */
	boolean isDue(AccountKey peerKey) {
		return isDue(peerKey,server.getTimestamp());
	}

	private boolean isDue(AccountKey peerKey, long now) {
		Backoff b=backoffs.get(peerKey);
		return (b==null) || (now>=b.due());
	}

	/** Counts a failure by a remote peer and starts its wait, returning its consecutive failures. */
	private int recordFailure(AccountKey peerKey, boolean unreachable) {
		long now=server.getTimestamp();
		Backoff b=backoffs.compute(peerKey,(key,old) -> {
			int failures=(old==null) ? 1 : old.failures()+1;
			return new Backoff(failures,now+backoff(pollDelay,failures),unreachable);
		});
		return b.failures();
	}

	private void makeDue(AccountKey peerKey) {
		long now=server.getTimestamp();
		backoffs.computeIfPresent(peerKey,(key,old) -> new Backoff(old.failures(),now,old.unreachable()));
	}

	/** Counts a failure on a link, closing the link once it leaves its peer's wait at the cap. */
	private void failLink(AccountKey peerKey, Convex link, boolean unreachable) {
		int failures=recordFailure(peerKey,unreachable);
		if (failures<=MAX_BACKOFF_DOUBLINGS) return;
		// While no link is verified, an unverified one is all there is to poll
		if (!isVerified(peerKey,link) && !hasVerifiedLink()) return;
		closeConnection(peerKey,link,"Failed "+failures+" times in a row");
	}

	/** Closes a link that has answered no request for the grace period. */
	private void noReply(AccountKey peerKey, Convex connection) {
		if (connections.get(peerKey)!=connection) return;

		long now=server.getTimestamp();
		long since=unresponsiveSince.computeIfAbsent(peerKey,key -> now);
		long unavailableFor=Math.max(0,now-since);
		if (unavailableFor>=UNRESPONSIVE_CONNECTION_TIMEOUT_MILLIS) {
			unresponsiveSince.remove(peerKey);
			closeConnection(peerKey,connection,"No reply for "+unavailableFor+" ms");
		} else {
			log.debug("Peer {} has not replied for {} ms",peerKey,unavailableFor);
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

	/**
	 * Polls one outbound peer once the poll delay has passed since the last poll: a verified
	 * link whose wait has passed, or an unverified link if there is none.
	 */
	void maybePollBelief() throws InterruptedException {
		long now=server.getTimestamp();
		if (now-lastPollTime<pollDelay) return;
		if (!server.getBeliefPropagator().hasBeliefPollCapacity()) return;

		ArrayList<AccountKey> verified = new ArrayList<>();
		ArrayList<AccountKey> unverified = new ArrayList<>();
		for (Map.Entry<AccountKey,Convex> e : connections.entrySet()) {
			AccountKey peerKey=e.getKey();
			Convex link=e.getValue();
			if (!link.isConnected()) continue;
			if (!isVerified(peerKey,link)) {
				unverified.add(peerKey);
			} else if (isDue(peerKey,now)) {
				verified.add(peerKey);
			}
		}
		// Trusted sources first (CAD015); an unverified link only fills a slot they cannot use
		ArrayList<AccountKey> peers = verified.isEmpty() ? unverified : verified;
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

	/**
	 * Polls one outbound peer for anti-entropy and connection health, counting the outcome
	 * towards the peer's back-off if the link is verified.
	 */
	void pollBelief(AccountKey peerKey, Convex connection) throws InterruptedException {
		boolean verified=isVerified(peerKey,connection);
		PollOutcome outcome=poll(peerKey,connection);
		// An unverified link only fills an idle slot: its back-off is for getting it verified
		if (!verified) return;
		switch (outcome) {
			case GOOD -> backoffs.remove(peerKey);
			case UNREACHABLE -> failLink(peerKey,connection,true);
			case ANSWERED_WRONGLY -> failLink(peerKey,connection,false);
			case NO_FAULT -> {}
		}
	}

	private PollOutcome poll(AccountKey peerKey, Convex connection) throws InterruptedException {
		try {
			Result result = connection.requestStatusSync(POLL_TIMEOUT_MILLIS);
			if (result.isError()) {
				return handleStatusFailure(peerKey,connection,result);
			}
			unresponsiveSince.remove(peerKey);

			AMap<Keyword, ACell> status = API.ensureStatusMap(result.getValue());
			if (status == null) {
				log.warn("Dubious status response message: {}", result);
				return PollOutcome.ANSWERED_WRONGLY;
			}
			Hash h = RT.ensureHash(status.get(Keywords.BELIEF));
			if (h==null) {
				log.warn("Status response has no Belief hash: {}",result);
				return PollOutcome.ANSWERED_WRONGLY;
			}
			if (h.equals(server.getBelief().getHash())) return PollOutcome.GOOD;
			if (!server.getBeliefPropagator().hasBeliefPollCapacity()) return PollOutcome.NO_FAULT;

			ACell acquired;
			try {
				acquired = connection.acquire(h).get(POLL_ACQUIRE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			} catch (ExecutionException | TimeoutException e) {
				if (server.isLive()) log.info("Belief poll could not acquire {}: {}",h,e.toString());
				return PollOutcome.ANSWERED_WRONGLY;
			}
			if (!(acquired instanceof Belief belief)) {
				log.warn("Peer returned non-Belief value for advertised Belief hash {}",h);
				return PollOutcome.ANSWERED_WRONGLY;
			}
			if (!server.queueBelief(belief)) {
				log.debug("Discarding acquired Belief because the local queue filled during polling");
			}
			return PollOutcome.GOOD;
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception t) {
			// Nothing here is known to be the peer's doing, so it is not counted against it
			if (server.isLive()) {
				log.info("Belief Polling failed: {}", t.getClass().toString() + " : " + t.getMessage());
			}
			return PollOutcome.NO_FAULT;
		}
	}

	/**
	 * Handles a STATUS error: one with no reply counts towards closing the link, and any
	 * other is an answer, which restarts that count.
	 */
	private PollOutcome handleStatusFailure(AccountKey peerKey, Convex connection, Result result) {
		ACell code=result.getErrorCode();
		if (!ErrorCodes.TIMEOUT.equals(code) && !ErrorCodes.CONNECT.equals(code)) {
			// A LOAD or application error is not evidence that the remote endpoint vanished.
			unresponsiveSince.remove(peerKey);
			log.debug("Peer status probe returned {}",result);
			return PollOutcome.ANSWERED_WRONGLY;
		}
		noReply(peerKey,connection);
		return PollOutcome.UNREACHABLE;
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
		} catch (Exception e) {
			if (opened!=null) pendingConnections.remove(opened);
			closeSilently(opened);
			result.completeExceptionally(e);
			// Throwing an interrupt cleared this thread's status: restore it for the caller
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
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
	}

	// ========== Alerts ==========

	/**
	 * Called to signal a bad / corrupt message from a Peer.
	 * @param m Message of concern
	 * @param reason Reason message considered bad
	 */
	public void alertBadMessage(Message m, String reason) {
		log.warn(reason);
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
