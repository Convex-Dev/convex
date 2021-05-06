package convex.core.data;

import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.prim.CVMLong;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Abstract base class for persistent lists and vectors
 *
 * @param <T> Type of list elements
 */
public abstract class ASequence<T extends ACell> extends ACollection<T> implements List<T>, IAssociative<CVMLong,T> {

	@Override
	public boolean contains(Object o) {
		return longIndexOf(o) >= 0;
	}

	/**
	 * Gets the first long index at which the specified value appears in the the sequence.
	 * @param value Any value which could appear as an element of the sequence.
	 * @return Index of the value, or -1 if not found.
	 */
	public abstract long longIndexOf(Object value);

	/**
	 * Gets the last long index at which the specified value appears in the the sequence.
	 * @param value Any value which could appear as an element of the sequence.
	 * @return Index of the value, or -1 if not found.
	 */
	public abstract long longLastIndexOf(Object value);

	public abstract <R extends ACell> ASequence<R> map(Function<? super T, ? extends R> mapper);

	@Override
	public abstract void forEach(Consumer<? super T> action);

	/**
	 * Visits all elements in this sequence, callin the specified consumer for each.
	 * 
	 * @param f
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
	 * @param vals A sequence of values to concatenate.
	 * @return The concatenated sequence, of the same type as this sequence.
	 */
	public abstract <R extends ACell> ASequence<R> concat(ASequence<R> vals);

	@Override
	public final boolean addAll(int index, Collection<? extends T> c) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	/**
	 * Gets the sequence of all elements after the first, or null if no elements
	 * remain
	 * 
	 * @return Sequence following the first element
	 */
	public abstract ASequence<T> next();

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
		if (key instanceof CVMLong) {
			long ix = ((CVMLong) key).longValue();
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
		if (key instanceof CVMLong) {
			long ix = ((CVMLong) key).longValue();
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
	protected abstract Ref<T> getElementRef(long index);

	@Override
	public T set(int index, T element) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}
	
	@Override
	public ASequence<T> assoc(CVMLong key, T value) {
		return assoc(key.longValue(),value);
	}

	/**
	 * Updates a value at the given position in the sequence.
	 * 
	 * @param i     Index of element to update
	 * @param value New element value
	 * @return Updated sequence
	 */
	public abstract <R extends ACell> ASequence<R> assoc(long i, R value);

	/**
	 * Checks if an index range is valid for this sequence
	 * 
	 * @param start
	 * @param length
	 */
	protected void checkRange(long start, long length) {
		if (start < 0) throw Utils.sneakyThrow(new IndexOutOfBoundsException("Negative start: " + start));
		if (length < 0L) throw Utils.sneakyThrow(new IndexOutOfBoundsException("Negative length: " + length));
		if ((start + length) > count())
			throw Utils.sneakyThrow(new IndexOutOfBoundsException("End out of bounds: " + start + length));
	}

	@Override
	public final void add(int index, T element) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final T remove(int index) {
		throw new UnsupportedOperationException(Errors.immutable(this));
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
	 * @param value
	 * @return Updated sequence
	 */
	@Override
	public abstract <R extends ACell> ASequence<R> conj(R value);

	/**
	 * Produces a slice of this sequence, beginning with the specified start index and of the given length.
	 * The start and length must be contained within this sequence. Will return the same sequence if the
	 * start is zero and the length matches this sequence.
	 * 
	 * @param start Index of the start element
	 * @param length Length of slice to create.
	 * @return A sequence representing the requested slice.
	 */
	public abstract ASequence<T> slice(long start, long length);

	/**
	 * Prepends an element to this sequence, returning a list.
	 * @param x Any new element value
	 * @return A list starting with the new element.
	 */
	public abstract AList<T> cons(T x);

	/**
	 * Gets a vector containing the specified subset of this sequence.
	 * 
	 * @param start
	 * @param length
	 * @return Sub-vector of this sequence
	 */
	public abstract <R extends ACell> AVector<R> subVector(long start, long length);

	@Override
	public final java.util.List<T> subList(int fromIndex, int toIndex) {
		long start = fromIndex;
		long length = toIndex - fromIndex;
		return subVector(start, length);
	}

	/**
	 * Gets the ListIterator for a long position
	 * 
	 * @param l
	 * @return ListIterator instance.
	 */
	protected abstract ListIterator<T> listIterator(long l);
}
