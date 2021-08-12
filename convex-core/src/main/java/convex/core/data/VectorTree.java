package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Persistent Vector implemented as a merkle tree of chunks
 * 
 * shift indicates the level of the tree: 4 = 1st level, 8 = second etc.
 * 
 * Invariants: 
 * <ul>
 * <li>All children except the last must be fully packed</li>
 * <li>Each non-terminal leaf chunk must be a tailless VectorLeaf of size 16</li>
 * </ul>
 * 
 * This implies that the entire tree must be a multiple of 16 in size. This is a
 * desirable property as we want dense trees in our canonical representation.
 * Any extra elements must be stored in a ListVector.
 * 
 * This structure facilitates fast ~O(log(n)) operations for lookup and vector
 * element update, and usually O(1) element additions/lookup at end.
 * 
 * "Software gets slower faster than hardware gets faster"
 * 
 * - Niklaus Wirth
 * 
 * @param <T> Type of Vector elements
 */
public class VectorTree<T extends ACell> extends AVector<T> {

	public static final int MINIMUM_SIZE = 2 * Vectors.CHUNK_SIZE;
	private final int shift; // bits in each child block

	private final Ref<AVector<T>>[] children;

	private VectorTree(Ref<AVector<T>>[] children, long count) {
		super(count);
		this.shift = computeShift(count);
		this.children = children;
	}

	/**
	 * Computes the shift value for a BlockVector of the given count Note: if
	 * returns zero, count cannot be supported by a valid BlockVector
	 * 
	 * @param count Number of elements
	 * @return Shift value
	 */
	public static int computeShift(long count) {
		int shift = 0;
		if (count >= (1L << 60)) return 60;
		while ((1L << (shift + Vectors.BITS_PER_LEVEL)) < count) {
			shift += Vectors.BITS_PER_LEVEL;
		}
		return shift;
	}

	/**
	 * Gets the index of the start of a given child
	 * 
	 * @param bpos
	 * @return
	 */
	private long childIndex(int childNumber) {
		return childNumber * childSize();
	}

	/**
	 * Compute the size of the child array for a given count
	 * 
	 * @param count
	 * @param shift
	 * @return
	 */
	static int computeArraySize(long count) {
		int shift = computeShift(count);
		long bsize = 1L << shift;
		return (int) ((count + (bsize - 1)) / bsize);
	}

	/**
	 * Create a TreeVector with the specified elements - things must have at least
	 * 32 elements (the minimum TreeVector size) - must be a whole multiple of 16
	 * elements (complete chunks only)
	 * 
	 * @param things Elements to include
	 * @param offset Offset into element array
	 * @param length Number of elements to include
	 * @return New TreeVector instance
	 */
	public static <T extends ACell> VectorTree<T> create(ACell[] things, int offset, int length) {
		if (length < MINIMUM_SIZE)
			throw new IllegalArgumentException("Can't create BlockVector with insufficient size: " + length);
		if ((length & Vectors.BITMASK) != 0)
			throw new IllegalArgumentException("Can't create BlockVector with odd elements: " + length);
		int shift = computeShift(length);

		int bSize = 1 << shift;
		int bNum = (length + (bSize - 1)) / bSize; // enough blocks
		@SuppressWarnings("unchecked")
		Ref<AVector<T>>[] bs = (Ref<AVector<T>>[]) new Ref<?>[bNum];
		for (int i = 0; i < bNum; i++) {
			int bLen = Math.min(bSize, length - bSize * i);
			bs[i] = Vectors.createChunked(things, offset + i * bSize, bLen).getRef();
		}
		VectorTree<T> tv = new VectorTree<T>(bs, length);
		return tv;
	}

	@Override
	public T get(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long bSize = 1L << shift; // size of a fully packed block
		int b = (int) (i >> shift);
		return children[b].getValue().get(i - b * bSize);
	}

