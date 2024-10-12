package convex.core.data;

import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.ErrorMessages;
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
 * <ul>
 * <li>0x80 - VectorLeaf tag byte </li>
 * <li>VLC Long - Length of list. Greater than 16 implies prefix must be
 * present. Low 4 bits specify N (0 means 16 in presence of prefix) </li>
 * <li>[Ref]*N - N Elements with length </li>
 * <li>Ref? - Tail Ref (excluded if not present)</li>
 * </ul>
 * 
 * @param <T> Type of vector elements
 */
public class VectorLeaf<T extends ACell> extends AVector<T> {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final VectorLeaf<?> EMPTY = Cells.intern(new VectorLeaf(new Ref<?>[0]));
	
	public static final Ref<VectorLeaf<?>> EMPTY_REF = EMPTY.getRef();

	/** Maximum size of a single VectorLeaf before a tail is required */
	public static final int MAX_SIZE = Vectors.CHUNK_SIZE;

	/**
	 * Ref to prefix vector. May be null, indicating a leaf node of 0-16 elements
	 */
	private Ref<AVector<T>> prefix;
	
	/**
	 * Refs to items at end of vector. May be 0-16 elements
	 * Can only be 16 items if no prefix is present (otherwise promotes to VectorTree)
	 */
	private final Ref<T>[] items;

	VectorLeaf(Ref<T>[] items, Ref<AVector<T>> prefix, long count) {
		super(count);
		this.items = items;
		this.prefix = prefix;
	}

	VectorLeaf(Ref<T>[] items) {
		this(items, null, items.length);
	}

