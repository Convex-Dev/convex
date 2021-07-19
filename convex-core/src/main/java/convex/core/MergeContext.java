package convex.core;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;

/**
 * Class representing the context to be used for a Belief merge/update function. This
 * context must be created by a Peer to perform a valid Belief merge. It can be safely
 * discarded after use.
 * 
 * SECURITY: contains a hot key pair! We need this to sign new belief updates
 * including any chains we want to communicate. Don't allow this to leak
 * anywhere!
 *
 */
public class MergeContext {

	private final AccountKey publicKey;
	private final State state;
	private final AKeyPair keyPair;
	private final long timestamp;

	private MergeContext(AKeyPair peerKeyPair, long mergeTimestamp, State consensusState) {
		this.state = consensusState;
		this.publicKey = peerKeyPair.getAccountKey();
		this.keyPair = peerKeyPair;
		this.timestamp = mergeTimestamp;
	}

	public static MergeContext create(AKeyPair kp, long timestamp, State s) {
		return new MergeContext(kp, timestamp, s);
	}

	/**
	 * Get the address of the current Peer (the one performing the merge)
	 * 
	 * @return The Address of the peer.
	 */
	public AccountKey getAccountKey() {
		return publicKey;
	}

	public <T extends ACell> SignedData<T> sign(T value) {
		return SignedData.create(keyPair, value);
	}

	public long getTimeStamp() {
		return timestamp;
	}

	public MergeContext withTimestamp(long newTimestamp) {
		return new MergeContext(keyPair, newTimestamp, state);
	}

	public State getConsensusState() {
		return state;
	}

}
