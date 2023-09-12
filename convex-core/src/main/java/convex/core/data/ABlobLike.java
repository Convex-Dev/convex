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
	
	/**
	 * Gets the byte at the specified position in this data object, possibly skipping bounds checking.
	 * Only safe if index is known to be in bounds, otherwise result is undefined.
	 * 
	 * @param i Index of the byte to get
	 * @return The byte at the specified position
	 */
	public byte byteAtUnchecked(long i) {
		return byteAt(i);
	}

	/**
	 * Gets the specified hex digit from this data object.
	 * 
	 * WARNING: Result is undefined if index is out of bounds, but probably an IndexOutOfBoundsException.
	 * 
	 * @param digitPos The position of the hex digit
	 * @return The value of the hex digit, in the range 0-15 inclusive
	 */
	public int getHexDigit(long digitPos) {
		byte b = byteAtUnchecked(digitPos >> 1);

		// This hack avoids a conditional
		int shift = 4*(1-((int)digitPos&1));
		return (b>>shift)&0x0F;
	}
	
	@Override
	public abstract ABlobLike<T> empty();
	
	/**
	 * Gets a new byte array containing a copy of this Blob.
	 * 
	 * @return A new byte array containing the contents of this blob.
	 */
	public byte[] getBytes() {
		byte[] result = new byte[Utils.checkedInt(count())];
		getBytes(result, 0);
		return result;
	}
	
	/**
	 * Copies the bytes from this instance to a given destination array
	 * 
	 * @param dest Destination array
	 * @param destOffset Offset into destination array
	 * @return End position in destination array after writing
	 */
	public abstract int getBytes(byte[] dest, int destOffset);

}