	@Override
	public Ref<T> getElementRef(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long bSize = 1L << shift; // size of a fully packed block
		int b = (int) (i >> shift);
		return children[b].getValue().getElementRef(i - b * bSize);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> AVector<R> assoc(long i, R value) {
		if ((i < 0) || (i >= count)) return null;
		
		Ref<AVector<R>>[] rchildren=(Ref[])children;

		long bSize = 1L << shift; // size of a fully packed block
		int b = (int) (i >> shift);
		AVector<R> oc = rchildren[b].getValue();
		AVector<R> nc = oc.assoc(i - (b * bSize), value);
		if (oc == nc) return (AVector<R>) this;

		Ref<AVector<R>>[] newChildren = rchildren.clone();
		newChildren[b] = nc.getRef();
		return new VectorTree<R>(newChildren, count);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.VECTOR;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos= Format.writeVLCLong(bs,pos, count);

		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = children[i].encode(bs,pos);
		}
		return pos;
	}
	
	@Override
	public long getEncodingLength() {
		if (encoding!=null) return encoding.count();
		
		// tag and count
		long length=1+Format.getVLCLength(count);
		int n = children.length;
		for (int i = 0; i < n; i++) {
			length+=children[i].getEncodingLength();
		}
		return length;
	}

	@Override
	public int estimatedEncodingSize() {
		// Allow tag, long count, 80 bytes per child average plus some headroom
		return 12 + (64 * (children.length+3));
	}
	
	public static int MAX_ENCODING_SIZE= 1 + Format.MAX_VLC_LONG_LENGTH + (Format.MAX_EMBEDDED_LENGTH * Vectors.CHUNK_SIZE);

	/**
	 * Reads a VectorTree from the provided ByteBuffer 
	 * 
	 * Assumes the header byte and count is already read.
	 * 
	 * @param bb ByteBuffer to read from
	 * @param count Number of elements
	 * @return TreeVector instance as read from ByteBuffer
	 * @throws BadFormatException If encoding is invalid
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> VectorTree<T> read(ByteBuffer bb, long count)
			throws BadFormatException {
		if (count < 0) throw new BadFormatException("Negative count?");
		int n = computeArraySize(count);
		Ref<AVector<T>>[] items = (Ref<AVector<T>>[]) new Ref<?>[n];
		for (int i = 0; i < n; i++) {
			Ref<AVector<T>> ref = Format.readRef(bb);
			items[i] = ref;
		}

		return new VectorTree<T>(items, count);
	}

	@SuppressWarnings("unchecked")
	@Override
	public VectorTree<T> appendChunk(VectorLeaf<T> b) {
		if (b.hasPrefix()) throw new IllegalArgumentException("Can't append a block with a tail");
		if (b.count() != VectorLeaf.MAX_SIZE)
			throw new IllegalArgumentException("Invalid block size for append: " + b.count());
		if (isPacked()) {
			// full blockvector, so need to elevate to the next level
			Ref<AVector<T>>[] newBlocks = new Ref[2];
			newBlocks[0] = this.getRef();
			newBlocks[1] = b.getRef();
			return new VectorTree<T>(newBlocks, this.count() + b.count());
		}

		int blength = children.length;
		AVector<T> lastBlock = children[blength - 1].getValue();
		if (lastBlock.count() == childSize()) {
			// need to extend block array
			Ref<AVector<T>>[] newBlocks = new Ref[blength + 1];
			System.arraycopy(children, 0, newBlocks, 0, blength);
			newBlocks[blength] = b.getRef();
			return new VectorTree<T>(newBlocks, count + Vectors.CHUNK_SIZE);
		} else {
			// add b into current last block
			AVector<T> newLast = lastBlock.appendChunk(b);
			Ref<AVector<T>>[] newBlocks = new Ref[blength];
			System.arraycopy(children, 0, newBlocks, 0, blength - 1);
			newBlocks[blength - 1] = newLast.getRef();
			return new VectorTree<T>(newBlocks, count + Vectors.CHUNK_SIZE);
		}
	}

	/**
	 * Get the child size in number of chunks (for all except the last child)
	 */
	private final long childSize() {
		return 1L << (shift);
	}
	
