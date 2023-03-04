package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Abstract base class for binary data stored in Java arrays.
 *
 */
public abstract class AArrayBlob extends ABlob {
	protected final byte[] store;
	protected final int offset;
	protected final int length;

	protected AArrayBlob(byte[] bytes, int offset, int length) {
		this.store = bytes;
		this.length = length;
		this.offset = offset;
	}

	@Override
	public void updateDigest(MessageDigest digest) {
		digest.update(store, offset, length);
	}

	@Override
	public Blob slice(long start, long end) {
		if (start < 0) return null;
		if (end > this.length) return null;
		long length=end-start;
		if (length<0) return null;
		if ((start==0)&&(end==this.count())) return toFlatBlob();
		return Blob.wrap(store, Utils.checkedInt(start + offset), Utils.checkedInt(length));
	}

	@Override
	public ABlob append(ABlob d) {
		long dlength = d.count();
		if (dlength == 0) return (ABlob) this.getCanonical();
		long length = this.length;
		if (length == 0) return (ABlob) d.getCanonical();
		
		if (length>Blob.CHUNK_LENGTH) {
			// Need to normalise to a BlobTree first
			return BlobTree.create(this).append(d);
		}
		
		// We know this Blob is 4096 bytes or less, but other Blob might still be big...
		long newLen=length+dlength;
		
		// If small enough, we have a regular Blob
		if (newLen<=Blob.CHUNK_LENGTH) return appendSmall(d);
		
		// If reasonably small, we have an ArrayBlob with exactly 2 children
		if (newLen<=Blob.CHUNK_LENGTH*2) {
			long split=Blob.CHUNK_LENGTH-length; // number of bytes to complete first chunk
			return BlobTree.create(this.append(d.slice(0,split)).toFlatBlob(),d.slice(split,dlength).toFlatBlob());
		}
		
		// More than 2 chunks, use a BlobBuilder
		BlobBuilder bb=new BlobBuilder(this);
		bb.append(d);
		return bb.toBlob();
	}
		
	protected ABlob appendSmall(ABlob d) {
		int n=Utils.checkedInt(count() + d.count());
		if (n>Blob.CHUNK_LENGTH) throw new Error("Illegal Blob appendSmall size: "+n);
		byte[] newData = new byte[n];
		getBytes(newData, 0);
		d.getBytes(newData, length);
		return Blob.wrap(newData);
	}

	@Override
	public Blob toFlatBlob() {
		return Blob.wrap(store, offset, length);
	}

	@Override
	public final int compareTo(ABlob b) {
		if (b instanceof AArrayBlob) {
			return compareTo((AArrayBlob) b);
		} else {
			return compareTo(b.toFlatBlob());
		}
	}

	public final int compareTo(AArrayBlob b) {
		if (this == b) return 0;
		int alength = this.length;
		int blength = b.length;
		
		// Check common bytes first
		int compareLength = Math.min(alength, blength);
		int c = Utils.compareByteArrays(this.store, this.offset, b.store, b.offset, compareLength);
		if (c != 0) return c;
		
		if (alength > compareLength) return 1; // this is bigger
		if (blength > compareLength) return -1; // b is bigger
		return 0;
	}

	@Override
	public final void getBytes(byte[] dest, int destOffset) {
		System.arraycopy(store, offset, dest, destOffset, length);
	}

	@Override
	public ByteBuffer writeToBuffer(ByteBuffer bb) {
		return bb.put(store, offset, length);
	}

	public int writeToBuffer(byte[] bs, int pos) {
		System.arraycopy(store, offset, bs, pos, length);
		return Utils.checkedInt(pos + length);
	}

	/**
	 * Encodes this Blob, excluding tag byte (will include count)
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.writeVLCLong(bs, pos, length);
		return writeToBuffer(bs,pos);
	}

	@Override
	public final boolean appendHex(BlobBuilder bb, long hexLength) {
		if (hexLength<0) return false;
		long nbytes= Math.min(hexLength/2, this.length); // Bytes to print
		for (long i=0; i<nbytes; i++) {
			byte b=byteAt(i);
			Utils.appendHexByte(bb,b);
		}
		return nbytes==this.length;
	}

	@Override
	public final long count() {
		return length;
	}

	@Override
	public final byte byteAt(long i) {
		int ix = (int) i;
		if ((ix != i) || (ix < 0) || (ix >= length)) {
			throw new IndexOutOfBoundsException("Index: " + i);
		}
		return store[offset + ix];
	}
	
	@Override
	public final byte getUnchecked(long i) {
		int ix = (int) i;
		return store[offset + ix];
	}

	@Override
	public int getHexDigit(long digitPos) {
		byte b = store[offset+ (int)(digitPos >> 1)];
		// if ((digitPos & 1) == 0) {
		// return (b >> 4) & 0x0F; // first hex digit
		// } else {
		// return b & 0x0F; // second hex digit
		// }
		int shift = 4 - (((int) digitPos & 1) << 2);
		return (b >> shift) & 0x0F;
	}

	/**
	 * Gets the internal array backing this Blob. Use with caution!
	 * @return Byte array backing this blob
	 */
	public byte[] getInternalArray() {
		return store;
	}
	
