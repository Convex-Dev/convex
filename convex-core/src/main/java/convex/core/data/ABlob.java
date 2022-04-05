package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.crypto.Hashing;
import convex.core.data.prim.CVMByte;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Abstract base class for data objects containing immutable chunks of binary
 * data. Representation is equivalent to a fixed size immutable byte sequence.
 * 
 * Rationale: - Allow data to be encapsulated as an immutable object - Provide
 * specialised methods for processing byte data - Provide a cached Hash value,
 * lazily computed on demand
 * 
 */
public abstract class ABlob extends ACountable<CVMByte> implements Comparable<ABlob> {
	/**
	 * Cached hash of the Blob data. Might be null.
	 */
	protected Hash contentHash = null;

	@Override
	public AType getType() {
		return Types.BLOB;
	}
	
	/**
	 * Copies the bytes from this blob to a given destination
	 * 
	 * @param dest Destination array
	 * @param destOffset Offset into destination array
	 */
	public abstract void getBytes(byte[] dest, int destOffset);

	/**
	 * Gets the length of this Blob
	 * 
	 * @return The length in bytes of this data object
	 */
	@Override
	public abstract long count();
	
	@Override
	public CVMByte get(long ix) {
		return CVMByte.create(byteAt(ix));
	}
	
	@Override
	public Ref<CVMByte> getElementRef(long index) {
		return get(index).getRef();
	}
	
	@Override
	public Blob empty() {
		return Blob.EMPTY;
	}

	/**
	 * Converts this data object to a lowercase hex string representation
	 * @return Hex String representation
	 */
	public final String toHexString() {
		return toHexString(Utils.checkedInt(count())*2);
	}
	
	/**
	 * Converts this data object to a hex string representation of the given length.
	 * Equivalent to truncating the full String representation.
	 * @param hexLength Length to truncate String to (in hex characters)
	 * @return String representation of hex values in Blob
	 */
	public final String toHexString(int hexLength) {
		BlobBuilder bb=new BlobBuilder();
		appendHex(bb,hexLength);
		return bb.getCVMString().toString();
	}

	/**
	 * Append hex string up to the given length in hex digits (a multiple of two)
	 * @param bb BlobBuilder instance to append to
	 * @param length Length in Hex digits to append
	 * @return true if Blob fully appended, false if more more hex digits remain
	 */
	protected abstract boolean appendHex(BlobBuilder bb, long length);

	/**
	 * Converts this blob to a readable byte buffer. 
	 * 
	 * WARNING: may be large. May refer to underlying byte array so should not be mutated
	 * 
	 * @return ByteBuffer with position zero (ready to read)
	 */
	public ByteBuffer toByteBuffer() {
		return ByteBuffer.wrap(getBytes());
	}

	/**
	 * Gets a contiguous slice of this blob, as a new Blob.
	 * 
	 * Shares underlying backing data where possible
	 * 
	 * @param start  Start position for the created slice
	 * @param length Length of the slice
	 * @return A blob of the specified length, representing a slice of this blob.
	 */
	public abstract ABlob slice(long start, long length);

	/**
	 * Gets a slice of this blob, as a new blob, starting from the given offset and
	 * extending to the end of the blob.
	 * 
	 * Shares underlying backing data where possible. Returned Blob may not be the
	 * same type as the original Blob
	 * @param start Start position to slice from
	 * @return Slice of Blob
	 */
	public ABlob slice(long start) {
		return slice(start, count() - start);
	}

	/**
	 * Converts this object to a flat array-backed Blob instance.
	 * Warning: might be O(n) in size of Blob, may not be canonical etc.
	 * 
	 * @return A Blob instance containing the same data as this Blob.
	 */
	public abstract Blob toFlatBlob();

	/**
	 * Computes the length of the longest common hex prefix between two blobs
	 * 
	 * @param b Blob to compare with 
	 * @return The length of the longest common prefix in hex digits
	 */
	public abstract long commonHexPrefixLength(ABlob b);

