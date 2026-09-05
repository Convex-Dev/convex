package convex.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

import convex.api.Convex;
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
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;
import convex.net.IPUtils;

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
	private long pollDelay = SERVER_POLL_DELAY;
	private long lastPollTime;
	private long lastConnectionUpdate = Utils.getCurrentTimestamp();

	/** First STATUS timeout for each connection which has not responded since. */
	private final ConcurrentHashMap<AccountKey, Long> unresponsiveSince = new ConcurrentHashMap<>();

	/** Background thread for connection maintenance and belief polling. */
	private Thread thread;

	public ConnectionManager(Server server) {
		this.server = server;
	}

	// ========== Lifecycle ==========

	/**
	 * Starts the connection manager's background maintenance thread.
	 */
	public void start() {
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
		// Broadcast GOODBYE to all outgoing remote peers
		try {
			Message msg = Message.createGoodBye();
			broadcast(msg);
		} finally {
			closeAllConnections();
			unresponsiveSince.clear();
			if (thread != null) {
				thread.interrupt();
			}
		}
	}

	public double getLoad() {
		return LoadMonitor.getLoad(thread);
	}

	protected AStore getStore() {
		return server.getStore();
	}

	// ========== Connection Management ==========

	public void addConnection(AccountKey peerKey, Convex convex) {
		if (peerKey == null) throw new IllegalArgumentException("Peer key must not be null");
		if (convex == null) throw new IllegalArgumentException("Connection must not be null");
		log.debug("Connected to Peer: {} at {}", peerKey, convex.getHostAddress());
		// A peer we chose to connect to is buffered for, not dropped, until it is far behind
		convex.setOutboundLimits(Config.PEER_OUTBOUND_QUEUE_SIZE, Config.PEER_OUTBOUND_QUEUE_BYTE_LIMIT);
		unresponsiveSince.remove(peerKey);
		connections.put(peerKey, convex);
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
		InetSocketAddress target = null;
		double accStake = 0.0;
		for (ACell c : potentialPeers) {
			AccountKey peerKey = RT.ensureAccountKey(c);
			if (connections.containsKey(peerKey)) continue;
			if (server.getPeerKey().equals(peerKey)) continue;

			PeerStatus ps = s.getPeers().get(peerKey);
			if (ps == null) continue;
			AString hostName = ps.getHostname();
			if (hostName == null) continue;
			InetSocketAddress maybeAddress = IPUtils.toInetSocketAddress(hostName.toString());
			if (maybeAddress == null) continue;

			long peerStake = ps.getPeerStake();
			if (peerStake > CPoSConstants.MINIMUM_EFFECTIVE_STAKE) {
				double t = random.nextDouble() * (accStake + peerStake);
				if (t >= accStake) {
					target = maybeAddress;
				}
				accStake += peerStake;
			}
		}

		if (target != null) {
			InetSocketAddress connectTarget = target;
			connectToPeer(target).exceptionally(e -> {
				log.debug("Failed to connect to Peer at {}: {}", connectTarget, e.getMessage());
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
			if (status == null) {
				log.warn("Dubious status response message: {}", result);
				return;
			}
			Hash h = RT.ensureHash(status.get(Keywords.BELIEF));
			if (h==null) {
				log.warn("Status response has no Belief hash: {}",result);
				return;
			}
			if (h.equals(server.getBelief().getHash())) return;
			if (!server.getBeliefPropagator().hasBeliefPollCapacity()) return;

			ACell acquired = connection.acquire(h).get(POLL_ACQUIRE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
			if (!(acquired instanceof Belief belief)) {
				log.warn("Peer returned non-Belief value for advertised Belief hash {}",h);
				return;
			}
			if (!server.queueBelief(belief)) {
				log.debug("Discarding acquired Belief because the local queue filled during polling");
			}
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception t) {
			if (server.isLive()) {
				log.info("Belief Polling failed: {}", t.getClass().toString() + " : " + t.getMessage());
			}
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
		CompletableFuture<Convex> result = new CompletableFuture<>();

		try {
			Convex convex = Convex.connect(hostAddress);
			convex.setStore(server.getStore());
			convex.setKeyPair(server.getKeyPair());

			identifyPeer(convex).whenComplete((peerKey, ex) -> {
				if (peerKey == null || ex != null) {
					convex.close();
					result.completeExceptionally(ex != null ? ex
						: new IOException("Unable to identify peer at " + hostAddress));
					return;
				}

				Convex existing = getConnection(peerKey);
				if ((existing != null) && existing.isConnected()) {
					convex.close();
					result.complete(existing);
				} else {
					addConnection(peerKey, convex);
					result.complete(convex);
				}
			});
		} catch (Exception e) {
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

	/** Minimum interval between requests for the same missing hash. */
	static final long MISSING_REQUEST_INTERVAL = 1000;

	/** Recently requested missing hashes with request time, bounding repeat requests. */
	private final ConcurrentHashMap<Hash, Long> missingRequests = new ConcurrentHashMap<>();

	/**
	 * Called to signal missing data in a received Belief or Order. Requests the
	 * missing value, and transitively whatever it needs, from the peer that sent the
	 * message, since it must hold the data, or failing that from the Order's author.
	 * The message itself is dropped; the next update from that peer merges once the
	 * data has arrived. Requests for one hash are rate limited so a merge that keeps
	 * failing while the acquisition is in flight does not repeat the request.
	 *
	 * @param m Message which caused alert
	 * @param e Missing data exception encountered
	 * @param peerKey Peer key which triggered missing data, or null if unknown
	 */
	public void alertMissing(Message m, MissingDataException e, AccountKey peerKey) {
		try {
			Hash h = e.getMissingHash();
			if (h == null) return;
			AccountKey sender = (m.getConnection() == null) ? null : m.getConnection().getTrustedKey();
			Convex conn = (sender == null) ? null : getConnection(sender);
			if ((conn == null) && (peerKey != null)) conn = getConnection(peerKey);
			if ((conn == null) || !conn.isConnected()) return;

			long now = Utils.getCurrentTimestamp();
			Long last = missingRequests.get(h);
			if ((last != null) && (now - last < MISSING_REQUEST_INTERVAL)) return;
			if (missingRequests.size() > 4096) missingRequests.clear();
			missingRequests.put(h, now);

			log.debug("Requesting missing data {}", h);
			conn.acquire(h).whenComplete((value, ex) -> {
				missingRequests.remove(h);
				if (ex != null) log.debug("Missing data request for {} failed: {}", h, ex.getMessage());
			});
		} catch (Exception ex) {
			log.warn("Unexpected error responding to missing data", ex);
		}
	}
}
