package convex.core.data;

/**
 * Abstract base class for Blob-like objects, which behave as a sequence of bytes.
 * 
 * @param <T> type of conceptual elements
 */
public abstract class ABlobLike<T extends ACell> extends ACountable<T> {
	/**
	 * Gets the byte at the specified position.
	 * Result is undefined if out of range.
	 * 
	 * @param i Index of the byte to get
	 * @return The byte at the specified position
	 */
	public abstract byte byteAt(long i);
}
