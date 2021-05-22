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
import convex.core.lang.RT;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * A Persistent Vector implementation representing 0-16 elements with a
 * packed Vector prefix.
 * 
 * Design goals: 
 * <ul>
 * <li>Allows fast access to most recently appended items</li>
 * <li>O(1) append, equals - O(log n) access, update</li>
 * <li>O(log n) comparisons</li>
 * <li>Fast computation of common prefix</li>
 * </ul>
 * 
 * Representation in bytes:
 * 
 * 0x80 ListVector tag byte VLC Long Length of list. >16 implies prefix must be
 * present. Low 4 bits specify N (0 means 16 in presence of prefix) [Ref]*N N
 * Elements with length Tail Ref using prefix hash (omitted if no prefix)
 *
 * @param <T> Type of vector elements
 */
public class VectorLeaf<T extends ACell> extends ASizedVector<T> {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final VectorLeaf<?> EMPTY = new VectorLeaf(new Ref<?>[0]);

	/** Maximum size of a single ListVector before a tail is required */
	public static final int MAX_SIZE = Vectors.CHUNK_SIZE;

	private final Ref<T>[] items;
	private final Ref<AVector<T>> prefix;

	VectorLeaf(Ref<T>[] items, Ref<AVector<T>> prefix, long count) {
		super(count);
		this.items = items;
		this.prefix = prefix;
	}

	VectorLeaf(Ref<T>[] items) {
		this(items, null, items.length);
	}

