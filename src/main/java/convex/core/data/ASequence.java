package convex.core.data;

import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Abstract base class for persistent lists and vectors
 *
 * @param <T> Type of list elements
 */
public abstract class ASequence<T> extends ACollection<T> implements List<T>, IGet<T> {

	@Override
	public boolean contains(Object o) {
		return longIndexOf(o) >= 0;
	}

	public abstract long longIndexOf(Object o);

	public abstract long longLastIndexOf(Object o);

	public abstract <R> ASequence<R> map(Function<? super T, ? extends R> mapper);

	@Override
	public abstract void forEach(Consumer<? super T> action);

	/**
	 * Visits all elements in this sequence, callin the specified consumer for each.
	 * 
	 * @param f
	 */
	public abstract void visitElementRefs(Consumer<Ref<T>> f);

	@SuppressWarnings("unchecked")
	public <R> ASequence<R> flatMap(Function<? super T, ? extends ASequence<R>> mapper) {
		ASequence<ASequence<R>> vals = this.map(mapper);
		ASequence<R> result = (ASequence<R>) this.empty();
		for (ASequence<R> seq : vals) {
			result = result.concat(seq);
		}
		return result;
	}

	public abstract ASequence<T> concat(ASequence<T> vals);

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

	/**
	 * Gets the element at the specified index
	 * 
	 * @param index Index of element to get
	 * @return Element at the specified index
	 */
	public abstract T get(long index);

	/**
	 * Gets the element at the specified key
	 * 
	 * @param key Key of element to get
	 * @return The value at the specified index, or null if not valid
	 */
	@Override
	public T get(Object key) {
		if (key instanceof Long) {
			long ix = (long) key;
			if ((ix >= 0) && (ix < count())) return get(ix);
		}
		return null;
	}

	@Override
	public boolean containsKey(Object key) {
		if (key instanceof Long) {
			long ix = (long) key;
			if ((ix >= 0) && (ix < count())) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	@Override
	public T get(Object key, Object notFound) {
		if (key instanceof Long) {
			long ix = (long) key;
			if ((ix >= 0) && (ix < count())) return get(ix);
		}
		return (T) notFound;
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

	/**
	 * Updates a value at the given position in the sequence.
	 * 
	 * @param i     Index of element to update
	 * @param value New element value
	 * @return Updated sequence
	 */
	public abstract ASequence<T> assoc(long i, T value);

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
	 * Adds an element to the sequence in the natural position
	 * 
	 * @param value
	 * @return Updated sequence
	 */
	@Override
	public abstract <R> ASequence<R> conj(R value);

	public abstract ASequence<T> slice(long start, long length);

	public abstract AList<T> cons(T x);

	/**
	 * Gets a vector containing the specified subset of this sequence.
	 * 
	 * @param start
	 * @param length
	 * @return Sub-vector of this sequence
	 */
	public abstract AVector<T> subVector(long start, long length);

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
