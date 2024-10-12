package convex.core.data;

import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.SequenceSpliterator;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * Abstract base class for concrete sequential data structure (immutable persistent lists and vectors etc.)
 * 
 * Implements standard java.util.List interface
 *
 * @param <T> Type of list elements
 */
public abstract class ASequence<T extends ACell> extends ACollection<T> implements List<T>, IAssociative<CVMLong,T> {

	public ASequence(long count) {
		super(count);
	}
	
	@Override
	public boolean contains(Object o) {
		if (!(o==null||(o instanceof ACell))) return false;
		return longIndexOf((ACell)o) >= 0;
	}
	
	@Override
	public int indexOf(Object o) {
		if (!(o==null||(o instanceof ACell))) return -1;
		long pos =longIndexOf((ACell) o);
		if (pos < 0) return -1;
		return Utils.checkedInt(pos);
	}

	@Override
	public int lastIndexOf(Object o) {
		if (!(o==null||(o instanceof ACell))) return -1;
		long pos =longLastIndexOf((ACell) o);
		if (pos < 0) return -1;
		return Utils.checkedInt(pos);
	}

	/**
	 * Gets the first long index at which the specified value appears in the the sequence. Similar
	 * to Java's standard List.indexOf(...) but supports long indexes.
	 * 
	 * @param value Any value which could appear as an element of the sequence.
	 * @return Index of the value, or -1 if not found.
	 */
	public abstract long longIndexOf(ACell value);

	/**
	 * Gets the last long index at which the specified value appears in the the sequence.
	 * 
	 * Similar to Java's standard List.lastIndexOf(...) but supports long indexes.
	 * 
	 * @param value Any value which could appear as an element of the sequence.
	 * @return Index of the value, or -1 if not found.
	 */
	public abstract long longLastIndexOf(ACell value);

	@Override
	public abstract <R extends ACell> ASequence<R> map(Function<? super T, ? extends R> mapper);

	@Override
	public abstract void forEach(Consumer<? super T> action);

	/**
	 * Visits all elements in this sequence, calling the specified consumer for each.
	 * 
	 * @param f Function to call for each element
	 */
	public abstract void visitElementRefs(Consumer<Ref<T>> f);

	@SuppressWarnings("unchecked")
	public <R extends ACell> ASequence<R> flatMap(Function<? super T, ? extends ASequence<R>> mapper) {
		ASequence<ASequence<R>> vals = this.map(mapper);
		ASequence<R> result = (ASequence<R>) this.empty();
		for (ASequence<R> seq : vals) {
			result = result.concat(seq);
		}
		return result;
	}

	/**
	 * Concatenates the elements from another sequence to the end of this sequence.
	 * Potentially O(n) in size of resulting sequence
	 * 
	 * @param vals A sequence of values to concatenate.
	 * @return The concatenated sequence, of the same type as this sequence.
	 */
	public abstract ASequence<T> concat(ASequence<? extends T> vals);