	/**
	 * Creates a VectorLeaf with the given items
	 * 
	 * @param elements Elements to add
	 * @param offset Offset into element array
	 * @param length Number of elements to include from array
	 * @return New VectorLeaf
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> VectorLeaf<T> create(ACell[] elements, int offset, int length) {
		if (length == 0) return (VectorLeaf<T>) VectorLeaf.EMPTY;
		if (length > Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Too many elements for VectorLeaf: " + length);
		Ref<T>[] items = new Ref[length];
		for (int i = 0; i < length; i++) {
			T value=(T) elements[i + offset];
			items[i] = Ref.get(value);
		}
		return new VectorLeaf<T>(items);
	}

	/**
	 * Creates a VectorLeaf with the given items appended to the specified prefix vector
	 * 
	 * @param elements Elements to add
	 * @param offset Offset into element array
	 * @param length Number of elements to include from array
	 * @param prefix Prefix vector to append to
	 * @return The updated VectorLeaf
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> VectorLeaf<T> create(ACell[] elements, int offset, int length, AVector<T> prefix) {
		if (length == 0)
			throw new IllegalArgumentException("VectorLeaf with tail cannot be created with zero head elements");
		if (length > Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Too many elements for VectorLeaf: " + length);
		Ref<T>[] items = new Ref[length];
		for (int i = 0; i < length; i++) {
			T value=(T) elements[i + offset];
			items[i] = Ref.get(value);
		}
		return new VectorLeaf<T>(items, prefix.getRef(), prefix.count() + length);
	}

	public static <T extends ACell> VectorLeaf<T> create(T[] things) {
		return create(things, 0, things.length);
	}

	@Override
	public final AVector<T> toVector() {
		return this;
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
				// just grow current VectorLeaf head
				return new VectorLeaf<T>(newItems, prefix, count + 1);
			}
		} else {
			// this must be a full single chunk already, so turn this into prefix of new
			// VectorLeaf
			AVector<T> newPrefix = this;
			return new VectorLeaf<T>(new Ref[] { Ref.get(value) }, newPrefix.getRef(), count + 1);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> concat(ASequence<? extends T> b) {
		// Maybe can optimise?
		long aLen = count();
		long bLen = b.count();
		AVector<T> result = this;
		long i = aLen;
		long end = aLen + bLen;
		while (i < end) {
			if ((i & Vectors.BITMASK) == 0) {
				int rn = Utils.checkedInt(Math.min(Vectors.CHUNK_SIZE, end - i));
				if (rn == Vectors.CHUNK_SIZE) {
					// we can append a whole chunk. Not toVector in case of VectorArrays
					result = result.appendChunk((AVector<T>) b.subVector(i - aLen, rn));
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
	public AVector<T> appendChunk(AVector<T> chunk) {
		if (chunk.count != Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Can't append a chunk of size: " + chunk.count());

		if (this.count == 0) return chunk;
		if (this.hasPrefix()) {
			throw new IllegalArgumentException(
					"Can't append chunk to a VectorLeaf with a tail (length = " + count + ")");
		}
		if (this.count != Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Can't append chunk to a VectorLeaf of size: " + this.count);
		return VectorTree.wrap2((VectorLeaf<T>)(chunk.toVector()), this);
	}

	@Override
	public T get(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		return getElementRefUnsafe(i).getValue();
	}

	@Override
	public Ref<T> getElementRef(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		return getElementRefUnsafe(i);
	}
	
	@Override
	protected Ref<T> getElementRefUnsafe(long i) {
		long ix = i - prefixLength();
		if (ix >= 0) {
			return items[(int) ix];
		} else {
			return prefix.getValue().getElementRefUnsafe(i);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public AVector<T> assoc(long i, T value) {
		if ((i < 0) || (i >= count)) return null;
		
		long ix = i - prefixLength();
		if (ix >= 0) {
			T old = items[(int) ix].getValue();
			if (old == value) return this;
			Ref<T>[] newItems = (Ref<T>[]) items.clone();
			newItems[(int) ix] = Ref.get(value);
			return new VectorLeaf<T>(newItems, (Ref)prefix, count);
		} else {
			AVector<T> tl = prefix.getValue();
			AVector<T> newTail = tl.assoc(i, value);
			if (tl == newTail) return (AVector<T>) this;
			return new VectorLeaf<T>((Ref[])items, newTail.getRef(), count);
		}
	}

	/**
	 * Reads a {@link VectorLeaf} from the provided Blob 
	 * 
	 * Assumes the header byte and count is already read.
	 * 
	 * @param b Blob to read from
	 * @param count Number of elements, assumed to be valid
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> VectorLeaf<T> read(long count, Blob b, int pos) throws BadFormatException {
		if (count == 0) return (VectorLeaf<T>)EMPTY;
		
		int n = ((int) count) & 0xF;
		if (n == 0) {
			if (count > 16) throw new BadFormatException("Vector not valid for size 0 mod 16: " + count);
			n = VectorLeaf.MAX_SIZE; // we know this must be true since zero already caught
		}
		
		int rpos=pos+1+Format.getVLQCountLength(count); // skip tag and count
		Ref<T>[] items = (Ref<T>[]) new Ref<?>[n];
		for (int i = 0; i < n; i++) {
			Ref<T> ref = Format.readRef(b,rpos);
			items[i] = ref;
			rpos+=ref.getEncodingLength();
		}
		
		Ref<AVector<T>> pfx = null;
		boolean prefixPresent = count > MAX_SIZE;
		if (prefixPresent) {
			pfx=Format.readRef(b,rpos);
			rpos+=pfx.getEncodingLength();
		}

		VectorLeaf<T> result=new VectorLeaf<T>(items, pfx, count);
		result.attachEncoding(b.slice(pos, rpos));
		return result;
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
		pos = Format.writeVLQCount(bs,pos, count);

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
		if (count<2) {
			if (count==0) return 2;
			return 2+Format.MAX_EMBEDDED_LENGTH;
		}
		// allow space for header of reasonable length
		// Estimate 64 bytes per element ref (plus space for tail/ other overhead)
		int ESTIMATED_REF_SIZE=70;
		return 1 + 9 + ESTIMATED_REF_SIZE * (items.length + 2);
	}
	
	@Override
	public int getEncodingLength() {
		if (encoding!=null) return encoding.size();
		
		// tag and count
		int length=1+Format.getVLQCountLength(count);
		int n = items.length;
		if (prefix!=null) length+=prefix.getEncodingLength();
		for (int i = 0; i < n; i++) {
			length+=items[i].getEncodingLength();
		}
		return length;
	}
	 
	public static final int MAX_ENCODING_LENGTH = 1 + Format.MAX_VLQ_COUNT_LENGTH + VectorTree.MAX_EMBEDDED_LENGTH+Format.MAX_EMBEDDED_LENGTH * (MAX_SIZE);

	/**
	 * Returns true if this VectorLeaf has a prefix AVector.
	 * 
	 * @return true if this VectorLeaf has a prefix, false otherwise
	 */
	public boolean hasPrefix() {
		return prefix != null;
	}

	public VectorLeaf<T> withPrefix(AVector<T> newPrefix) {
		if ((newPrefix == null) && !hasPrefix()) return this;
		long newPC = (newPrefix == null) ? 0L : newPrefix.count();
		if ((newPC&0x0F)!=0) throw new IllegalArgumentException("Prefix must be fully packed!");
		return new VectorLeaf<T>(items, (newPrefix == null) ? null : newPrefix.getRef(), newPC + items.length);
	}