	/**
	 * Creates a ListVector with the given items
	 * 
	 * @param things
	 * @param offset
	 * @param length
	 * @return New ListVector
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> VectorLeaf<T> create(Object[] things, int offset, int length) {
		if (length == 0) return (VectorLeaf<T>) VectorLeaf.EMPTY;
		if (length > Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Too many elements for ListVector: " + length);
		Ref<T>[] items = new Ref[length];
		for (int i = 0; i < length; i++) {
			T value=RT.cvm(things[i + offset]);
			items[i] = Ref.get(value);
		}
		return new VectorLeaf<T>(items);
	}

	/**
	 * Creates a ListVector with the given items appended to the specified tail
	 * 
	 * @param things
	 * @param offset
	 * @param length
	 * @return The updated ListVector
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> VectorLeaf<T> create(Object[] things, int offset, int length, AVector<T> tail) {
		if (length == 0)
			throw new IllegalArgumentException("ListVector with tail cannot be created with zero head elements");
		if (length > Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Too many elements for ListVector: " + length);
		Ref<T>[] items = new Ref[length];
		for (int i = 0; i < length; i++) {
			T value=RT.cvm(things[i + offset]);
			items[i] = Ref.get(value);
		}
		return new VectorLeaf<T>(items, tail.getRef(), tail.count() + length);
	}

	public static <T extends ACell> VectorLeaf<T> create(T[] things) {
		return create(things, 0, things.length);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final <R extends ACell> AVector<R> toVector() {
		return (AVector<R>) this;
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> append(T value) {
		int localSize = items.length;
		if (localSize < Vectors.CHUNK_SIZE) {
			// extend storage array
			Ref<T>[] newItems = new Ref[localSize + 1];
			System.arraycopy(items, 0, newItems, 0, localSize);
			newItems[localSize] = Ref.get(value);

			if (localSize + 1 == Vectors.CHUNK_SIZE) {
				// need to extend to TreeVector
				VectorLeaf<T> chunk = new VectorLeaf<T>(newItems);
				if (!hasPrefix()) return chunk; // exactly one whole chunk
				return prefix.getValue().appendChunk(chunk);
			} else {
				// just grow current ListVector head
				return new VectorLeaf<T>(newItems, prefix, count + 1);
			}
		} else {
			// this must be a full single chunk already, so turn this into tail of new
			// ListVector
			AVector<T> newTail = this;
			return new VectorLeaf<T>(new Ref[] { Ref.get(value) }, newTail.getRef(), count + 1);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> AVector<R> concat(ASequence<R> b) {
		// Maybe can optimise?
		long aLen = count();
		long bLen = b.count();
		AVector<R> result = (AVector<R>) this;
		long i = aLen;
		long end = aLen + bLen;
		while (i < end) {
			if ((i & Vectors.BITMASK) == 0) {
				int rn = Utils.checkedInt(Math.min(Vectors.CHUNK_SIZE, end - i));
				if (rn == Vectors.CHUNK_SIZE) {
					// we can append a whole chunk
					result = result.appendChunk((VectorLeaf<R>) b.subVector(i - aLen, rn));
					i += Vectors.CHUNK_SIZE;
					continue;
				}
			}
			// otherwise just append one-by-one
			result = result.append(b.get(i - aLen));
			i++;
		}
		return result;
	}

	@Override
	public AVector<T> appendChunk(VectorLeaf<T> chunk) {
		if (chunk.count != Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Can't append a chunk of size: " + chunk.count());

		if (this.count == 0) return chunk;
		if (this.hasPrefix()) {
			throw new IllegalArgumentException(
					"Can't append chunk to a ListVector with a tail (length = " + count + ")");
		}
		if (this.count != Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Can't append chunk to a ListVector of size: " + this.count);
		return VectorTree.wrap2(chunk, this);
	}

	@Override
	public T get(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long ix = i - prefixLength();
		if (ix >= 0) {
			return items[(int) ix].getValue();
		} else {
			return prefix.getValue().get(i);
		}
	}

	@Override
	protected Ref<T> getElementRef(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long ix = i - prefixLength();
		if (ix >= 0) {
			return items[(int) ix];
		} else {
			return prefix.getValue().getElementRef(i);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public <R  extends ACell> AVector<R> assoc(long i, R value) {
		if ((i < 0) || (i >= count)) return null;
		
		long ix = i - prefixLength();
		if (ix >= 0) {
			R old = (R) items[(int) ix].getValue();
			if (old == value) return (AVector<R>) this;
			Ref<R>[] newItems = (Ref<R>[]) items.clone();
			newItems[(int) ix] = Ref.get(value);
			return new VectorLeaf<R>(newItems, (Ref)prefix, count);
		} else {
			AVector<T> tl = prefix.getValue();
			AVector<R> newTail = tl.assoc(i, value);
			if (tl == newTail) return (AVector<R>) this;
			return new VectorLeaf<R>((Ref[])items, newTail.getRef(), count);
		}
	}

	/**
	 * Reads a ListVector from the provided ByteBuffer 
	 * 
	 * Assumes the header byte and count is already read.
	 * 
	 * @param bb
	 * @param count
	 * @return ListVector read from ByteBuffer
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> VectorLeaf<T> read(ByteBuffer bb, long count) throws BadFormatException {
		if (count < 0) throw new BadFormatException("Negative length");
		if (count == 0) return (VectorLeaf<T>) EMPTY;
		boolean prefixPresent = count > MAX_SIZE;

		int n = ((int) count) & 0xF;
		if (n == 0) {
			if (count > 16) throw new BadFormatException("Vector not valid for size 0 mod 16: " + count);
			n = VectorLeaf.MAX_SIZE; // we know this must be true since zero already caught
		}

		Ref<T>[] items = (Ref<T>[]) new Ref<?>[n];
		for (int i = 0; i < n; i++) {
			Ref<T> ref = Format.readRef(bb);
			items[i] = ref;
		}

		Ref<AVector<T>> tail = null;
		if (prefixPresent) {
			tail=Format.readRef(bb);
		}

		return new VectorLeaf<T>(items, tail, count);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.VECTOR;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		int ilength = items.length;
		boolean hasPrefix = hasPrefix();

		// count field
		pos = Format.writeVLCLong(bs,pos, count);

		for (int i = 0; i < ilength; i++) {
			pos= items[i].encode(bs,pos);
		}

		if (hasPrefix) {
			pos = prefix.encode(bs,pos);
		}
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		// allow space for header, reasonable length, 33 bytes per element ref plus tail
		// ref
		return 1 + 9 + Format.MAX_EMBEDDED_LENGTH * items.length + ((count > 16) ? 33 : 00);
	}
	
	@Override
	public long getEncodingLength() {
		if (encoding!=null) return encoding.count();
		
		// tag and count
		long length=1+Format.getVLCLength(count);
		int n = items.length;
		if (prefix!=null) length+=prefix.getEncodingLength();
		for (int i = 0; i < n; i++) {
			length+=items[i].getEncodingLength();
		}
		return length;
	}

	/**
	 * Returns true if this ListVector has a prefix AVector.
	 * 
	 * @return true if this ListVector has a prefix, false otherwise
	 */
	public boolean hasPrefix() {
		return prefix != null;
	}

