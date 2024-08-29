package convex.core.data;

import java.nio.ByteBuffer;
import java.security.MessageDigest;

import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.Panic;
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

	private BlobTree(Ref<ABlob>[] children, int shift, long count) {
		super(count);
		this.children = children;
		this.shift = shift;
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
		if (length<=Blob.CHUNK_LENGTH) throw new IllegalArgumentException("Can't make BlobTree for too small length: "+length);
		
		int chunks = Utils.checkedInt(calcChunks(length));
		Blob[] blobs = new Blob[chunks];
		for (int i = 0; i < chunks; i++) {
			int offset = i * Blob.CHUNK_LENGTH;
			long take=Math.min(Blob.CHUNK_LENGTH, length - offset);
			blobs[i] = blob.slice(offset, offset+take).toFlatBlob();
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
	 * Create a BlobTree from an array of Blob chunks. Each child must be a valid
	 * chunk, all except the last child must be of the full chunk size.
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
				int chunks= Math.min(childChunks, chunkCount - childOffset);
				ABlob child;
				if (chunks==1) {
					child=blobs[offset+childOffset];
				} else {
					child=create(blobs, offset + childOffset,chunks);
				}
				children[i] = child.getRef();
				length += child.count();
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
		// Should always be canonical as long as we enforce at least 2 children as an invariant
		return true;
		
		// return count>Blob.CHUNK_LENGTH;
	}

	@Override
	public int getBytes(byte[] dest, int pos) {
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos=getChild(i).getBytes(dest, pos);
		}
		return pos;
	}

	@Override
	public ABlob slice(long start, long end) {
		if (start < 0L) return null;
		if (end >count) return null;
		long length=end-start;;
		if (length<0) return null;
		if (start==end) return Blob.EMPTY;
		
		if ((start == 0L) && (length == this.count)) return this;

		long csize = childLength();
		int ci = (int) (start / csize);
		int cilast=(int)((end - 1) / csize);
		if (ci == cilast) {
			// Slice within a single child
			long coffset=ci*csize;
			return getChild(ci).slice(start - coffset, end - coffset);
		}

		// Construct using BlobBuilder iterating over relevant children
		BlobBuilder bb=new BlobBuilder();
		for (int i=ci; i<=cilast; i++) {
			ABlob child=getChild(i);
			long coff=i*csize;
			long cstart=Math.max(start-coff, 0);
			long cend=Math.min(end-coff, child.count());
			bb.append(child.slice(cstart,cend));
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
	public byte byteAtUnchecked(long i) {
		long childLength = childLength();
		int ci = (int) (i >> (shift + Blobs.CHUNK_SHIFT));
		return getChild(ci).byteAtUnchecked(i - ci * childLength);
	}

	/**
	 * Gets the length in bytes of each full child of this BlobTree
	 * @return
	 */
	private long childLength() {
		return 1L << (shift + Blobs.CHUNK_SHIFT);
	}

	@Override
	public boolean equals(ABlob a) {
		if (a.count()!=count) return false;
		
		// Canonical form of other Blob must be a BlobTree for this count
		BlobTree other=(BlobTree) (a.getCanonical());
		return equals(other); 
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
	public boolean equalsBytes(byte[] bytes, long byteOffset) {
		long clen=childLength();
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
			return equals(bb);
		}
		
		assert (!b.isCanonical()) : "Canonical Blob of this size should be a BlobTree?";
		
		// Might be a non-canonical array blob?
		if (b instanceof AArrayBlob) {
			AArrayBlob ab=(AArrayBlob) b;
			return equalsBytes(ab.getInternalArray(),ab.getInternalOffset());
		}
	
		return equalsBytes(b.getCanonical());
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCCount(bs,pos, count);
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = children[i].encode(bs,pos);
		}
		return pos;
	}

	/**
	 * Maximum byte length of an encoded BlobTree node. 
	 * Note: 
	 * - Last child might be embedded, others cannot
	 */
	public static final int MAX_ENCODING_SIZE=1+Format.MAX_VLC_COUNT_LENGTH+((FANOUT-1)*Ref.INDIRECT_ENCODING_LENGTH)+Format.MAX_EMBEDDED_LENGTH;

	/**
	 * Reads an encoded BlobTree from a Blob. Assumes there will be encoded children.
	 * @param count Length to read
	 * @param src Source data, assumed to include tag and count at start
	 * @param pos Position to read from, assumed to be tag byte
	 * @return BlobTree instance.
	 * @throws BadFormatException If BlobTree encoding is invalid
	 */
	public static BlobTree read(long count, Blob src, int pos) throws BadFormatException {
		int headerLength = (1 + Format.getVLCCountLength(count));
		long chunks = calcChunks(count);
		int shift = calcShift(chunks);
		int numChildren = Utils.checkedInt(((chunks - 1) >> shift) + 1);

		@SuppressWarnings("unchecked")
		Ref<ABlob>[] children = (Ref<ABlob>[]) new Ref<?>[numChildren];
		
		int rpos=pos+headerLength; // ref position
		for (int i = 0; i < numChildren; i++) {
			Ref<ABlob> ref = Format.readRef(src,rpos);
			children[i] = ref;
			rpos+=ref.getEncodingLength();
		}

		BlobTree result= new BlobTree(children, shift, count);
		Blob enc=src.slice(pos, rpos);
		result.attachEncoding(enc);
		return result;
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
		BlobTree acc=this; // accumulator for appended BlobTree
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
					ABlob newChild=d.slice(off,off+take);
					acc=acc.appendChild(newChild);
					off+=take;
					if (off>=dlen) return acc; // Finished!
				}
			}
			
			// Next level takes a following child with up to as many bytes as acc
			long take=Math.min(acc.count(), dlen-off);
			BlobTree nextLevel=BlobTree.createWithChildren(new ABlob[] {acc,d.slice(off,off+take)});
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
	@Override
	public boolean isFullyPacked() {
		return count==childLength()*FANOUT;
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
		long clen = childLength();
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
		return toFlatBlob().getByteBuffer();
	}

	@Override
	public boolean appendHex(BlobBuilder bb, long length) {
		for (int i = 0; i < children.length; i++) {
			ABlob child=children[i].getValue();
			if (!child.appendHex(bb,length)) return false; // bail out if not fully printed
			length-=child.count()*2;
			if (length<0) return false;
		}
		return true;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		int n = children.length;
		if ((n < 2) | (n > FANOUT)) throw new InvalidDataException("Illegal number of BlobTree children: " + n, this);
	}
	
	@Override
	public long hexMatch(ABlobLike<?> b) {
		if (b instanceof ABlob) return commonHexPrefixLength((ABlob)b);
		return b.hexMatch(this);
	}

	public long commonHexPrefixLength(ABlob b) {
		long cpl = 0;
		long DIGITS_PER_CHUNK = Blob.CHUNK_LENGTH * 2;
		long maxChunk=(Math.min(count(),b.count())-1)/Blob.CHUNK_LENGTH;
		for (long i = 0; i<=maxChunk; i++) {
			long cl = getChunk(i).hexMatch(b.getChunk(i));
			if (cl < DIGITS_PER_CHUNK) return cpl + cl;
			cpl += DIGITS_PER_CHUNK;
		}
		return cpl;
	}
	
	@Override
	public long hexMatch(ABlobLike<?> b, long start, long length) {
		long HEX_CHUNK_LENGTH = (Blob.CHUNK_LENGTH * 2);
		long end = start + length;
		long endChunk = (end - 1) / HEX_CHUNK_LENGTH;
		for (long ci = start / HEX_CHUNK_LENGTH; ci < endChunk; ci++) {
			long cpos = ci * HEX_CHUNK_LENGTH; // position of chunk
			long cs = Math.max(0, start - cpos); // start position within chunk
			long ce = Math.min(HEX_CHUNK_LENGTH, end - cpos); // end position within chunk
			long clen = ce - cs; // length to check within chunk
			long match = getChunk(ci).hexMatch(b.toBlob().getChunk(ci), cs, clen);
			if (match < clen) return cpos + cs + match;
		}
		return length;
	}
	
	@Override
	public long longValue() {
		return slice(count-8,count).longValue();
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
		if (ch>=FANOUT) throw new Panic("Trying to add a child beyond allowable fanout!");
		Ref<ABlob>[] newChildren=new Ref[ch+1];
		System.arraycopy(children, 0, newChildren, 0, ch);
		newChildren[ch]=newChild.getRef();
		long newCount=ch*childLength()+newChild.count();
		return new BlobTree(newChildren, shift, newCount);
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
	
	@Override
	public ABlob toCanonical() {
		return this;
		//if (isCanonical()) return this;
		//return Blobs.toCanonical(this);
	}

	@Override
	public int read(long offset, long count, ByteBuffer dest) {
		if (count<0) throw new IllegalArgumentException("Negative count");
		if ((offset<0)||(offset+count>this.count)) throw new IndexOutOfBoundsException();
		int result=0;
		int childcount=childCount();
		long csize=childLength();

		for (int i=0; i<childcount; i++) {
			if (offset<csize) {
				long n=Math.min(count, csize-offset);
				if (n>0) {
					result+=getChild(i).read(offset,n, dest);
				}
				count-=n;
				offset=0; // offset is now the start of the next child 
			} else {
				offset-=csize;
			}
			if (count<=0) break;
 		}
		
		return result;
	}


}
