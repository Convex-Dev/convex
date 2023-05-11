package convex.core.data;

import convex.core.util.Utils;

/**
 * Abstract base class for Blob-like objects, which conceptually behave as a sequence of bytes.
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
	
	@Override
	public abstract ABlobLike<T> empty();
	
	/**
	 * Gets a byte array containing a copy of this Blob.
	 * 
	 * @return A new byte array containing the contents of this blob.
	 */
	public byte[] getBytes() {
		byte[] result = new byte[Utils.checkedInt(count())];
		getBytes(result, 0);
		return result;
	}
	
	/**
	 * Copies the bytes from this instance to a given destination
	 * 
	 * @param dest Destination array
	 * @param destOffset Offset into destination array
	 * @return End position in destination array after writing
	 */
	public abstract int getBytes(byte[] dest, int destOffset);

}
