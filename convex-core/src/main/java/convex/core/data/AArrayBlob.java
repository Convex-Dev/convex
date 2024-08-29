package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

import convex.core.crypto.Hashing;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * Abstract base class for binary data stored in Java arrays.
 *
 */
public abstract class AArrayBlob extends ABlob {
	protected final byte[] store;
	protected final int offset;

	protected AArrayBlob(byte[] bytes, int offset, int length) {
		super((long)length);
		this.store = bytes;
		this.offset = offset;
	}

	/**
	 * Cached hash of the Blob data. Might be null.
	 */
	protected Hash contentHash = null;

	
	@Override
	public final Hash getContentHash() {
		if (contentHash == null) {
			contentHash = computeHash(Hashing.getDigest());
		}
		return contentHash;
	}
	
	@Override
	public void updateDigest(MessageDigest digest) {
		digest.update(store, offset, (int)count);
	}

	@Override
	public Blob slice(long start, long end) {
		if (start < 0) return null;
		if (end > this.count) return null;
		long length=end-start;
		if (length<0) return null;
		int size=(int)length;
		if (length==count) return toFlatBlob();
		return Blob.wrap(store, Utils.checkedInt(start + offset), size);
	}

	@Override
	public ABlob append(ABlob d) {
		long dlength = d.count();
		if (dlength == 0) return this;
		long length = this.count;
		if (length == 0) return d;
		
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
		if (n>Blob.CHUNK_LENGTH) throw new IllegalStateException("Illegal Blob appendSmall size: "+n);
		byte[] newData = new byte[n];
		getBytes(newData, 0);
		d.getBytes(newData, (int) count);
		return Blob.wrap(newData);
	}

	@Override
	public Blob toFlatBlob() {
		return Blob.wrap(store, offset, (int) count);
	}

	@Override
	public final int compareTo(ABlobLike<?> b) {
		if (b instanceof AArrayBlob) {
			return compareTo((AArrayBlob) b);
		} else {
			return -b.compareTo(this);
		}
	}

	public final int compareTo(AArrayBlob b) {
		if (this == b) return 0;
		int alength = (int) this.count;
		int blength = (int) b.count;
		
		// Check common bytes first
		int compareLength = Math.min(alength, blength);
		int c = Utils.compareByteArrays(this.store, this.offset, b.store, b.offset, compareLength);
		if (c != 0) return c;
		
		if (alength > compareLength) return 1; // this is bigger
		if (blength > compareLength) return -1; // b is bigger
		return 0;
	}

	@Override
	public final int getBytes(byte[] dest, int pos) {
		System.arraycopy(store, offset, dest, pos, (int) count);
		return (int) (pos + count);
	}

	/**
	 * Encodes this Blob, excluding tag byte (will include count)
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.writeVLCCount(bs, pos, count);
		return getBytes(bs,pos);
	}

	@Override
	public final boolean appendHex(BlobBuilder bb, long hexLength) {
		if (hexLength<0) return false;
		long nbytes= Math.min(hexLength/2, this.count); // Bytes to print
		for (long i=0; i<nbytes; i++) {
			byte b=byteAt(i);
			bb.appendHexByte(b);
		}
		return nbytes==this.count;
	}

	@Override
	public boolean isChunkPacked() {
		return (count==0)||(count==Blob.CHUNK_LENGTH);
	}

	@Override
	public boolean isFullyPacked() {
		return (count==Blob.CHUNK_LENGTH);
	}
	
	@Override
	protected long calcMemorySize() {	
		// fast path for small Blobs, never have child cells
		if (isEmbedded()) return 0;
		return super.calcMemorySize();
	}

	@Override
	public final byte byteAt(long i) {
		int ix = (int) i;
		if ((ix != i) || (ix < 0) || (ix >= count)) {
			throw new IndexOutOfBoundsException("Index: " + i);
		}
		return store[offset + ix];
	}
	
	@Override
	public final short shortAt(long i) {
		int ix=(int)i;
		if ((ix != i) || (ix < 0) || (ix+1 >= count)) {
			throw new IndexOutOfBoundsException("Index: " + i);
		}
		ix+=offset;
		return (short)((store[ix]<<8) |(store[ix+1]&0xFF));
	}
	

	public long longAt(long i) {
		int ix=(int)i;
		if ((ix != i) || (ix < 0) || (ix+7 >= count)) {
			throw new IndexOutOfBoundsException("Index: " + i);
		}
		
		long val=Utils.readLong(store, offset+ix,8);
		return val;
	}
	
	@Override
	public final byte byteAtUnchecked(long i) {
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
		// We need to force the ByteBuffer offset in the array to be zero
		// Otherwise position won't be 0 at start....
		if (offset == 0) {
			return ByteBuffer.wrap(store, offset, (int) count);
		} else {
			return ByteBuffer.wrap(this.getBytes());
		}
	}

	@Override
	public boolean equals(ABlob o) {
		if (o==this) return true;
		if (o==null) return false;
		if (o.count()!=count) return false;
		return o.equalsBytes(this.store, this.offset);
	}
	
	@Override
	public boolean equalsBytes(byte[] bytes, long byteOffset) {
		return Utils.arrayEquals(store, offset, bytes, Utils.checkedInt(byteOffset), (int) count);
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
			if (store[offset+i] != b.byteAtUnchecked(i)) return false;
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
	public long hexMatch(ABlobLike<?> b, long start, long length) {
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
	public long hexMatch(ABlobLike<?> b) {
		if (b == this) return count() * 2;

		long max = Math.min(count(), b.count());
		for (long i = 0; i < max; i++) {
			byte ai = byteAtUnchecked(i);
			byte bi = b.byteAtUnchecked(i);
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
		if (count < 0) throw new InvalidDataException("Negative length: " + count, this);
		if (offset < 0) throw new InvalidDataException("Negative data offset: " + offset, this);
		if ((offset + count) > store.length) {
			throw new InvalidDataException(
					"End out of range: " + (offset + count) + " with array size=" + store.length, this);
		}
	}

	@Override
	public long longValue() {
		if (count==0) return 0;
		if (count >= 8) {
			return Utils.readLong(store, (int) (offset + count - 8),8);
		} else {
			return Utils.readLong(store,offset,(int) count);
		}
	}
	
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (count>Blob.CHUNK_LENGTH) return super.getRef(i);
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		if (count>Blob.CHUNK_LENGTH) return super.updateRefs(func);
		return this;
	}
	
	@Override
	public int getRefCount() {
		if (count>Blob.CHUNK_LENGTH) return super.getRefCount();
		return 0;
	}
	
	@Override
	public int read(long offset, long count, ByteBuffer dest) {
		if (count<0) throw new IllegalArgumentException("Negative count");
		if ((offset<0)||(offset+count>this.count)) throw new IllegalArgumentException();
		int n=(int)count;
		dest.put(store, (int) (this.offset+offset), n);
		return n;
	}

}