	/**
	 * Computes the hash of the byte data stored in this Blob, using the default MessageDigest.
	 * 
	 * This is the correct hash ID for a data value if this blob contains the data value's encoding
	 * 
	 * @return The Hash
	 */
	public final Hash getContentHash() {
		if (contentHash == null) {
			contentHash = computeHash(Hashing.getDigest());
		}
		return contentHash;
	}

	/**
	 * Computes the hash of the byte data stored in this Blob, using the given MessageDigest.
	 * 
	 * @param digest MessageDigest instance
	 * @return The hash
	 */
	public final Hash computeHash(MessageDigest digest) {
		updateDigest(digest);
		return Hash.wrap(digest.digest());
	}

	protected abstract void updateDigest(MessageDigest digest);

	/**
	 * Gets the byte at the specified position in this blob
	 * 
	 * @param i Index of the byte to get
	 * @return The byte at the specified position
	 */
	public byte byteAt(long i) {
		if ((i < 0) || (i >= count())) {
			throw new IndexOutOfBoundsException("Index: " + i);
		}
		return getUnchecked(i);
	}
	
	/**
	 * Gets the byte at the specified position in this data object, without bounds checking.
	 * Only safe if index is known to be in bounds, otherwise result is undefined.
	 * 
	 * @param i Index of the byte to get
	 * @return The byte at the specified position
	 */
	public abstract byte getUnchecked(long i);

	/**
	 * Gets the specified hex digit from this data object.
	 * 
	 * WARNING: Result is undefined if index is out of bounds, but probably an IndexOutOfBoundsException.
	 * 
	 * @param digitPos The position of the hex digit
	 * @return The value of the hex digit, in the range 0-15 inclusive
	 */
	public int getHexDigit(long digitPos) {
		byte b = getUnchecked(digitPos >> 1);
		//if ((digitPos & 1) == 0) {
		//	return (b >> 4) & 0x0F; // first hex digit
		//} else {
		//	return b & 0x0F; // second hex digit
		//}
		// This hack avoids a conditional, not sure if worth it....
		int shift = 4*(1-((int)digitPos&1));
		return (b>>shift)&0x0F;
	}

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
	 * Append an additional Blob to this, creating a new Blob as needed. New Blob will be canonical.
	 * 
	 * @param d Blob to append
	 * @return A new Blob, containing the additional data appended to this blob.
	 */
	public abstract ABlob append(ABlob d);

	/**
	 * Determines if this Blob is equal to another Object.
	 * 
	 * Blobs are defined to be equal if they have the same on-chain representation,
	 * i.e. if and only if all of the following are true:
	 * 
	 * - Blob is of the same general type - Blobs are of the same length - All byte
	 * values are equal
	 */
	@Override
	public final boolean equals(ACell o) {
		if (!(o instanceof ABlob)) return false;
		return equals((ABlob)o);
	}
	
	/**
	 * Determines if this Blob is equal to another Blob.
	 * 
	 * Blobs are defined to be equal if they have the same on-chain representation,
	 * i.e. if and only if all of the following are true:
	 * 
	 * - Blob is of the same general type - Blobs are of the same length - All byte
	 * values are equal
	 * 
	 * @param o Blob to compare with
	 * @return true if Blobs are equal, false otherwise
	 */
	public abstract boolean equals(ABlob o);
	
	@Override
	public abstract ABlob toCanonical();

	/**
	 * Tests if this Blob is equal to a subset of a byte array
	 * @param bytes Byte array to compare with
	 * @param byteOffset Offset into byte array
	 * @return true if exactly equal, false otherwise
	 */
	public abstract boolean equalsBytes(byte[] bytes, int byteOffset);
	
	/**
	 * Compares this Blob to another Blob, in lexicographic order sorting by first
	 * bytes (unsigned).
	 * 
	 * Note: This means that compareTo does not precisely match equality, because
	 * specialised Blob types may be lexicographically equal but represent different values.
	 */
	@Override
	public int compareTo(ABlob b) {
		if (this == b) return 0;
		long alength = this.count();
		long blength = b.count();
		long compareLength = Math.min(alength, blength);
		for (long i = 0; i < compareLength; i++) {
			int c = (0xFF & getUnchecked(i)) - (0xFF & b.getUnchecked(i));
			if (c > 0) return 1;
			if (c < 0) return -1;
		}
		if (alength > compareLength) return 1; // this is bigger
		if (blength > compareLength) return -1; // b is bigger
		return 0;
	}

