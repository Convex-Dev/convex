package convex.lattice;

/**
 * A path that is well formed but does not fit what it addresses. Resolution
 * reports a segment that names no place in a component's namespace with
 * {@link NoSuchPathException}, carrying the caller's segments. A cursor reports a
 * key it cannot write with {@link PathWriteException}, carrying its keys. It is an
 * {@link IllegalStateException}: the path passes as an argument, but does not fit
 * the namespace or data it addresses.
 */
public abstract class LatticePathException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	private final int index;

	protected LatticePathException(String message,int index) {
		super(message);
		this.index=index;
	}

	/** Position of the first step that does not fit, among the steps the exception carries. */
	public int getIndex() {
		return index;
	}
}
