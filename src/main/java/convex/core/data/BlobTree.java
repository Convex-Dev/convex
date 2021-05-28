package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Implementation of a large Blob data structure consisting of 2 or more chunks.
 * 
 * Intention is to enable relatively large binary content to be handled without
 * too many tree levels, and without too many references in a single tree node
 * We choose a branching factor of 16 as a reasonable tradeoff.
 * 
 * Level 1 can hold up to 64k Level 2 can hold up to 1mb Level 3 can hold up to
 * 16mb Level 4 can hold up to 256mb ... Level 15 (max) should be big enough for
 * the moment
 * 
 * One smart reference is maintained for each child node at each level
 * 
 */
public class BlobTree extends ABlob {

	public static final int BIT_SHIFT_PER_LEVEL = 4;
	public static final int FANOUT = 1 << BIT_SHIFT_PER_LEVEL;

	private final Ref<ABlob>[] children;
	private final int shift;
	private final long count;

	private BlobTree(Ref<ABlob>[] children, int shift, long count) {
		this.children = children;
		this.shift = shift;
		this.count = count;
	}

	/**
	 * Create a BlobTree from an arbitrary Blob.
	 * 
	 * Must be of sufficient size to convert to BlobTree
	 */
	public static BlobTree create(ABlob blob) {
		if (blob instanceof BlobTree) return (BlobTree) blob;

		long length = blob.count();
		int chunks = Utils.checkedInt(calcChunks(length));
		Blob[] blobs = new Blob[chunks];
		for (int i = 0; i < chunks; i++) {
			int offset = i * Blob.CHUNK_LENGTH;
			blobs[i] = blob.slice(offset, Math.min(Blob.CHUNK_LENGTH, length - offset)).toBlob();
		}
		return create(blobs);
	}

	/**
	 * Create a BlobTree from an array of children. Each child must be a valid
	 * chunk. All except the last child must be of the correct chunk size.
	 * 
	 * @param blobs
	 * @return New BlobTree
	 */
	public static BlobTree create(Blob... blobs) {
		return create(blobs, 0, blobs.length);
	}

	/**
	 * Computes the shift level for a BlobTree with the specified number of chunks
	 * 
	 * @param chunkCount
	 * @return Shift value for a BlobTree with the specified number of chunks
	 */
	public static int calcShift(long chunkCount) {
		int shift = 0;
		while (chunkCount > FANOUT) {
			shift += BIT_SHIFT_PER_LEVEL;
			chunkCount >>= BIT_SHIFT_PER_LEVEL;
		}
		return shift;
	}

	/**
	 * Computes the number of chunks for a BlobTree of the given length
	 * 
	 * @param length The length of the Blob in bytes
	 * @return Number of chunks needed for a given byte length.
	 */
	public static long calcChunks(long length) {
		return ((length - 1) >> Blobs.CHUNK_SHIFT) + 1;
	}

	private static BlobTree createSmall(Blob[] blobs, int offset, int chunkCount) {
		long length = 0;
		if (chunkCount < 2) throw new IllegalArgumentException("Cannot create BlobTree without at least 2 Blobs");
		@SuppressWarnings("unchecked")
		Ref<ABlob>[] children = new Ref[chunkCount];
		for (int i = 0; i < chunkCount; i++) {
			Blob blob = blobs[offset + i];
			long childLength = blob.count();

			if (childLength > Blob.CHUNK_LENGTH)
				throw new IllegalArgumentException("BlobTree chunk too large: " + childLength);
			if ((i < chunkCount - 1) && (childLength != Blob.CHUNK_LENGTH))
				throw new IllegalArgumentException("Illegal internediate chunk size: " + childLength);

			Ref<ABlob> child = blob.getRef();
			children[i] = child;
			length += childLength;
		}
		return new BlobTree(children, 0, length);
	}

