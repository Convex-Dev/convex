package convex.lattice;

import java.util.function.BiPredicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.prim.CVMLong;

/**
 * Context for lattice merge operations.
 *
 * Contains contextual information needed during merges such as:
 * - Timestamp for conflict resolution
 * - Signing key for creating signatures on new values
 * - Owner verifier for checking signer authorisation
 */
public class LatticeContext {

	public static final LatticeContext EMPTY = new LatticeContext(null, null, null);

	private final CVMLong timestamp;
	private final AKeyPair signingKey;
	private final BiPredicate<ACell, AccountKey> ownerVerifier;

	private LatticeContext(CVMLong timestamp, AKeyPair signingKey, BiPredicate<ACell, AccountKey> ownerVerifier) {
		this.timestamp = timestamp;
		this.signingKey = signingKey;
		this.ownerVerifier = ownerVerifier;
	}

	/**
	 * Creates a new LatticeContext with the given timestamp and signing key.
	 *
	 * @param timestamp Timestamp for conflict resolution (may be null)
	 * @param signingKey Key pair for signing new values (may be null)
	 * @return New LatticeContext instance
	 */
	public static LatticeContext create(CVMLong timestamp, AKeyPair signingKey) {
		if (timestamp == null && signingKey == null) return EMPTY;
		return new LatticeContext(timestamp, signingKey, null);
	}

	/**
	 * Creates a new LatticeContext with the given timestamp, signing key, and owner verifier.
	 *
	 * @param timestamp Timestamp for conflict resolution (may be null)
	 * @param signingKey Key pair for signing new values (may be null)
	 * @param ownerVerifier Predicate to verify (ownerKey, signerKey) authorisation (may be null for lenient mode)
	 * @return New LatticeContext instance
	 */
	public static LatticeContext create(CVMLong timestamp, AKeyPair signingKey, BiPredicate<ACell, AccountKey> ownerVerifier) {
		if (timestamp == null && signingKey == null && ownerVerifier == null) return EMPTY;
		return new LatticeContext(timestamp, signingKey, ownerVerifier);
	}

	/**
	 * Verifies that the given signer key is valid for the specified owner.
	 *
	 * For blob/AccountKey owners, checks direct equality with the signer key.
	 * For other owner types (Address, DID strings, etc.), delegates to the
	 * owner verifier if one is set.
	 *
	 * Returns true if no verifier is set and the owner type is not a blob
	 * (lenient mode for backward compatibility).
	 *
	 * @param ownerKey The owner identity (AccountKey, Address, AString DID, etc.)
	 * @param signerKey The Ed25519 public key from SignedData
	 * @return true if the signer is authorised for this owner
	 */
	public boolean verifyOwner(ACell ownerKey, AccountKey signerKey) {
		// Fast path: owner IS the signer key
		if (ownerKey instanceof AccountKey ak) return ak.equals(signerKey);
		if (ownerKey instanceof ABlob blob && blob.count() == AccountKey.LENGTH) {
			return AccountKey.create(blob).equals(signerKey);
		}
		// Delegate to verifier for Address, DID, etc.
		if (ownerVerifier != null) return ownerVerifier.test(ownerKey, signerKey);
		return true; // lenient if no verifier
	}

	/**
	 * Gets the timestamp for this context.
	 * @return Timestamp or null if not set
	 */
	public CVMLong getTimestamp() {
		return timestamp;
	}

	/**
	 * Gets the signing key for this context.
	 * @return Signing key or null if not set
	 */
	public AKeyPair getSigningKey() {
		return signingKey;
	}

	/**
	 * Gets the owner verifier for this context.
	 * @return Owner verifier or null if not set
	 */
	public BiPredicate<ACell, AccountKey> getOwnerVerifier() {
		return ownerVerifier;
	}
}
