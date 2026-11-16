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
import convex.core.util.FutureUtils;
import convex.core.util.LoadMonitor;
import convex.core.util.ThreadUtils;
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
	private enum PollOutcome { GOOD, UNREACHABLE, ANSWERED_WRONGLY, FALSE_INPUT, NO_FAULT }

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
			ThreadUtils.joinUninterruptibly(managerThread);
		}

		// Broadcast GOODBYE after maintenance has stopped, then close every route.
		Cleanup cleanup=new Cleanup();
		cleanup.run(() -> broadcast(Message.createGoodBye()));
		for (Convex pending : pendingConnections) cleanup.run(() -> closeSilently(pending));
		pendingConnections.clear();
		cleanup.run(this::closeAllConnections);
		unresponsiveSince.clear();
		backoffs.clear();
		challenges.clear();
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
				connectFailures.log(e,"Failed to connect to Peer at {}",connectTarget);
				Throwable cause=PeerConnectionException.unwrap(e);
				if (!(cause instanceof PeerRefusedException)) {
					// Reached no one
					recordFailure(chosen,true);
				} else if ((cause.getCause() instanceof ValidationException bad) && disconnects(bad)) {
					recordFalseInput(chosen);
				} else {
					// Reached, and answered wrongly
					recordFailure(chosen,false);
				}
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
					ValidationException bad=badAnswer(challenge.answer());
					if (bad!=null) handleBadInput(peerKey,link,bad);
					if ((bad!=null) && disconnects(bad)) {
						recordFalseInput(peerKey);
					} else {
						failLink(peerKey,link,false);
					}
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

	/** Gets the bad input a challenge's answer was rejected for, or null if there was none. */
	private static ValidationException badAnswer(CompletableFuture<AccountKey> answer) {
		try {
			answer.getNow(null);
			return null;
		} catch (CancellationException e) {
			return null;
		} catch (CompletionException e) {
			return (PeerConnectionException.unwrap(e) instanceof ValidationException bad) ? bad : null;
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

	/** Makes a remote peer that sent false input wait the longest, from where its next failure closes its link. */
	private void recordFalseInput(AccountKey peerKey) {
		long now=server.getTimestamp();
		backoffs.compute(peerKey,(key,old) -> {
			int failures=Math.max(MAX_BACKOFF_DOUBLINGS+1,(old==null) ? 1 : old.failures()+1);
			return new Backoff(failures,now+backoff(pollDelay,failures),false);
		});
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
	 * towards the peer's back-off if the link is verified or the reply is false input.
	 */
	void pollBelief(AccountKey peerKey, Convex connection) throws InterruptedException {
		boolean verified=isVerified(peerKey,connection);
		PollOutcome outcome=poll(peerKey,connection);
		// On any link: redialling the same address soon would reach the same answerer
		if (outcome==PollOutcome.FALSE_INPUT) {
			recordFalseInput(peerKey);
			return;
		}
		// An unverified link only fills an idle slot: its back-off is for getting it verified
		if (!verified) return;
		switch (outcome) {
			case GOOD -> backoffs.remove(peerKey);
			case UNREACHABLE -> failLink(peerKey,connection,true);
			case ANSWERED_WRONGLY -> failLink(peerKey,connection,false);
			case FALSE_INPUT, NO_FAULT -> {}
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
			if (status == null) throw new BadFormatException("Status reply is not a status");
			Hash h = RT.ensureHash(status.get(Keywords.BELIEF));
			if (h==null) throw new BadFormatException("Status reply has no Belief hash");
			if (h.equals(server.getBelief().getHash())) return PollOutcome.GOOD;
			if (!server.getBeliefPropagator().hasBeliefPollCapacity()) return PollOutcome.NO_FAULT;

			ACell acquired;
			try {
				// Cancelled if it is not done in time, so no acquisition outlives its poll
				acquired = FutureUtils.getOrCancel(connection.acquire(h), POLL_ACQUIRE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			} catch (ExecutionException | TimeoutException e) {
				// Bad input in the polled peer's reply goes to the handler below
				if (e.getCause() instanceof ValidationException bad) throw bad;
				if (server.isLive()) pollFailures.log(e,"Belief poll of {} could not acquire {}",peerKey,h);
				return PollOutcome.ANSWERED_WRONGLY;
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
			return PollOutcome.GOOD;
		} catch (InterruptedException e) {
			// Before the catch-all: an interrupt stops the manager, it is not a failed poll
			throw e;
		} catch (ValidationException e) {
			// The polled peer's own input, and the poll's other steps throw none
			handleBadInput(peerKey,connection,e);
			return disconnects(e) ? PollOutcome.FALSE_INPUT : PollOutcome.ANSWERED_WRONGLY;
		} catch (Throwable e) {
			Utils.checkRequestFailure(e);
			// Failures while closing are expected
			if (server.isLive()) pollFailures.log(e,"Belief poll of {} failed",peerKey);
			// Nothing here is known to be the peer's doing, so it is not counted against it
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