	public VectorLeaf<T> withPrefix(AVector<T> newPrefix) {
		if ((newPrefix == null) && !hasPrefix()) return this;
		long tc = (newPrefix == null) ? 0L : newPrefix.count();
		return new VectorLeaf<T>(items, (newPrefix == null) ? null : newPrefix.getRef(), tc + items.length);
	}

	@Override
	public boolean isPacked() {
		return (!hasPrefix()) && (items.length == Vectors.CHUNK_SIZE);
	}

	@Override
	public ListIterator<T> listIterator() {
		return listIterator(0);
	}

	@Override
	public ListIterator<T> listIterator(long index) {
		return new ListVectorIterator(index);
	}

	/**
	 * Custom ListIterator for ListVector
	 */
	private class ListVectorIterator implements ListIterator<T> {
		ListIterator<T> prefixIterator;
		int pos;

		public ListVectorIterator(long index) {
			if (index < 0L) throw new NoSuchElementException();

			long tc = prefixLength();
			if (index >= tc) {
				// in the list head
				if (index > count) throw new NoSuchElementException();
				pos = (int) (index - tc);
				this.prefixIterator = (prefix == null) ? null : prefix.getValue().listIterator(tc);
			} else {
				// in the prefix
				pos = 0;
				this.prefixIterator = (prefix == null) ? null : prefix.getValue().listIterator(index);
			}
		}

		@Override
		public boolean hasNext() {
			if ((prefixIterator != null) && prefixIterator.hasNext()) return true;
			return pos < items.length;
		}

		@Override
		public T next() {
			if (prefixIterator != null) {
				if (prefixIterator.hasNext()) return prefixIterator.next();
			}
			return items[pos++].getValue();
		}

		@Override
		public boolean hasPrevious() {
			if (pos > 0) return true;
			if (prefixIterator != null) return prefixIterator.hasPrevious();
			return false;
		}

		@Override
		public T previous() {
			if (pos > 0) return items[--pos].getValue();

			if (prefixIterator != null) return prefixIterator.previous();
			throw new NoSuchElementException();
		}

		@Override
		public int nextIndex() {
			if ((prefixIterator != null) && prefixIterator.hasNext()) return prefixIterator.nextIndex();
			return Utils.checkedInt(prefixLength() + pos);
		}

