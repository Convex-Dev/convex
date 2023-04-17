package convex.core;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;

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

	private final Belief initialBelief;
	private final AccountKey publicKey;
	private final State state;
	private final AKeyPair keyPair;
	private final long timestamp;

	private MergeContext(Belief belief, AKeyPair peerKeyPair, long mergeTimestamp, State consensusState) {
		this.initialBelief=belief;
		this.state = consensusState;
		this.publicKey = peerKeyPair.getAccountKey();
		this.keyPair = peerKeyPair;
		this.timestamp = mergeTimestamp;
	}

	/**
	 * Create a MergeContext
	 * @param belief 
	 * @param kp Keypair
	 * @param timestamp Timestamp
	 * @param s Consensus State
	 * @return New MergeContext instance
	 */
	public static MergeContext create(Belief belief, AKeyPair kp, long timestamp, State s) {
		return new MergeContext(belief, kp, timestamp, s);
	}

	/**
	 * Get the address of the current Peer (the one performing the merge)
	 * 
	 * @return The Address of the peer.
	 */
	public AccountKey getAccountKey() {
		return publicKey;
	}

	/**
	 * Sign a value using the keypair for this MergeContext
	 * @param <T> Type of value
	 * @param value Value to sign
	 * @return Signed value
	 */
	public <T extends ACell> SignedData<T> sign(T value) {
		return SignedData.create(keyPair, value);
	}

	/**
	 * Gets the timestamp of this merge
	 * @return Timestamp
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Updates the timestamp of this MergeContext
	 * @param newTimestamp New timestamp
	 * @return Updated MergeContext
	 */
	public MergeContext withTimestamp(long newTimestamp) {
		if (timestamp==newTimestamp) return this;
		return new MergeContext(initialBelief,keyPair, newTimestamp, state);
	}

	/**
	 * Gets the Consensus State for this merge
	 * @return Consensus State
	 */
	public State getConsensusState() {
		return state;
	}

	public Belief merge(Belief[] beliefs) throws BadSignatureException, InvalidDataException {
		return initialBelief.merge(this,beliefs);
	}

}
