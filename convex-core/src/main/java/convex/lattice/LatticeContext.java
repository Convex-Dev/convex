package convex.lattice;

import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMLong;

/**
 * Context for lattice merge operations.
 *
 * Contains contextual information needed during merges such as:
 * - Timestamp for conflict resolution
 * - Signing key for creating signatures on new values
 */
public class LatticeContext {

	public static final LatticeContext EMPTY = new LatticeContext(null, null);

	private final CVMLong timestamp;
	private final AKeyPair signingKey;

	private LatticeContext(CVMLong timestamp, AKeyPair signingKey) {
		this.timestamp = timestamp;
		this.signingKey = signingKey;
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
		return new LatticeContext(timestamp, signingKey);
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
}