	private static BlobTree create(Blob[] blobs, int offset, int chunkCount) {
		int shift = calcShift(chunkCount);
		if (shift == 0) {
			return createSmall(blobs, offset, chunkCount);
		} else {
			int childSize = 1 << shift; // number of chunks in children
			int numChildren = ((chunkCount - 1) >> shift) + 1;
			@SuppressWarnings("unchecked")
			Ref<ABlob>[] children = new Ref[numChildren];

			long length = 0;
			for (int i = 0; i < numChildren; i++) {
				int childOffset = i * childSize;
				BlobTree bt = create(blobs, offset + childOffset, Math.min(childSize, chunkCount - childOffset));
				children[i] = bt.getRef();
				length += bt.count;
			}
			return new BlobTree(children, shift, length);
		}
	}

// TODO: better implementation of this
//	@Override
//	public int compareTo(ABlob o) {
//		if (this==o) return 0;
//		throw new UnsupportedOperationException();
//	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#blobtree {:length " + count() + " :shift " + shift + "}");
	}

	@Override
	public boolean isCanonical() {
		// BlobTree is always canonical
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@Override
	public void getBytes(byte[] dest, int destOffset) {
		long clen = childLength();
		int n = children.length;
		for (int i = 0; i < n; i++) {
			getChild(i).getBytes(dest, Utils.checkedInt(destOffset + i * clen));
		}
	}

	@Override
	public long count() {
		return count;
	}

	@Override
	public ABlob slice(long start, long length) {
		if ((start == 0L) && (length == this.count)) return this;
		if (start < 0L) throw new IndexOutOfBoundsException(Errors.badIndex(start));

		long csize = childLength();
		int ci = (int) (start / csize);
		if (ci == (start + length - 1) / csize) {
			return getChild(ci).slice(start - ci * csize, length);
		}

		// FIXME: This looks broken
		// TODO: handle big slices more effectively
		int alen = Utils.checkedInt(this.count);
		byte[] bs = new byte[alen];
		return Blob.wrap(bs, Utils.checkedInt(start), Utils.checkedInt(length));
	}

	private ABlob getChild(int childIndex) {
		return children[childIndex].getValue();
	}

	@Override
	public Blob toBlob() {
		int len = Utils.checkedInt(count());
		byte[] data = new byte[len];
		getBytes(data, 0);
		return Blob.wrap(data);
	}

	@Override
	protected void updateDigest(MessageDigest digest) {
		int n = children.length;
		for (int i = 0; i < n; i++) {
			getChild(i).updateDigest(digest);
		}
	}
	
	@Override
	public byte getUnchecked(long i) {
		int childLength = childLength();
		int ci = (int) (i >> (shift + Blobs.CHUNK_SHIFT));
		return getChild(ci).getUnchecked(i - ci * childLength);
	}

	/**
	 * Gets the length in bytes of each full child of this BlobTree
	 * @return
	 */
	private int childLength() {
		return 1 << (shift + Blobs.CHUNK_SHIFT);
	}

	@Override
	public int hashCode() {
		return getHash().hashCode();
	}

	@Override
	public boolean equals(ABlob a) {
		if (!(a instanceof BlobTree)) return false;
		return equals((BlobTree) a);
	}

	public boolean equals(BlobTree b) {
		if (b == this) return true;
		if (b.count != count) return false;
		int n = children.length;
		for (int i = 0; i < n; i++) {
			if (!children[i].equals(b.children[i])) return false;
		}
		return true;
	}
	
	@Override
	public boolean equalsBytes(byte[] bytes, int byteOffset) {
		int clen=childLength();
		for (int i=0; i<children.length; i++) {
			if (!(getChild(i).equalsBytes(bytes, byteOffset+i*clen))) return false;
		}
		return true;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.BLOB;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, count);
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = children[i].encode(bs,pos);
		}
		return pos;
	}

	@Override
	public ByteBuffer writeToBuffer(ByteBuffer bb) {
		int n = children.length;
		for (int i = 0; i < n; i++) {
			bb = children[i].write(bb);
		}
		return bb;
	}
	
	@Override
	public int writeToBuffer(byte[] bs, int pos) {
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = children[i].getValue().writeToBuffer(bs,pos);
		}
		return pos;
	}

	
	/**
	 * Maximum byte length of an encoded BlobTree node
	 */
	public static final int MAX_ENCODING_LENGTH=1+Format.MAX_VLC_LONG_LENGTH+(FANOUT*Format.MAX_REF_LENGTH);

	/**
	 * Reads a BlobTree from a bytebuffer. Assumes that tag byte and count are already read
	 * @param bb ByteBuffer
	 * @param count
	 * @return
	 * @throws BadFormatException
	 */
	public static BlobTree read(ByteBuffer bb, long count) throws BadFormatException {
		if (count < 0) {
			// note that this conveniently also captures count overflows....
			throw new BadFormatException("Negative length: " + count);
		}
		long chunks = calcChunks(count);
		int shift = calcShift(chunks);

		int numChildren = Utils.checkedInt(((chunks - 1) >> shift) + 1);
		if ((numChildren < 2) || (numChildren > FANOUT)) {
			throw new BadFormatException(
					"Invalid number of children [" + numChildren + "] for BlobTree with length: " + count);
		}

		@SuppressWarnings("unchecked")
		Ref<ABlob>[] children = (Ref<ABlob>[]) new Ref[numChildren];
		for (int i = 0; i < numChildren; i++) {
			Ref<ABlob> ref = Format.readRef(bb);
			children[i] = ref;
		}

		return new BlobTree(children, shift, count);
	}

	public static BlobTree read(Blob src, long count) throws BadFormatException {
		int headerLength = (1 + Format.getVLCLength(count));
		long chunks = calcChunks(count);
		int shift = calcShift(chunks);
		int numChildren = Utils.checkedInt(((chunks - 1) >> shift) + 1);

		@SuppressWarnings("unchecked")
		Ref<ABlob>[] children = (Ref<ABlob>[]) new Ref<?>[numChildren];
		
		ByteBuffer bb=src.getByteBuffer();
		bb.position(headerLength);
		for (int i = 0; i < numChildren; i++) {
			Ref<ABlob> ref = Format.readRef(bb);
			children[i] = ref;
		}

		return new BlobTree(children, shift, count);
	}

	@Override
	public int estimatedEncodingSize() {
		return 1 + Format.MAX_VLC_LONG_LENGTH + Ref.INDIRECT_ENCODING_LENGTH * children.length;
	}

	@Override
	public ABlob append(ABlob d) {
		// TODO: optimise
		return toBlob().append(d);
	}

	@Override
	public Blob getChunk(long chunkIndex) {
		long childSize = 1 << shift;
		int child = Utils.checkedInt(chunkIndex >> shift);
		return getChild(child).getChunk(chunkIndex - child * childSize);
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		int n = children.length;
		if ((n < 2) | (n > FANOUT)) throw new InvalidDataException("Illegal number of BlobTree children: " + n, this);
		int clen = childLength();
		long total = 0;
		
		// We need to validate and check the lengths of all child notes. Note that only the last child can
		// be shorted than the defined childLength() for this shift level.
		for (int i = 0; i < n; i++) {
			ABlob child;
			child = getChild(i);
			child.validate();
			
			long cl = child.count();
			total += cl;
			if (i == (n - 1)) {
				if (cl > clen) throw new InvalidDataException(
						"Illegal last child length: " + cl + " expected less than or equal to " + clen, this);
			} else {
				if (cl != clen)
					throw new InvalidDataException("Illegal child length: " + cl + " expected " + clen, this);
			}
		}
		if (total != count) throw new InvalidDataException("Incorrect total child count: " + total, this);
	}

	@Override
	public ByteBuffer getByteBuffer() {
		throw new UnsupportedOperationException("Can't get bytebuffer for " + this.getClass());
	}

	@Override
	public String toHexString() {
		StringBuilder sb = new StringBuilder();
		toHexString(sb);
		return sb.toString();
	}

	@Override
	public void toHexString(StringBuilder sb) {
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().toHexString(sb);
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		int n = children.length;
		if ((n < 2) | (n > FANOUT)) throw new InvalidDataException("Illegal number of BlobTree children: " + n, this);
	}

	@Override
	public long commonHexPrefixLength(ABlob b) {
		long cpl = 0;
		long DIGITS_PER_CHUNK = Blob.CHUNK_LENGTH * 2;
		for (long i = 0;; i++) {
			long cl = getChunk(i).commonHexPrefixLength(b.getChunk(i));
			if (cl < DIGITS_PER_CHUNK) return cpl + cl;
			cpl += DIGITS_PER_CHUNK;
		}
	}

	@Override
	public long hexMatchLength(ABlob b, long start, long length) {
		long HEX_CHUNK_LENGTH = (Blob.CHUNK_LENGTH * 2);
		long end = start + length;
		long endChunk = (end - 1) / HEX_CHUNK_LENGTH;
		for (long ci = start / HEX_CHUNK_LENGTH; ci < endChunk; ci++) {
			long cpos = ci * HEX_CHUNK_LENGTH; // position of chunk
			long cs = Math.max(0, start - cpos); // start position within chunk
			long ce = Math.min(HEX_CHUNK_LENGTH, end - cpos); // end position within chunk
			long clen = ce - cs; // length to check within chunk
			long match = getChunk(ci).hexMatchLength(b.getChunk(ci), cs, clen);
			if (match < clen) return cpos + cs + match;
		}
		return length;
	}

	@Override
	public long longValue() {
		if (count != 8) throw new IllegalStateException(Errors.wrongLength(8, count));
		return getChunk(0).longValue();
	}
	
	@Override
	public long toLong() {
		return slice(count-8,8).toLong();
	}

	@Override
	public int getRefCount() {
		return children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return (Ref<R>) children[i];
	}

	@Override
	public BlobTree updateRefs(IRefFunction func) {
		Ref<ABlob>[] newChildren = Ref.updateRefs(children, func);
		return withChildren(newChildren);
	}

	private BlobTree withChildren(Ref<ABlob>[] newChildren) {
		if (children == newChildren) return this;
		return new BlobTree(newChildren, shift, count);
	}
	
	@Override
	public boolean isRegularBlob() {
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.BLOB;
	}

}
