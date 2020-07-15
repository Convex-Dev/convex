package convex.core.data;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.crypto.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Persistent Merkle Vector implemented as a tree of chunks
 * 
 * shift indicates the level of the tree: 4 = 1st level, 8 = second etc.
 * 
 * Invariants: - All children except the last must be fully packed - each leaf
 * chunk must be a tailless ListVector of size 16
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
 * @param <T>
 */
public class TreeVector<T> extends AVector<T> {

	public static final int MINIMUM_SIZE = 2 * Vectors.CHUNK_SIZE;
	private final int shift; // bits in each child block
	private final long count; // total count of elements

	private final Ref<AVector<T>>[] children;

	private TreeVector(Ref<AVector<T>>[] children, long count) {
		super();
		this.count = count;
		this.shift = computeShift(count);
		this.children = children;
	}

	/**
	 * Computes the shift value for a BlockVector of the given count Note: if
	 * returns zero, count cannot be supported by a valid BlockVector
	 * 
	 * @param count
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
	 * @param things
	 * @param offset
	 * @param length
	 * @return New TreeVector instance
	 */
	public static <T> TreeVector<T> create(T[] things, int offset, int length) {
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
			bs[i] = Ref.create(Vectors.createChunked(things, offset + i * bSize, bLen));
		}
		TreeVector<T> tv = new TreeVector<T>(bs, length);
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
	protected Ref<T> getElementRef(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long bSize = 1L << shift; // size of a fully packed block
		int b = (int) (i >> shift);
		return children[b].getValue().getElementRef(i - b * bSize);
	}

	@Override
	public AVector<T> assoc(long i, T value) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long bSize = 1L << shift; // size of a fully packed block
		int b = (int) (i >> shift);
		AVector<T> oc = children[b].getValue();
		AVector<T> nc = oc.assoc(i - (b * bSize), value);
		if (oc == nc) return this;

