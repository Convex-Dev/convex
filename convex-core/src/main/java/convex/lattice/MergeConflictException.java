package convex.lattice;

/**
 * Two sides changed the same location from a common base, to different values, and
 * nothing defines how to reconcile them. See {@link LatticeOps#mergeChanges}.
 */
public class MergeConflictException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public MergeConflictException() {
		super("Both sides changed the same location");
	}

	/**
	 * @param message What changed on both sides, and what could not reconcile it
	 */
	public MergeConflictException(String message) {
		super(message);
	}
}