	/**
	 * Gets this offset into the internal array backing this Blob.
	 * @return Offset into backing array
	 */
	public int getInternalOffset() {
		return offset;
	}

	@Override
	public ByteBuffer getByteBuffer() {
		return ByteBuffer.wrap(store, offset, length).asReadOnlyBuffer();
	}

	@Override
	public boolean equals(ABlob o) {
		if (o==null) return false;
		if (o.getType()!=Types.BLOB) return false;
		if (o.count()!=length) return false;
		return o.equalsBytes(this.store, this.offset);
	}
	
	@Override
	public boolean equalsBytes(byte[] bytes, int byteOffset) {
		return Utils.arrayEquals(store, offset, bytes, byteOffset, length);
	}
	
	public boolean equalsBytes(ABlob k) {
		if (k.count()!=this.count()) return false;
		return k.equalsBytes(store,offset);
	}
	
	/**
	 * Tests if a specific range of bytes are exactly equal.
	 * @param b Blob to compare with
	 * @param start Start index of range (inclusive)
	 * @param end End index of range (exclusive)
	 * @return true if digits are equal, false otherwise
	 */
	public boolean rangeMatches(ABlob b, int start, int end) {
		if (b instanceof AArrayBlob) return rangeMatches((AArrayBlob)b,start,end);
		for (int i = start; i < end; i++) {
			// null entry if key does not match prefix
			if (store[offset+i] != b.getUnchecked(i)) return false;
		}
		return true;
	}
	
	/**
	 * Tests if a specific range of bytes are exactly equal from this Blob with another Blob
	 * @param b Blob with which to compare
	 * @param start Start index in both Blobs
	 * @param end End index in both Blobs
	 * @return true if digits are equal, false otherwise
	 */
	public boolean rangeMatches(AArrayBlob b, int start, int end) {
		return Arrays.equals(store, offset+start, offset+end, b.store, b.offset+start,b.offset+end);
	}
	
	@Override
	public long hexMatchLength(ABlob b, long start, long length) {
		if (b == this) return length;
		long end = start + length;
		for (long i = start; i < end; i++) {
			if (!(getHexDigit(i) == b.getHexDigit(i))) return i - start;
		}
		return length;
	}
	
	/**
	 * Tests if a specific range of hex digits are exactly equal.
	 * @param key Blob to compare with
	 * @param start Start hex digit index (inclusive)
	 * @param end End hex digit index (Exclusive)
	 * @return true if digits are equal, false otherwise
	 */
	public boolean hexMatches(ABlob key, int start, int end) {
		if (key==this) return true;
		if (start==end) return true; 
		if ((start&1)!=0) if (key.getHexDigit(start) != getHexDigit(start)) return false;
		if ((end&1)!=0) if (key.getHexDigit(end-1) != getHexDigit(end-1)) return false;
		return rangeMatches(key,(start+1)>>1,end>>1);
	}

	@Override
	public long commonHexPrefixLength(ABlob b) {
		if (b == this) return count() * 2;

		long max = Math.min(count(), b.count());
		for (long i = 0; i < max; i++) {
			byte ai = getUnchecked(i);
			byte bi = b.getUnchecked(i);
			if (ai != bi) return (i * 2) + (Utils.firstDigitMatch(ai, bi) ? 1 : 0);
		}
		return max * 2;
	}



	@Override
	public void validate() throws InvalidDataException {
		super.validate();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (length < 0) throw new InvalidDataException("Negative length: " + length, this);
		if (offset < 0) throw new InvalidDataException("Negative data offset: " + offset, this);
		if ((offset + length) > store.length) {
			throw new InvalidDataException(
					"End out of range: " + (offset + length) + " with array size=" + store.length, this);
		}
	}

	@Override
	public long toExactLong() {
		if (length != 8) throw new IllegalStateException(Errors.wrongLength(8, length));
		return Utils.readLong(store, offset);
	}

	@Override
	public long longValue() {
		if (length >= 8) {
			return Utils.readLong(store, offset + length - 8);
		} else {
			long result = 0l;
			int ix = offset;
			if ((length & 4) != 0) {
				result += 0xffffffffL & Utils.readInt(store, ix);
				ix += 4;
			}
			if ((length & 2) != 0) {
				result = (result << 16) + (0xFFFF & Utils.readShort(store, ix));
				ix += 2;
			}
			if ((length & 1) != 0) {
				result = (result << 8) + (0xFF & store[ix]);
				ix += 1;
			}
		
			return result;
		}
	}
	
	@Override
	public byte getTag() {
		return Tag.BLOB;
	}

	@Override
	public final int getRefCount() {
		// Array-backed blobs have no child Refs
		return 0;
	}
	

}