	/**
	 * Get the child size in number of chunks for a specific child
	 */
	private static long childSize(long count, int i) {
		long n=computeArraySize(count);
		if ((i<0)||(i>=n)) throw new IndexOutOfBoundsException("Bad child: "+i);
		int shift=computeShift(count);
		long cs= 1L<<shift;
		if (i<(n-1)) return cs;
		return count-(n-1)*cs;
	}

	@Override
	public boolean isPacked() {
		return count == 1 << (shift + 4);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public AVector<T> append(T value) {
		return new VectorLeaf(new Ref[] { Ref.get(value) }, this.getRef(), count + 1);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> AVector<R> concat(ASequence<R> b) {
		long bLen = b.count();
		VectorTree<R> result = (VectorTree<R>) this;
		long bi = 0;
		while (bi < bLen) {
			if ((bi + Vectors.CHUNK_SIZE) <= bLen) {
				// can append a whole chunk
				VectorLeaf<R> chunk = (VectorLeaf<R>) b.subVector(bi, Vectors.CHUNK_SIZE);
				result = result.appendChunk(chunk);
				bi += Vectors.CHUNK_SIZE;
			} else {
				// we have less than a chunk left, so final result must be a ListVector with the
				// current result as tail
				VectorLeaf<T> head = (VectorLeaf<T>) b.subVector(bi, bLen - bi);
				return ((VectorLeaf<R>)head).withPrefix(result);
			}
		}
		return result;
	}

	/**
	 * Creates a TreeVector with exactly two chunks
	 * 
	 * @param <T>
	 * @param head
	 * @param tail
	 * @return
	 */
	@SuppressWarnings("unchecked")
	static <T extends ACell> VectorTree<T> wrap2(VectorLeaf<T> head, VectorLeaf<T> tail) {
		Ref<AVector<T>>[] newBlocks = new Ref[2];
		newBlocks[0] = tail.getRef();
		newBlocks[1] = head.getRef();
		return new VectorTree<T>(newBlocks, 2 * Vectors.CHUNK_SIZE);
	}

	@Override
	protected <K> void copyToArray(K[] arr, int offset) {
		for (int i = 0; i < children.length; i++) {
			AVector<T> b = children[i].getValue();
			b.copyToArray(arr, offset);
			offset += b.count();
		}
	}

	@Override
	public long longIndexOf(Object o) {
		long offset = 0;
		for (int i = 0; i < children.length; i++) {
			AVector<T> b = children[i].getValue();
			long bpos = b.longIndexOf(o);
			if (bpos >= 0) return bpos + offset;
			offset += b.count();
		}
		return -1;
	}

	@Override
	public long longLastIndexOf(Object o) {
		long offset = count;
		for (int i = children.length - 1; i >= 0; i--) {
			AVector<T> b = children[i].getValue();
			offset -= b.count();
			long bpos = b.longLastIndexOf(o);
			if (bpos >= 0) return bpos + offset;
		}
		return -1;
	}

	@Override
	public ListIterator<T> listIterator() {
		return new VectorTreeIterator();
	}

	@Override
	public ListIterator<T> listIterator(long index) {
		return new VectorTreeIterator(index);
	}

	private class VectorTreeIterator implements ListIterator<T> {
		int bpos;
		ListIterator<T> sub;

		public VectorTreeIterator() {
			this(0);
		}

		public VectorTreeIterator(final long index) {
			long ix=index;
			if (index < 0L) throw new IndexOutOfBoundsException((int)index);

			bpos = 0;
			for (int i = 0; i < children.length; i++) {
				AVector<T> b = children[bpos].getValue();
				long bc = b.count();
				if (ix <= bc) {
					sub = b.listIterator(ix);
					return;
				}
				ix -= bc;
				bpos++;
			}
			// appears to be beyond the end?
			throw new IndexOutOfBoundsException((int)index);
		}

		@Override
		public boolean hasNext() {
			if (sub.hasNext()) return true;
			return bpos < children.length - 1;
		}

		@Override
		public T next() {
			if (sub.hasNext()) return sub.next();
			if (bpos < children.length - 1) {
				bpos++;
				sub = children[bpos].getValue().listIterator();
				return sub.next();
			}
			;
			throw new NoSuchElementException();
		}

		@Override
		public boolean hasPrevious() {
			if (sub.hasPrevious()) return true;
			return bpos > 0;
		}

		@Override
		public T previous() {
			if (sub.hasPrevious()) return sub.previous();
			if (bpos > 0) {
				bpos = bpos - 1;
				AVector<T> b = children[bpos].getValue();
				sub = b.listIterator(b.count());
				return sub.previous();
			}
			throw new NoSuchElementException();
		}

		@Override
		public int nextIndex() {
			return Utils.checkedInt(childIndex(bpos) + sub.nextIndex());
		}

		@Override
		public int previousIndex() {
			return Utils.checkedInt(childIndex(bpos) + sub.previousIndex());
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

		@Override
		public void set(T e) {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

		@Override
		public void add(T e) {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

	}

	@Override
	public void forEach(Consumer<? super T> action) {
		for (Ref<AVector<T>> r : children) {
			r.getValue().forEach(action);
		}
	}

	@Override
	public boolean anyMatch(Predicate<? super T> pred) {
		for (Ref<AVector<T>> r : children) {
			if (r.getValue().anyMatch(pred)) return true;
		}
		return false;
	}

	@Override
	public boolean allMatch(Predicate<? super T> pred) {
		for (Ref<AVector<T>> r : children) {
			if (!r.getValue().allMatch(pred)) return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> AVector<R> map(Function<? super T, ? extends R> mapper) {
		int blength = children.length;
		Ref<AVector<R>>[] newBlocks = (Ref<AVector<R>>[]) new Ref<?>[blength];
		for (int i = 0; i < blength; i++) {
			AVector<R> r = children[i].getValue().map(mapper);
			newBlocks[i] = r.getRef();
		}
		return new VectorTree<R>(newBlocks, count);
	}

	@Override
	public void visitElementRefs(Consumer<Ref<T>> f) {
		for (Ref<AVector<T>> item : children) {
			item.getValue().visitElementRefs(f);
		}
	}

	@Override
	public <R> R reduce(BiFunction<? super R, ? super T, ? extends R> func, R value) {
		int blength = children.length;
		for (int i = 0; i < blength; i++) {
			value = children[i].getValue().reduce(func, value);
		}
		return value;
	}

	@Override
	public Spliterator<T> spliterator(long position) {
		return new TreeVectorSpliterator(position);
	}

	private class TreeVectorSpliterator implements Spliterator<T> {
		long pos = 0;

		public TreeVectorSpliterator(long position) {
			if ((position < 0) || (position > count))
				throw new IllegalArgumentException(Errors.illegalPosition(position));
			this.pos = position;
		}

		@Override
		public boolean tryAdvance(Consumer<? super T> action) {
			if (pos >= count) return false;
			action.accept((T) get(pos++));
			return true;
		}

		@Override
		public Spliterator<T> trySplit() {
			for (int i = 0; i < children.length; i++) {
				long bpos = childIndex(i);
				AVector<T> b = children[i].getValue();

				long bcount = b.count();
				long blockEnd = childIndex(i) + bcount;
				if (pos < blockEnd) {
					Spliterator<T> ss = b.spliterator(pos - bpos);
					pos = blockEnd;
					return ss;
				}
			}
			return null;
		}

		@Override
		public long estimateSize() {
			return count;
		}

		@Override
		public int characteristics() {
			return Spliterator.IMMUTABLE | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED;
		}
	}

	@Override
	public boolean isCanonical() {
		if (count < MINIMUM_SIZE) return false;
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public final <R extends ACell> AVector<R> toVector() {
		assert (isCanonical());
		return (AVector<R>) this;
	}

	@Override
	public int getRefCount() {
		return children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		int ic = children.length;
		if (i < 0) throw new IndexOutOfBoundsException("Negative Ref index: " + i);
		if (i < ic) return (Ref<R>) children[i];
		throw new IndexOutOfBoundsException("Ref index out of range: " + i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public VectorTree<T> updateRefs(IRefFunction func) {
		int ic = children.length;
		for (int i = 0; i < ic; i++) {
			Ref<AVector<T>> current = children[i];
			Ref<AVector<T>> newChild = (Ref<AVector<T>>) func.apply(current);
			children[i]=newChild;
		}
		return this;
	}

	@Override
	public long commonPrefixLength(AVector<T> b) {
		if (b instanceof VectorTree) return commonPrefixLength((VectorTree<T>) b);
		return b.commonPrefixLength(this); // Handle MapEntry and ListVectors
	}

	private long commonPrefixLength(VectorTree<T> b) {
		if (this.equals(b)) return count;

		long cs = childSize();
		long bcs = b.childSize();
		if (cs == bcs) {
			return commonPrefixLengthAligned(b);
		} else if (cs < bcs) {
			// b is longer
			AVector<T> bChild = b.children[0].getValue();
			return commonPrefixLength(bChild);
		} else {
			// this is longer
			AVector<T> child = children[0].getValue();
			return child.commonPrefixLength(b);
		}
	}

	// compute common prefix length assuming TreeVectors are aligned (same child
	// size)
	private long commonPrefixLengthAligned(VectorTree<T> b) {
		// check if we have the same stored hash. If so, quick exit!
		Hash thisHash = cachedHash();
		if (thisHash != null) {
			Hash bHash = b.cachedHash();
			if ((bHash != null) && (thisHash.equals(bHash))) return count;
		}

		int n = Math.min(children.length, b.children.length);
		long cs = childSize();
		long result = 0;
		for (int i = 0; i < n; i++) {
			long cpl = children[i].getValue().commonPrefixLength(b.children[i].getValue());
			if (cpl < cs) return result + cpl;
			result += cs; // we have validated cs elements as equal
		}
		return result;
	}

	@Override
	public VectorLeaf<T> getChunk(long offset) {
		long cs = childSize();
		int ix = (int) (offset / cs);
		AVector<T> child = children[ix].getValue();
		long cOffset = offset - (ix * cs);
		if (cs == VectorLeaf.MAX_SIZE) {
			if (cOffset != 0) throw new IndexOutOfBoundsException("Index: " + offset);
			return (VectorLeaf<T>) child;
		}
		return child.getChunk(cOffset);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> subVector(long start, long length) {
		checkRange(start, length);
		if ((start & Vectors.BITMASK) == 0) {
			// TODO: this can be fast!
		}
		ACell[] arr = new ACell[Utils.checkedInt(length)];
		for (int i = 0; i < length; i++) {
			arr[i] = get(start + i);
		}
		return (AVector<T>) Vectors.create(arr);
	}

	@Override
	public AVector<T> next() {
		return slice(1L, count - 1);
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		long c = 0;
		int blen = children.length;
		if (blen < 2) throw new InvalidDataException("Insufficient children: " + blen, this);
		long bsize = childSize();
		for (int i = 0; i < blen; i++) {
			ACell ch = children[i].getValue();
			if (!(ch instanceof AVector)) throw new InvalidDataException("Child "+i+" is not a vector!",this);
			@SuppressWarnings("unchecked")
			AVector<T> b=(AVector<T>)ch;
			
			b.validate();
			long expectedChildSize=childSize(count,i);	
			if (expectedChildSize != b.count()) {
				throw new InvalidDataException("Expected block size: " + bsize + " for blocks[" + i + "] but was: "
						+ b.count() + " in BlockVector of size: " + count, this);
			}

			c += b.count();
		}
		if (c != count) {
			throw new InvalidDataException("Expected count: " + count + " but sum of child sizes was: " + c, this);
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		int blen = children.length;
		if (count < blen) throw new InvalidDataException("Implausible low count: " + count, this);

		if (blen < 2) throw new InvalidDataException("Insufficient children: " + blen, this);

	}

	@Override
	public ACell toCanonical() {
		// TODO Should be always true?
		return this;
	}

}