		@Override
		public int previousIndex() {
			if (pos > 0) return Utils.checkedInt(prefixLength() + pos - 1);
			if (prefixIterator != null) return prefixIterator.previousIndex();
			return -1;
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

	public long prefixLength() {
		return count - items.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <K> void copyToArray(K[] arr, int offset) {
		int s = size();
		if (prefix != null) {
			prefix.getValue().copyToArray(arr, offset);
		}
		int ilen = items.length;
		for (int i = 0; i < ilen; i++) {
			K value = (K) items[i].getValue();
			;
			arr[offset + s - ilen + i] = value;
		}
	}

	@Override
	public long longIndexOf(Object o) {
		if (prefix != null) {
			long pi = prefix.getValue().longIndexOf(o);
			if (pi >= 0L) return pi;
		}
		for (int i = 0; i < items.length; i++) {
			if (Utils.equals(items[i].getValue(), o)) return (count - items.length + i);
		}
		return -1L;
	}

	@Override
	public long longLastIndexOf(Object o) {
		for (int i = items.length - 1; i >= 0; i--) {
			if (Utils.equals(items[i].getValue(), o)) return (count - items.length + i);
		}
		if (prefix != null) {
			long ti = prefix.getValue().longLastIndexOf(o);
			if (ti >= 0L) return ti;
		}
		return -1L;
	}

	@Override
	public void forEach(Consumer<? super T> action) {
		if (prefix != null) {
			prefix.getValue().forEach(action);

			for (Ref<T> r : items) {
				action.accept(r.getValue());
			}
		}
	}

	@Override
	public boolean anyMatch(Predicate<? super T> pred) {
		if ((prefix != null) && (prefix.getValue().anyMatch(pred))) return true;
		for (Ref<T> r : items) {
			if (pred.test(r.getValue())) return true;
		}
		return false;
	}

	@Override
	public boolean allMatch(Predicate<? super T> pred) {
		if ((prefix != null) && !(prefix.getValue().allMatch(pred))) return false;
		for (Ref<T> r : items) {
			if (!pred.test(r.getValue())) return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> AVector<R> map(Function<? super T, ? extends R> mapper) {
		Ref<AVector<R>> newPrefix = (prefix == null) ? null : prefix.getValue().map(mapper).getRef();

		int ilength = items.length;
		Ref<R>[] newItems = (Ref<R>[]) new Ref[ilength];
		for (int i = 0; i < ilength; i++) {
			R r = mapper.apply(items[i].getValue());
			newItems[i] = Ref.get(r);
		}

		return (prefix == null) ? new VectorLeaf<R>(newItems) : new VectorLeaf<R>(newItems, newPrefix, count);
	}

	@Override
	public void visitElementRefs(Consumer<Ref<T>> f) {
		if (prefix != null) prefix.getValue().visitElementRefs(f);
		for (Ref<T> item : items) {
			f.accept(item);
		}
	}

	@Override
	public <R> R reduce(BiFunction<? super R, ? super T, ? extends R> func, R value) {
		if (prefix != null) value = prefix.getValue().reduce(func, value);
		int ilength = items.length;
		for (int i = 0; i < ilength; i++) {
			value = func.apply(value, items[i].getValue());
		}
		return value;
	}

	@Override
	public Spliterator<T> spliterator(long position) {
		return new ListVectorSpliterator(position);
	}

	private class ListVectorSpliterator implements Spliterator<T> {
		long pos = 0;

		public ListVectorSpliterator(long position) {
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
			long tlength = prefixLength();
			if (pos < tlength) {
				pos = tlength;
				return prefix.getValue().spliterator(pos);
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
		if ((count > MAX_SIZE) && (prefix == null)) throw new Error("Invalid Listvector!");
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@Override
	public int getRefCount() {
		return items.length + (hasPrefix() ? 1 : 0);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (prefix != null) {
			if (i==0) return (Ref<R>) prefix;
			i--; // DEcrement so that i indexes into child array after skipping prefix ref
		}
		int itemsCount = items.length;
		if (i < 0) throw new IndexOutOfBoundsException("Negative Ref index: " + i);
		if (i < itemsCount) return (Ref<R>) items[i];
		throw new IndexOutOfBoundsException("Ref index out of range: " + i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public VectorLeaf<T> updateRefs(IRefFunction func) {
		Ref<?> newPrefix = (prefix == null) ? null : func.apply(prefix); // do this first for in-order traversal
		int ic = items.length;
		Ref<?>[] newItems = items;
		for (int i = 0; i < ic; i++) {
			Ref<?> current = items[i];
			Ref<?> newItem = func.apply(current);
			if (newItem!=current) {
				if (items==newItems) newItems=items.clone();
				newItems[i] = newItem;
			}
		}
		if ((items==newItems) && (prefix == newPrefix)) return this; // if no change, safe to return this
		return new VectorLeaf<T>((Ref<T>[]) newItems, (Ref<AVector<T>>) newPrefix, count);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(ACell a) {
		if (!(a instanceof VectorLeaf)) return false;
		return equals((VectorLeaf<T>) a);
	}

	public boolean equals(VectorLeaf<T> v) {
		if (this == v) return true;
		if (this.count != v.count()) return false;
		if (!Utils.equals(this.prefix, v.prefix)) return false;
		for (int i = 0; i < items.length; i++) {
			if (!items[i].equalsValue(v.items[i])) return false;
		}
		return true;
	}

	@Override
	public long commonPrefixLength(AVector<T> b) {
		long n = count();
		if (this.equals(b)) return n;
		int il = items.length;
		long prefixLength = n - il;
		if (prefixLength > 0) {
			long prefixMatchLength = prefix.getValue().commonPrefixLength(b);
			if (prefixMatchLength < prefixLength) return prefixMatchLength; // matched segment entirely within prefix
		}
		// must have matched prefixLength at least
		long nn = Math.min(n, b.count()) - prefixLength; // number of extra elements to check
		for (int i = 0; i < nn; i++) {
			if (!items[i].equalsValue(b.getElementRef(prefixLength + i))) {
				return prefixLength + i;
			}
		}
		return prefixLength + nn;
	}

	@Override
	public VectorLeaf<T> getChunk(long offset) {
		if (prefix == null) {
			if (items.length != MAX_SIZE) throw new IllegalStateException("Can only get full chunk");
			if (offset != 0) throw new IndexOutOfBoundsException("Chunk offset must be zero");
			return this;
		} else {
			return prefix.getValue().getChunk(offset);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R  extends ACell> AVector<R> subVector(long start, long length) {
		checkRange(start, length);
		if (length == count) return (AVector<R>) this;

		if (prefix == null) {
			int len = Utils.checkedInt(length);
			Ref<R>[] newItems = new Ref[len];
			System.arraycopy(items, Utils.checkedInt(start), newItems, 0, len);
			return new VectorLeaf<R>(newItems, null, length);
		} else {
			long tc = prefixLength();
			if (start >= tc) {
				return this.withPrefix(null).subVector(start - tc, length);
			}

			AVector<T> tv = prefix.getValue();
			if ((start + length) <= tc) {
				return tv.subVector(start, length);
			} else {
				long split = tc - start;
				return tv.subVector(start, split).concat(this.withPrefix(null).subVector(0, length - split));
			}
		}
	}

	@Override
	public AVector<T> next() {
		if (count <= 1) return null;
		return slice(1, count - 1);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void validate() throws InvalidDataException {
		// TODO: Needs to ensure children are validated?
		super.validate();
		if (prefix != null) {
			// if we have a prefix, should be 1..15 elements only
			if (count == Vectors.CHUNK_SIZE) {
				throw new InvalidDataException("Full ListVector with prefix? This is not right...", this);
			}

			if (count == 0) {
				throw new InvalidDataException("Empty ListVector with prefix? This is not right...", this);
			}

			ACell ccell=prefix.getValue();
			if (!(ccell instanceof AVector)) {
				throw new InvalidDataException("Prefix is not a vector", this);			
			}
			
			AVector<T> tv = (AVector<T>)ccell;
			if (prefixLength() != tv.count()) {
				throw new InvalidDataException("Expected prefix length: " + prefixLength() + " but found " + tv.count(),
						this);
			}
			tv.validate();
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((count > 0) && (items.length == 0)) throw new InvalidDataException("Should be items present!", this);
		if (!isCanonical()) throw new InvalidDataException("Not a canonical ListVector!", this);
	}

}
