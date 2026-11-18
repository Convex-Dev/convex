package convex.lattice;

/**
 * A merge cannot reconcile changes that both sides made from a common base. See
 * {@link ALatticeComponent#reconcile(convex.core.data.ACell,convex.core.data.ACell,convex.core.data.ACell)}.
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