	/**
	 * Writes the raw byte contents of this Blob to a ByteBuffer. May be big!
	 * 
	 * @param bb ByteBuffer to write to
	 * @return The passed ByteBuffer, after writing byte content
	 */
	public abstract ByteBuffer writeToBuffer(ByteBuffer bb);

	/**
	 * Writes the raw byte contents of this blob to a byte array
	 * 
	 * @param bs Byte array to write to
	 * @param pos Starting position in byte array to write to
	 * @return The position in the array after writing
	 */
	public abstract int writeToBuffer(byte[] bs, int pos);
	
	/**
	 * Gets a chunk of this Blob, as a canonical Blob up to the maximum chunk size.
	 * Returns empty Blob if and only if referencing the end of a Blob with fully packed chunks
	 * 
	 * @param i Index of chunk
	 * @return A Blob containing the specified chunk data.
	 */
	public abstract Blob getChunk(long i);
	
	/**
	 * Prints this Blob in a readable Hex representation, typically in the format "0x01abcd...."
	 * 
	 * Subclasses may override this if they require a different representation.
	 */
	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(Strings.HEX_PREFIX);
		return appendHex(bb,limit-bb.count());
	}

	/**
	 * Gets a byte buffer containing this Blob's raw data. Will have remaining bytes
	 * equal to this Blob's size.
	 * 
	 * @return A ByteBuffer containing the Blob's data.
	 */
	public abstract ByteBuffer getByteBuffer();

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (count() < 0) throw new InvalidDataException("Negative blob length", this);
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
	public abstract long hexMatchLength(ABlob b, long start, long length);

	/**
	 * Checks for Hex equality of two ABlobs. *ignores* type, i.e. only considers hex contents.
	 * @param b ABlob to compare with
	 * @return True if all hex digits are equal, false otherwise
	 */
	public boolean hexEquals(ABlob b) {
		long c = count();
		if (b.count() != c) return false;
		return hexMatchLength(b, 0L, c) == c;
	}

	public boolean hexEquals(ABlob b, long start, long length) {
		return hexMatchLength(b, start, length) == length;
	}

	public long hexLength() {
		return count() << 1;
	}
	
	/**
	 * Writes this Blob's encoding to a byte array, excluding the tag byte
	 *
	 * @param bs A byte array to which to write the encoding
	 * @param pos The offset into the byte array
	 * @return New position after writing
	 */
	public abstract int encodeRaw(byte[] bs, int pos);
	
	/**
	 * Converts this Blob to the corresponding long value.
	 * 
	 * Assumes big-endian format, as if the entire blob is interpreted as a big integer. Higher bytes 
	 * outside the Long range will be ignored.
	 * 
	 * @return long value of this blob
	 */
	public abstract long toLong();
	
	@Override
	public int hashCode() {
		// note: We use the Java hashcode of the last bytes for blobs
		return Long.hashCode(toLong());
	}

	/**
	 * Gets the long value of this Blob if the length is exactly 8 bytes, otherwise
	 * throws an Exception
	 * 
	 * @return The long value represented by the Blob
	 */
	public abstract long longValue();

	/**
	 * Returns true if this object is a regular blob (i.e. not a special blob type like Address)
	 * @return True if a regular blob
	 */
	public boolean isRegularBlob() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	/**
	 * Tests if this Blob has exactly the same bytes as another Blob
	 * @param b Blob to compare with
	 * @return True if byte content is exactly equal, false otherwise
	 */
	public abstract boolean equalsBytes(ABlob b);

	/**
	 * Encodes the raw data of this Blob. Assumes buffer has enough space for (length) bytes.
	 * @param bs Byte array to write to
	 * @param pos Position to write at
	 * @return Updates position
	 */
	public int encodeRawData(byte[] bs, int pos) {
		return toFlatBlob().encodeRawData(bs, pos);
	}

}