	@Override
	public boolean isFullyPacked() {
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
	 * Custom ListIterator for VectorLeaf
	 */
	private class ListVectorIterator implements ListIterator<T> {
		ListIterator<T> prefixIterator;
		int pos;

		public ListVectorIterator(long index) {
			if (index < 0L) throw new IndexOutOfBoundsException((int)index);

			long tc = prefixLength();
			if (index >= tc) {
				// in the list head
				if (index > count) throw new IndexOutOfBoundsException((int)index);
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
			throw new UnsupportedOperationException(ErrorMessages.immutable(this));
		}

		@Override
		public void set(T e) {
			throw new UnsupportedOperationException(ErrorMessages.immutable(this));
		}

		@Override
		public void add(T e) {
			throw new UnsupportedOperationException(ErrorMessages.immutable(this));
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
	public long longIndexOf(ACell o) {
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
	public long longLastIndexOf(ACell o) {
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
			Ref<T> iref=items[i];
			R r = mapper.apply(iref.getValue());
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
	public boolean isCanonical() {
		return true;
	}
	
	@Override
	public ACell toCanonical() {
		return this;
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
			i--; // Decrement so that i indexes into child array after skipping prefix ref
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
		VectorLeaf<T> result= new VectorLeaf<T>((Ref<T>[]) newItems, (Ref<AVector<T>>) newPrefix, count);
		
		result.attachEncoding(encoding); // this is an optimisation to avoid re-encoding
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(AVector<? super T> a) {
		if (a instanceof VectorLeaf) return equals((VectorLeaf<T>)a);
		if (!(a instanceof AVector)) return false;
		
		AVector<T> v=(AVector<T>) a;
		if (v.count()!=count) return false;
		
		// It's a vector of same length, but not canonical?
		return a.getEncoding().equals(this.getEncoding());
	}

	public boolean equals(VectorLeaf<T> v) {
		if (this == v) return true;
		if (this.count != v.count()) return false;
		if (!Utils.equals(this.prefix, v.prefix)) return false;
		for (int i = 0; i < items.length; i++) {
			if (!items[i].equals(v.items[i])) return false;
		}
		return true;
	}

	@Override
	public long commonPrefixLength(AVector<T> b) {
		long n = count();
		if (this==b) return n;
		int il = items.length;
		long prefixLength = n - il;
		if (prefixLength > 0) {
			long prefixMatchLength = prefix.getValue().commonPrefixLength(b);
			if (prefixMatchLength < prefixLength) return prefixMatchLength; // matched segment entirely within prefix
		}
		// must have matched prefixLength at least
		long nn = Math.min(n, b.count()) - prefixLength; // number of extra elements to check
		if (nn==0) return prefixLength;
		VectorLeaf<T> bChunk=b.getChunk(prefixLength);
		for (int i = 0; i < nn; i++) {
			if (!items[i].equals(bChunk.items[i])) {
				return prefixLength + i;
			}
		}
		return prefixLength + nn;
	}

	@Override
	public VectorLeaf<T> getChunk(long offset) {
		if (prefix == null) {
			if (offset == 0) return this;
		} else {
			AVector<T> pre=prefix.getValue();
			long prefixLength=pre.count();
			if (offset<prefixLength) {
				return prefix.getValue().getChunk(offset);
			} else if (offset==prefixLength) {
				return this;
			}
		}
		throw new IndexOutOfBoundsException("Invalid chunk offset: "+offset+" in vector of length "+count);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> slice(long start, long end) {
		if (!checkRange(start, end)) return null;
		if (start == end) return  (AVector<T>) EMPTY;
		if ((start == 0)&&(end==count)) return this;

		long tc = prefixLength();
		if (start >= tc) {
			// range is in last part
			int len = (int)(end-start);
			Ref<T>[] newItems= new Ref[len];
			System.arraycopy(items, Utils.checkedInt(start-tc), newItems, 0, len);
			return new VectorLeaf<T>(newItems, null, len);
		}

		AVector<T> tv = prefix.getValue();
		if (end <= tc) {
			// Range is entirely in prefix
			return tv.slice(start, end);
		} else {
			return tv.slice(start, tc).concat(slice(tc, end));
		}
	}

	@Override
	public AVector<T> next() {
		if (count <= 1) return null;
		return slice(1, count);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void validate() throws InvalidDataException {
		// TODO: Needs to ensure children are validated?
		super.validate();
		if (prefix != null) {
			// if we have a prefix, should be 1..15 elements only
			if (count == Vectors.CHUNK_SIZE) {
				throw new InvalidDataException("Full VectorLeaf with prefix? This is not right...", this);
			}

			if (count == 0) {
				throw new InvalidDataException("Empty VectorLeaf with prefix? This is not right...", this);
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
		if (!isCanonical()) throw new InvalidDataException("Not a canonical VectorLeaf!", this);
	}


	/**
	 * Tests if a given count should result in a VectorLeaf
	 * @param count Count of elements in vector
	 * @return true if vector should be a VectorLeaf, false otherwise
	 */
	public static final boolean isValidCount(long count) {
		// Vector is a VectorLeaf if it is less than or equal to the max size, or has a non-empty tail
		return (count <= VectorLeaf.MAX_SIZE) || ((count & 0x0F) != 0);
	}


}