		Ref<AVector<T>>[] newChildren = children.clone();
		newChildren[b] = Ref.create(nc);
		return new TreeVector<T>(newChildren, count);
	}

	@Override
	public long count() {
		return count;
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.VECTOR);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = Format.writeVLCLong(bb, count);

		int ilength = children.length;
		for (int i = 0; i < ilength; i++) {
			bb = children[i].write(bb);
		}
		return bb;
	}

	@Override
	public int estimatedEncodingSize() {
		// Allow tag, long count, 33 bytes per child
		return 12 + 33 * children.length;
	}

	/**
	 * Reads a BlockVector from the provided ByteBuffer Assumes the header byte is
	 * already read.
	 * 
	 * @param bb
	 * @param count
	 * @return TreeVector instance as read from ByteBuffer
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <T> TreeVector<T> read(ByteBuffer bb, long count)
			throws BadFormatException, BufferUnderflowException {
		if (count < 0) throw new BadFormatException("Negative count?");
		int n = computeArraySize(count);

		Ref<AVector<T>>[] items = (Ref<AVector<T>>[]) new Ref<?>[n];
		for (int i = 0; i < n; i++) {
			T o = Format.read(bb);
			if (!(o instanceof Ref)) throw new BadFormatException("Expected item Ref but got: " + o);
			Ref<AVector<T>> ref = (Ref<AVector<T>>) o;
			items[i] = ref;
		}

		return new TreeVector<T>(items, count);
	}

	@SuppressWarnings("unchecked")
	@Override
	public TreeVector<T> appendChunk(ListVector<T> b) {
		if (b.hasPrefix()) throw new IllegalArgumentException("Can't append a block with a tail");
		if (b.count() != ListVector.MAX_SIZE)
			throw new IllegalArgumentException("Invalid block size for append: " + b.count());
		if (isPacked()) {
			// full blockvector, so need to elevate to the next level
			Ref<AVector<T>>[] newBlocks = new Ref[2];
			newBlocks[0] = Ref.create(this);
			newBlocks[1] = Ref.create(b);
			return new TreeVector<T>(newBlocks, this.count() + b.count());
		}

		int blength = children.length;
		AVector<T> lastBlock = children[blength - 1].getValue();
		if (lastBlock.count() == childSize()) {
			// need to extend block array
			Ref<AVector<T>>[] newBlocks = new Ref[blength + 1];
			System.arraycopy(children, 0, newBlocks, 0, blength);
			newBlocks[blength] = Ref.create(b);
			return new TreeVector<T>(newBlocks, count + Vectors.CHUNK_SIZE);
		} else {
			// add b into current last block
			AVector<T> newLast = lastBlock.appendChunk(b);
			Ref<AVector<T>>[] newBlocks = new Ref[blength];
			System.arraycopy(children, 0, newBlocks, 0, blength - 1);
			newBlocks[blength - 1] = Ref.create(newLast);
			return new TreeVector<T>(newBlocks, count + Vectors.CHUNK_SIZE);
		}
	}

	/**
	 * Get the child size in number of chunks (for all except the last child)
	 */
	private long childSize() {
		return 1L << (shift);
	}

	@Override
	public boolean isPacked() {
		return count == 1 << (shift + 4);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public AVector<T> append(T value) {
		return new ListVector(new Ref[] { Ref.create(value) }, Ref.create(this), count + 1);
	}

	@Override
	public AVector<T> concat(ASequence<T> b) {
		long bLen = b.count();
		TreeVector<T> result = this;
		long bi = 0;
		while (bi < bLen) {
			if ((bi + Vectors.CHUNK_SIZE) <= bLen) {
				// can append a whole chunk
				ListVector<T> chunk = (ListVector<T>) b.subVector(bi, Vectors.CHUNK_SIZE);
				result = result.appendChunk(chunk);
				bi += Vectors.CHUNK_SIZE;
			} else {
				// we have less than a chunk left, so final result must be a ListVector with the
				// current result as tail
				ListVector<T> head = (ListVector<T>) b.subVector(bi, bLen - bi);
				return head.withPrefix(result);
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
	static <T> TreeVector<T> wrap2(ListVector<T> head, ListVector<T> tail) {
		Ref<AVector<T>>[] newBlocks = new Ref[2];
		newBlocks[0] = Ref.create(tail);
		newBlocks[1] = Ref.create(head);
		return new TreeVector<T>(newBlocks, 2 * Vectors.CHUNK_SIZE);
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
		return new TreeVectorIterator();
	}

	@Override
	public ListIterator<T> listIterator(long index) {
		return new TreeVectorIterator(index);
	}

	private class TreeVectorIterator implements ListIterator<T> {
		int bpos;
		ListIterator<T> sub;

		public TreeVectorIterator() {
			this(0);
		}

		public TreeVectorIterator(long index) {
			if (index < 0L) throw new NoSuchElementException();

			bpos = 0;
			for (int i = 0; i < children.length; i++) {
				AVector<T> b = children[bpos].getValue();
				long bc = b.count();
				if (index <= bc) {
					sub = b.listIterator(index);
					return;
				}
				index -= bc;
				bpos++;
			}
			;
			// appears to be beyond the end?
			throw new NoSuchElementException();
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
	public <R> AVector<R> map(Function<? super T, ? extends R> mapper) {
		int blength = children.length;
		Ref<AVector<R>>[] newBlocks = (Ref<AVector<R>>[]) new Ref<?>[blength];
		for (int i = 0; i < blength; i++) {
			AVector<R> r = children[i].getValue().map(mapper);
			newBlocks[i] = Ref.create(r);
		}
		return new TreeVector<R>(newBlocks, count);
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

	@Override
	public final AVector<T> toVector() {
		assert (isCanonical());
		return this;
	}

	@Override
	public int getRefCount() {
		return children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		int ic = children.length;
		if (i < 0) throw new IndexOutOfBoundsException("Negative Ref index: " + i);
		if (i < ic) return (Ref<R>) children[i];
		throw new IndexOutOfBoundsException("Ref index out of range: " + i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public TreeVector<T> updateRefs(IRefFunction func) {
		int ic = children.length;
		Ref<AVector<T>>[] newChildren = children;
		for (int i = 0; i < ic; i++) {
			Ref<AVector<T>> current = children[i];
			Ref<AVector<T>> newChild = (Ref<AVector<T>>) func.apply(current);
			
			if (newChild!=current) {
				if (children==newChildren) newChildren=children.clone();
				newChildren[i] = newChild;
			}
		}
		if (newChildren==children) return this; // no change, safe to return this
		return new TreeVector<>(newChildren, count);
	}

	@Override
	public long commonPrefixLength(AVector<T> b) {
		if (b instanceof TreeVector) return commonPrefixLength((TreeVector<T>) b);
		return b.commonPrefixLength(this); // Handle MapEntry and ListVectors
	}

	private long commonPrefixLength(TreeVector<T> b) {
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
	private long commonPrefixLengthAligned(TreeVector<T> b) {
		// check if we have the same stored hash. If so, quick exit!
		Hash thisHash = checkHash();
		if (thisHash != null) {
			Hash bHash = b.checkHash();
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
	public ListVector<T> getChunk(long offset) {
		long cs = childSize();
		int ix = (int) (offset / cs);
		AVector<T> child = children[ix].getValue();
		long cOffset = offset - (ix * cs);
		if (cs == ListVector.MAX_SIZE) {
			if (cOffset != 0) throw new IndexOutOfBoundsException("Index: " + offset);
			return (ListVector<T>) child;
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
		Object[] arr = new Object[Utils.checkedInt(length)];
		for (int i = 0; i < length; i++) {
			arr[i] = get(start + i);
		}
		return (AVector<T>) Vectors.of(arr);
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
			AVector<T> b = children[i].getValue();
			b.validate();
			if (i < (blen - 1)) {
				if (bsize != b.count()) {
					throw new InvalidDataException("Expected block size: " + bsize + " for blocks[" + i + "] but was: "
							+ b.count() + " in BlockVector of size: " + count, this);
				}
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

}
