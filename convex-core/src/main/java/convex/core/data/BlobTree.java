package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.BlobBuilder;
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
	
	/**
	 * Shift level for the BlobTree. 0 for minimum size BlobTree, increase by 4 for each level
	 */
	private final int shift;
	
	/**
	 * Total number of bytes in this BlobTree
	 */
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
	 * @param blob Source of BlobTree data
	 * @return New BlobTree instance
	 */
	public static BlobTree create(ABlob blob) {
		if (blob instanceof BlobTree) return (BlobTree) blob; // already a BlobTree

		long length = blob.count();
		if (length<=Blob.CHUNK_LENGTH) throw new Error("Can't make BlobTree for too small length: "+length);
		
		int chunks = Utils.checkedInt(calcChunks(length));
		Blob[] blobs = new Blob[chunks];
		for (int i = 0; i < chunks; i++) {
			int offset = i * Blob.CHUNK_LENGTH;
			blobs[i] = blob.slice(offset, Math.min(Blob.CHUNK_LENGTH, length - offset)).toFlatBlob();
		}
		return create(blobs);
	}
	
	/**
	 * Create a BlobTree with the given children.
	 * 
	 * SECURITY: Does not validate children in any way
	 * @param children Child blobs for this BlobTree node
	 * @return New BlobTree instance
	 */
	@SuppressWarnings("unchecked")
	public static BlobTree createWithChildren(ABlob[] children) {
		int n=children.length;
		long count=0;
		Ref<ABlob>[] cs = new Ref[n];
		for (int i=0; i<n; i++) {
			ABlob child=children[i];
			cs[i]=child.getRef();
			count+=child.count();
		}
		int shift=calcShift(calcChunks(count));
		return new BlobTree(cs,shift, count);
	}

	/**
	 * Create a BlobTree from an array of Blob children. Each child must be a valid
	 * chunk. All except the last child must be of the correct chunk size.
	 * 
	 * @param blobs Blobs to include
	 * @return New BlobTree
	 */
	static BlobTree create(Blob... blobs) {
		return create(blobs, 0, blobs.length);
	}

	/**
	 * Computes the shift level for a BlobTree with the specified number of chunks. Will be zero
	 * for a minimum size BlobTree (4097-65536 bytes)
	 * 
	 * @param chunkCount
	 * @return Shift value for a BlobTree with the specified number of chunks
	 */
	static int calcShift(long chunkCount) {
		int shift = 0;
		while ((((long)FANOUT)<<shift) < chunkCount) {
			shift += BIT_SHIFT_PER_LEVEL;
		}
		return shift;
	}

	/**
	 * Computes the number of chunks (4096 bytes or less) for the canonical BlobTree of the given length
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
		if (chunkCount<=FANOUT) {
			return createSmall(blobs, offset, chunkCount);
		} else {
			int shift = calcShift(chunkCount);
			int childChunks = 1 << shift; // number of chunks in children
			int numChildren = ((chunkCount - 1) >> shift) + 1;
			@SuppressWarnings("unchecked")
			Ref<ABlob>[] children = new Ref[numChildren];

			long length = 0;
			for (int i = 0; i < numChildren; i++) {
				int childOffset = i * childChunks;
				BlobTree bt = create(blobs, offset + childOffset, Math.min(childChunks, chunkCount - childOffset));
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
	public boolean isCanonical() {
		return count>Blob.CHUNK_LENGTH;
	}
	
	@Override 
	public final boolean isCVMValue() {
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
		if (start < 0L) throw new IndexOutOfBoundsException(Errors.badIndex(start));
		if (length < 0L) throw new IllegalArgumentException("Negative length: "+length);
		long end=start+length;
		if (end>count()) throw new IndexOutOfBoundsException(Errors.badIndex(end));
		
		if ((start == 0L) && (length == this.count)) return this;

		long csize = childLength();
		int ci = (int) (start / csize);
		int cilast=(int)((end - 1) / csize);
		if (ci == cilast) {
			// Slice within a single child
			return getChild(ci).slice(start - ci * csize, length);
		}

		// Construct using BlobBuilder iterating over relevant children
		BlobBuilder bb=new BlobBuilder();
		for (int i=ci; i<=cilast; i++) {
			ABlob child=getChild(ci);
			long coff=i*csize;
			long cstart=Math.max(start-coff, 0);
			long cend=Math.min(end-coff, child.count());
			bb.append(child.slice(cstart,cend-cstart));
		}
		return bb.toBlob();
	}

	private ABlob getChild(int childIndex) {
		return children[childIndex].getValue();
	}
	
	private ABlob lastChild() {
		return children[children.length-1].getValue();
	}

	@Override
	public Blob toFlatBlob() {
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
	public boolean equalsBytes(ABlob b) {
		if (b.count()!=count) return false;
		if (b instanceof BlobTree) {
			BlobTree bb=(BlobTree) b;
			for (int i=0; i<children.length; i++) {
				if (!(getChild(i).equalsBytes(bb.getChild(i)))) return false;
			}		
			return true;
		}
		if (b instanceof AArrayBlob) {
			AArrayBlob ab=(AArrayBlob) b;
			return equalsBytes(ab.getInternalArray(),ab.getInternalOffset());
		}
		throw new UnsupportedOperationException("Shouldn't be possible?");
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
			bb = children[i].getValue().writeToBuffer(bb);
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
	 * Maximum byte length of an encoded BlobTree node. 
	 * Note: 
	 * - Last child might be embedded, others cannot
	 * - With max 16 children , not possible to have biggest VLC length
	 */
	public static final int MAX_ENCODING_SIZE=1+(Format.MAX_VLC_LONG_LENGTH-1)+((FANOUT-1)*Ref.INDIRECT_ENCODING_LENGTH)+Format.MAX_EMBEDDED_LENGTH;

	/**
	 * Reads a BlobTree from a bytebuffer. Assumes that tag byte and count are already read
	 * @param bb ByteBuffer
	 * @param count Count of bytes in BlobTree being read
	 * @return Decoded BlobTree
	 * @throws BadFormatException if the encoding was invalid
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

	/**
	 * Appends another blob to this BlobTree. Potentially O(n) but can be faster.
	 * 
	 * We are careful to slice from (0...n) on the appended array, to minimise reconstruction of BlobTrees
	 */
	@Override
	public ABlob append(ABlob d) {
		BlobTree acc=this;
		long off=0; // offset into d
		long dlen=d.count();
		
		// loop until d is fully consumed
		while (off<dlen) {
		
			long csize=acc.childLength();
			ABlob lastChild=acc.lastChild();
			long clen=lastChild.count();
			if (clen!=csize) {
				// Need to fill last child
				long spare=csize-clen;
				long take=Math.min(spare, dlen);
				ABlob newChild=lastChild.append(d.slice(0,take));
				acc=acc.withChild(acc.children.length-1,newChild);
				off+=take;
			}
			
			if (off>=dlen) return acc; // Finished!
			
			if (!acc.isFullyPacked()) {
				// Need to add more children
				for (int i=acc.childCount(); i<FANOUT; i++) {
					long take=Math.min(csize, dlen-off);
					ABlob newChild=d.slice(off,take);
					acc=acc.appendChild(newChild);
					off+=take;
					if (off>=dlen) return acc; // Finished!
				}
			}
			
			// Next level takes a following child with up to as memy bytes as acc
			long take=Math.min(acc.count(), dlen-off);
			BlobTree nextLevel=BlobTree.createWithChildren(new ABlob[] {acc,d.slice(off,take)});
			acc=nextLevel;
			off+=take;
		} 
		return acc;
	}

	private int childCount() {
		return children.length;
	}

	/**
	 * Returns true if this is a fully packed set of chunks
	 * @return True if fully packed, false otherwise
	 */
	public boolean isFullyPacked() {
		return count==childLength()*FANOUT;
	}
	
	/**
	 * Returns true if this is a fully packed set of chunks
	 * @return True if fully packed, false otherwise
	 */
	public boolean isChunkPacked() {
		return (count&(Blob.CHUNK_LENGTH-1))==0;
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
	public void appendHexString(BlobBuilder sb, int length) {
		for (int i = 0; i < children.length; i++) {
			ABlob child=children[i].getValue();
			child.appendHexString(sb,length);
			length-=Utils.checkedInt(child.count()*2);
			if (length<=0) break;
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
	
	private BlobTree withChild(int i,ABlob newChild) {
		ABlob oldChild=children[i].getValue();
		if (oldChild == newChild) return this;
		Ref<ABlob>[] newChildren=children.clone();
		newChildren[i]=newChild.getRef();
		long newCount=count;
		if (i==(children.length-1)) {
			newCount+=newChild.count()-oldChild.count(); // update count if last child changed
		}
		return new BlobTree(newChildren, shift, newCount);
	}
	
	/**
	 * Appends a child. Assumes all previous children are fully packed
	 * @param newChild New child to append
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private BlobTree appendChild(ABlob newChild) {
		int ch=children.length;
		if (ch>=FANOUT) throw new Error("Trying to add a child beyond allowable fanout!");
		Ref<ABlob>[] newChildren=new Ref[ch+1];
		System.arraycopy(children, 0, newChildren, 0, ch);
		newChildren[ch]=newChild.getRef();
		long newCount=ch*childLength()+newChild.count();
		return new BlobTree(newChildren, shift, newCount);
	}
	
	@Override
	public boolean isRegularBlob() {
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.BLOB;
	}

	@Override
	public ABlob toCanonical() {
		if (isCanonical()) return this;
		return Blobs.toCanonical(this);
	}

	/**
	 * Gets the size of a BlobTree child for a blob of given total length.
	 * @param length Length of Blob
	 * @return Size of child, or 1 if not a BlobTree
	 */
	public static long childSize(long length) {
		long chunks=calcChunks(length);
		int shift=calcShift(chunks);
		return ((long)Blob.CHUNK_LENGTH)<<shift;
	}

	/**
	 * Gets the number of children for a BlobTree of given total length.
	 * @param length Length of Blob
	 * @return Number of Child blobs
	 */
	public static int childCount(long length) {
		return Utils.checkedInt(1+(length-1)/childSize(length));
	}





}
