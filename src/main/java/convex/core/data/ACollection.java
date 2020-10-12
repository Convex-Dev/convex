package convex.core.data;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.function.Function;

import convex.core.util.Errors;

/**
 * Abstract base class for Persistent Merkle Collections
 * 
 * @param <T> Type of elements in this collection
 */
public abstract class ACollection<T> extends ADataStructure<T> implements Collection<T> {

	@Override
	public abstract int write(byte[] bs, int pos);

	@Override
	public abstract boolean contains(Object o);

	@Override
	public abstract Iterator<T> iterator();

	@Override
	public final boolean add(T e) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final boolean remove(Object o) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		HashSet<T> h=new HashSet<T>(this.size());
		h.addAll(this);
		for (Object o: c) {
			if (!h.contains(o)) return false;
		}
		return true;
	}

	@Override
	public final boolean addAll(Collection<? extends T> c) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final boolean removeAll(Collection<?> c) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final boolean retainAll(Collection<?> c) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final void clear() {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	/**
	 * Converts this collection to a canonical vector of elements
	 * @return This collection coerced to a vector
	 */
	public abstract AVector<T> toVector();

	/**
	 * Adds an element to this collection, according to the natural semantics of the collection
	 * @param x Value to add
	 * @return The updated collection
	 */
	@Override
	public abstract <R> ACollection<R> conj(R x);
	
	public abstract <R> ACollection<R> map(Function<? super T, ? extends R> mapper);
}
