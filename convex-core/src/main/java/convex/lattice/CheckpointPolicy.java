package convex.lattice;

/**
 * Policy for optional persistence of working values before publication.
 * Explicit persistence, signing, publication and physical durability are separate.
 */
public enum CheckpointPolicy {
	/** Keep working values in memory until explicitly persisted or published. */
	DEFERRED,

	/** Allow components to checkpoint working values at domain-specific intervals. */
	INCREMENTAL
}
