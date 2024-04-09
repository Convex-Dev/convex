package convex.core.data;

import convex.core.util.Utils;

/**
 * Abstract base class for Blob-like objects, which conceptually behave as a sequence of bytes.
 * 
 * Includes hex-related functionality for printing and usage in radix trees etc.
 * 
 * @param <T> type of conceptual elements
 */
public abstract class ABlobLike<T extends ACell> extends ACountable<T> implements Comparable<ABlobLike<?>> {
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
	
	/**
	 * Returns the number of matching hex digits in the given hex range of another Blob. Assumes
	 * range is valid for both blobs.
	 * 
	 * Returns length if this Blob is exactly equal to the specified hex range.
	 * 
	 * @param start Start position (in hex digits)
	 * @param length Length to compare (in hex digits)
	 * @param b Blob to compare with
	 * @return The number of matching hex characters
	 */
	public abstract long hexMatch(ABlobLike<?> b, long start, long length);
	
	/**
	 * Computes the length of the longest common hex prefix between two blobs
	 * 
	 * @param b Blob to compare with 
	 * @return The length of the longest common prefix in hex digits
	 */
	public long hexMatch(ABlobLike<?> b) {
		long limit=Math.min(count(),b.count());
		return hexMatch(b,0,limit);
	}
	
	/**
	 * Checks for Hex equality of two BlobLikes. *ignores* type, i.e. only considers hex contents.
	 * @param b Value to compare with
	 * @return True if all hex digits are equal, false otherwise
	 */
	public boolean hexEquals(ABlobLike<?> b) {
		long c = count();
		if (b.count() != c) return false;
		return hexMatch(b, 0L, c) == c;
	}

	public boolean hexEquals(ABlobLike<?> b, long start, long length) {
		return hexMatch(b, start, length) == length;
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

	/**
	 * Gets the length of this value in hex digits
	 * @return Number of hex digits
	 */
	public long hexLength() {
		return count()*2;
	}
	
	/**
	 * Converts this data object to a hex string representation of the given length.
	 * Equivalent to truncating the full String representation.
	 * @param hexLength Length to truncate String to (in hex characters)
	 * @return String representation of hex values in Blob
	 */
	public final String toHexString(int hexLength) {
		BlobBuilder bb=new BlobBuilder();
		long hl=((hexLength&1)==0)?hexLength:hexLength+1;
		appendHex(bb,hl);
		String s= bb.getCVMString().toString();
		if (s.length()>hexLength) {
			s=s.substring(0,hexLength);
		}
		return s;
	}
	
	/**
	 * Converts this data object to a lowercase hex string representation
	 * @return Hex String representation
	 */
	public String toHexString() {
		return toHexString(Utils.checkedInt(hexLength()));
	}

	/**
	 * Append hex string up to the given length in hex digits (a multiple of two)
	 * @param bb BlobBuilder instance to append to
	 * @param length Length in Hex digits to append
	 * @return true if Blob fully appended, false if more more hex digits remain
	 */
	protected boolean appendHex(BlobBuilder bb, long length) {
		long len=hexLength();
		length=Math.min(len,length);
		for (int i=0; i<length; i++) {
			int digit=getHexDigit(i);
			char c=Utils.toHexChar(digit);
			bb.append(c);
		}
		return length==len;
	}
	
	/**
	 * Converts this BlobLike to the corresponding long value.
	 * 
	 * Assumes big-endian format, as if the entire blob is interpreted as an unsigned big integer. Higher bytes 
	 * outside the Long range will be ignored, i.e. the lowest 64 bits are taken
	 * 
	 * @return long value of this blob
	 */
	public abstract long longValue();

	/**
	 * Convert this BlobLike object to a blob, in the most efficient way. May return `this`
	 * @return
	 */
	public abstract ABlob toBlob();

	/**
	 * Compare the byte content of this BlobLike to a Blob value
	 * @param b Blob value to compare with
	 * @return `true` if byte contents are exactly equal, `false` otherwise
	 */
	public abstract boolean equalsBytes(ABlob b);

	@Override
	public abstract int compareTo(ABlobLike<?> b);

	/**
	 * Returns true if this object is a regular blob (i.e. not a special blob type like Address)
	 * @return True if a regular blob
	 */
	public boolean isRegularBlob() {
		return false;
	}

}