	@Override
	public final boolean addAll(int index, Collection<? extends T> c) {
		// Convex sequences are never mutable
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	/**
	 * Gets the sequence of all elements after the first, or null if no elements
	 * remain after the first (i.e. count of 0 or 1)
	 * 
	 * @return Sequence following the first element
	 */
	public abstract ASequence<T> next();

	@Override
	public abstract ASequence<T> empty();
	
	/**
	 * Gets the element at the specified index in this sequence.
	 * 
	 * Behaves as if the index was considered as a long
	 * 
	 * @param index Index of element to get
	 * @return Element at the specified index
	 */
	@Override
	public T get(int index) {
		return get((long) index);
	}

	@Override
	public abstract T get(long index);

	/**
	 * Gets the element at the specified key
	 * 
	 * @param key Key of element to get
	 * @return The value at the specified index, or null if not valid
	 */
	@Override
	public T get(ACell key) {
		if (key instanceof AInteger) {
			CVMLong longix=RT.ensureLong(key);
			if (longix==null) return null;
			long ix = longix.longValue();
			if ((ix >= 0) && (ix < count())) return get(ix);
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public ACell get(ACell key, ACell notFound) {
		if (key instanceof CVMLong) {
			long ix = ((CVMLong) key).longValue();
			if ((ix >= 0) && (ix < count())) return get(ix);
		}
		return (T) notFound;
	}

	@Override
	public boolean containsKey(ACell key) {
		// TODO: probably should be AInteger?
		CVMLong index=RT.ensureLong(key);
		if (index!=null) {
			long ix = index.longValue();
			if ((ix >= 0) && (ix < count())) return true;
		}
		return false;
	}

	/**
	 * Gets the element Ref at the specified index
	 * 
	 * @param index Index of element to get
	 * @return Ref to element at specified index
	 */
	public abstract Ref<T> getElementRef(long index);

	@Override
	public T set(int index, T element) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public ASequence<T> assoc(ACell key, ACell value) {
		CVMLong ix=RT.ensureLong(key);
		if (ix==null) return null;
		return assoc(ix.longValue(),(T)value);
	}

	/**
	 * Updates a value at the given position in the sequence.
	 * 
	 * @param i     Index of element to update
	 * @param value New element value
	 * @return Updated sequence, or null if index is out of range
	 */
	public abstract ASequence<T> assoc(long i, T value);

	/**
	 * Checks if an index range is valid for this sequence
	 * 
	 * @param start
	 * @param length
	 */
	protected boolean checkRange(long start, long end) {
		if (start < 0) return false;
		if (start > end) return false;
		if (end > count()) return false;
		return true;
	}

	@Override
	public final void add(int index, T element) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final T remove(int index) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}
	
	/**
	 * Converts this sequence to a new Cell array
	 * @return A new cell array containing the elements of this sequence
	 */
	public ACell[] toCellArray() {
		int n=Utils.checkedInt(count());
		ACell[] cells=new ACell[n];
		for (int i=0; i<n; i++) {
			cells[i]=get(i);
		}
		return cells;
	}

	/**
	 * Adds an element to the sequence in the natural position
	 * 
	 * @param value Value to add
	 * @return Updated sequence, or null if value is invalid
	 */
	@Override
	public abstract ASequence<T> conj(ACell value);

	/**
	 * Produces a slice of this sequence, beginning with the specified start index and of the given length.
	 * The start and end must be contained within this sequence. Will return the same sequence if the
	 * start is zero and the length matches this sequence.
	 * 
	 * @param start Index of the start element
	 * @param end End index(exclusive)
	 * @return A sequence representing the requested slice.
	 */
	public abstract ASequence<T> slice(long start, long end);

	/**
	 * Prepends an element to this sequence, returning a list.
	 * @param x Any new element value
	 * @return A list starting with the new element.
	 */
	public abstract AList<T> cons(T x);

	/**
	 * Gets a vector containing the specified subset of this sequence, or null if range is invalid
	 * 
	 * @param start Start index of sub vector
	 * @param length Length of sub vector to produce
	 * @return Sub-vector of this sequence
	 */
	public abstract AVector<T> subVector(long start, long length);

	@Override
	public final java.util.List<T> subList(int fromIndex, int toIndex) {
		long start = fromIndex;
		long end = toIndex;
		ASequence<T> result= slice(start, end);
		if (result==null) throw new IndexOutOfBoundsException(ErrorMessages.badRange(start, end));
		return result;
	}

	/**
	 * Gets the ListIterator for a long position
	 * 
	 * @param l
	 * @return ListIterator instance.
	 */
	protected abstract ListIterator<T> listIterator(long l);
	
	@Override
	public Spliterator<T> spliterator() {
		return spliterator(0,count);
	}

	/**
	 * Gets a Spliterator for the given range of this sequence
	 * @param start Start position (inclusive)
	 * @param end End position (exclusive)
	 * @return Spliterator instance
	 */
	public Spliterator<T> spliterator(long start, long end) {
		return new SequenceSpliterator<T>(this,start,end);
	}

	/**
	 * Reverses a sequence, converting Lists to Vectors and vice versa
	 * @return Reversed sequence
	 */
	public abstract ASequence<T> reverse();
}
