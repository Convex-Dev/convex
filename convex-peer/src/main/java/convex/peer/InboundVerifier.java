package convex.peer;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.message.AConnection;
import convex.core.message.Message;

/**
 * Handles server-initiated challenge/response verification of untrusted
 * inbound connections. Owned by {@link Server}.
 *
 * <p>When an untrusted connection sends a Belief, the server triggers
 * verification via {@link #maybeStart(AConnection)}. A virtual thread
 * sends a CHALLENGE on the connection, the remote client auto-responds,
 * and the RESULT is routed back via {@link #handleResult(Message)}.
 * On success the connection's trusted key is set.</p>
 *
 * <p>At most one verification runs per connection (CAS guard).
 * All operations on the message dispatch path are non-blocking.</p>
 */
class InboundVerifier {

	private static final Logger log = LoggerFactory.getLogger(InboundVerifier.class.getName());

	private static final long TIMEOUT_MS = 5000;

	private final Server server;
	private final SecureRandom random = new SecureRandom();
	private final AtomicLong idCounter = new AtomicLong();
	private final AtomicLong verifiedCount = new AtomicLong();

	/**
	 * In-progress verifications keyed by connection. The future is completed
	 * by {@link #handleResult(Message)} when the client's RESULT arrives.
	 * Also serves as CAS guard — at most one verification per connection.
	 */
	private final ConcurrentHashMap<AConnection, CompletableFuture<Message>> active = new ConcurrentHashMap<>();

	InboundVerifier(Server server) {
		this.server = server;
	}

	/**
	 * Triggers verification for an untrusted inbound connection.
	 * No-op if the connection is already trusted or verification is in progress.
	 * Non-blocking — spawns a virtual thread for the actual protocol exchange.
	 *
	 * @param conn Inbound connection to verify
	 */
	void maybeStart(AConnection conn) {
		if (conn.isTrusted()) return;
		if (!conn.supportsMessage()) return;
		if (active.containsKey(conn)) return;

		CompletableFuture<Message> resultFuture = new CompletableFuture<>();
		if (active.putIfAbsent(conn, resultFuture) != null) return;

		try { Thread.startVirtualThread(() -> {
			try {
				AKeyPair kp = server.getKeyPair();
				if (kp == null) return;

				Hash token = Blob.createRandom(random, 16).getHash();
				AccountKey ownKey = kp.getAccountKey();
				Peer peer = server.getPeer();
				Hash networkID = (peer != null) ? peer.getNetworkID() : null;

				SignedData<ACell> signed = Message.signChallenge(kp, token, null, networkID);

				long id = idCounter.incrementAndGet();
				Message challengeMsg = Message.createChallenge(id, signed);
				if (!conn.sendMessage(challengeMsg)) return;

				Message resultMsg = resultFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

				try {
					resultMsg.getPayload(server.getStore());
				} catch (BadFormatException e) {
					log.debug("Bad format in verification response: {}", e.getMessage());
					return;
				}
				Result result = resultMsg.toResult();
				AccountKey remoteKey = Message.verifyChallengeResponse(result, token, ownKey, networkID, null);
				if (remoteKey == null) return;

				// Verify the proven key is a registered peer in consensus state
				State state = (peer != null) ? peer.getConsensusState() : null;
				if (state == null || state.getPeer(remoteKey) == null) {
					log.debug("Verified key {} is not a registered peer", remoteKey);
					return;
				}

				conn.setTrustedKey(remoteKey);
				verifiedCount.incrementAndGet();
				log.info("Verified inbound peer: {} from {}", remoteKey, conn.getRemoteAddress());
			} catch (Exception e) {
				log.debug("Inbound verification failed for {}: {}", conn, e.getMessage());
			} finally {
				active.remove(conn);
			}
		}); } catch (Exception e) {
			active.remove(conn);
		}
	}

	/** Number of connections successfully verified since startup. */
	long getVerifiedCount() { return verifiedCount.get(); }

	/** Number of verifications currently in progress. */
	int getPendingCount() { return active.size(); }

	/**
	 * Routes an inbound RESULT to a pending verification by connection.
	 * Called from {@link Server#processMessage(Message)}.
	 * Non-blocking — just a map lookup and future completion.
	 *
	 * @param m Inbound RESULT message
	 * @return true if consumed by a pending verification, false otherwise
	 */
	boolean handleResult(Message m) {
		if (active.isEmpty()) return false;
		AConnection conn = m.getConnection();
		if (conn == null) return false;
		CompletableFuture<Message> cf = active.get(conn);
		if (cf == null) return false;
		cf.complete(m);
		return true;
	}
}
