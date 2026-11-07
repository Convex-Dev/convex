package convex.core.message;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.util.Utils;

/**
 * Proves possession of one key in one challenge context, and signs nothing else.
 *
 * <p>A transport given a challenge signer instead of the key pair can challenge other
 * parties and answer their challenges in its context, but cannot sign data,
 * transactions or challenges in any other context with the key.</p>
 */
public final class ChallengeSigner {

	private final AKeyPair keyPair;
	private final ACell context;

	private ChallengeSigner(AKeyPair keyPair,ACell context) {
		this.keyPair=keyPair;
		this.context=context;
	}

	/**
	 * Creates a challenge signer for a key in one context.
	 *
	 * @param keyPair key whose possession is proved
	 * @param context challenge context, such as {@link Message#LATTICE_PEER_CHALLENGE_CONTEXT},
	 *        or null for challenges that carry none
	 * @return challenge signer
	 */
	public static ChallengeSigner create(AKeyPair keyPair,ACell context) {
		if (keyPair==null) throw new IllegalArgumentException("Key pair must not be null");
		return new ChallengeSigner(keyPair,context);
	}

	/** The key whose possession this signer proves. */
	public AccountKey getAccountKey() {
		return keyPair.getAccountKey();
	}

	/** The challenge context this signer signs in, or null for none. */
	public ACell getContext() {
		return context;
	}

	/**
	 * Signs a challenge for another party, as {@link Message#signChallenge} does, in this
	 * signer's context.
	 *
	 * @param token random nonce
	 * @param target key the challenged party must prove, or null to accept any
	 * @return signed challenge
	 */
	public SignedData<ACell> challenge(Hash token,AccountKey target) {
		return Message.signChallenge(keyPair,token,target,context);
	}

	/**
	 * Answers a challenge, as {@link Message#answerChallenge} does, if it is in this
	 * signer's context and names this signer's key or none.
	 *
	 * @param challenge signed challenge received
	 * @return the signed response, or an error result for a challenge this signer refuses
	 */
	public Result answer(SignedData<ACell> challenge) {
		return Message.answerChallenge(keyPair,challenge,received -> Utils.equals(received,context));
	}
}
