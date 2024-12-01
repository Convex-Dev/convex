package convex.net;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cvm.Peer;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Vectors;
import convex.core.data.Hash;
import convex.core.data.SignedData;

public class ChallengeRequest {

	private static final Logger log = LoggerFactory.getLogger(ChallengeRequest.class.getName());

	private static final int TIMEOUT_SECONDS = 10;


	protected AccountKey peerKey;
	protected long timeout;
	protected Hash token;
	protected Hash sendHash;

	private ChallengeRequest(AccountKey peerKey, long timeout) {
		this.peerKey = peerKey;
		this.timeout = timeout;
	}

	public static ChallengeRequest create(AccountKey peerKey) {
		return ChallengeRequest.create(peerKey, TIMEOUT_SECONDS);
	}

	public static ChallengeRequest create(AccountKey peerKey, int timeoutSeconds) {
		long timeout = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
		return new ChallengeRequest(peerKey, timeout);
	}


	/**
	 * Sends out a single challenge to the remote peer.
	 * @param connection Connection
	 * @param peer This Peer
	 * @return ID of message sent, or negative value if sending fails
	 */
	public long send(Connection connection, Peer peer) {
		AVector<ACell> values = null;
		try {
			SecureRandom random = new SecureRandom();
			
			// Get 120 random bytes
			byte bytes[] = new byte[120];
			random.nextBytes(bytes);
			token = Blob.create(bytes).getHash();
			
			values = Vectors.of(token, peer.getNetworkID(), peerKey);
			SignedData<ACell> challenge = peer.sign(values);
			sendHash = challenge.getHash();
			return connection.sendChallenge(challenge);
		} catch (IOException e) {
			log.warn("Cannot send challenge to remote peer at {}", connection.getRemoteAddress());
			values = null;
		}
		return -1;
	}

	public AccountKey getPeerKey() {
		return peerKey;
	}

	public Hash getToken() {
		return token;
	}

	public Hash getSendHash() {
		return sendHash;
	}

	public boolean isTimedout() {
		return timeout < System.currentTimeMillis();
	}
}
