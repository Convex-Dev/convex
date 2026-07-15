package convex.lattice;

import java.util.function.BiPredicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

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
	 * Creates a context snapshot with a different timestamp, preserving this
	 * context's signing key and owner verifier.
	 *
	 * <p>This is an immutable value-copy operation. It does not establish
	 * field-level inheritance from this context; subsequent changes to the source
	 * from which this context was obtained are not reflected in the result.</p>
	 *
	 * @param timestamp New timestamp, or null to clear it
	 * @return Context snapshot with the supplied timestamp
	 */
	public LatticeContext withTimestamp(CVMLong timestamp) {
		return create(timestamp, signingKey, ownerVerifier);
	}

	/**
	 * Creates a context snapshot with a different signing key, preserving this
	 * context's timestamp and owner verifier.
	 *
	 * <p>This is an immutable value-copy operation. It does not establish
	 * field-level inheritance from this context; subsequent changes to the source
	 * from which this context was obtained are not reflected in the result.</p>
	 *
	 * @param signingKey New signing key, or null to clear it
	 * @return Context snapshot with the supplied signing key
	 */
	public LatticeContext withSigningKey(AKeyPair signingKey) {
		return create(timestamp, signingKey, ownerVerifier);
	}

	/**
	 * Creates a context snapshot with a different owner verifier, preserving this
	 * context's timestamp and signing key.
	 *
	 * <p>This is an immutable value-copy operation. It does not establish
	 * field-level inheritance from this context; subsequent changes to the source
	 * from which this context was obtained are not reflected in the result.</p>
	 *
	 * @param ownerVerifier New owner verifier, or null to clear it
	 * @return Context snapshot with the supplied owner verifier
	 */
	public LatticeContext withOwnerVerifier(BiPredicate<ACell, AccountKey> ownerVerifier) {
		return create(timestamp, signingKey, ownerVerifier);
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
	 * Gets the explicit timestamp set on this context, or null if none was supplied.
	 * @return Timestamp or null if not set
	 */
	public CVMLong getTimestamp() {
		return timestamp;
	}

	/**
	 * Resolves the current write/merge timestamp (#561). Lattice value and merge code must
	 * obtain "now" from here rather than reading the system clock directly — time is the
	 * responsibility of the driving (merging or test) process, injected via this context.
	 *
	 * <p>Returns the explicit timestamp when the driver supplied one (giving full determinism
	 * — tests inject a fixed value); this boundary is the single place a wall-clock is read,
	 * and only as the fallback when no timestamp was supplied (standalone use).</p>
	 *
	 * @return the write/merge timestamp to stamp new values with
	 */
	public CVMLong currentTimestamp() {
		return (timestamp != null) ? timestamp : CVMLong.create(Utils.getCurrentTimestamp());
	}

	/**
	 * The {@code long} form of {@link #currentTimestamp()} — the resolved write/merge time in
	 * epoch millis. Used for expiry checks and arithmetic without boxing.
	 *
	 * @return resolved current timestamp in epoch milliseconds
	 */
	public long currentTimestampValue() {
		return (timestamp != null) ? timestamp.longValue() : Utils.getCurrentTimestamp();
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
