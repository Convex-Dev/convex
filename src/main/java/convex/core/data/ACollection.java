package convex.core.data;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.function.Function;

import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Abstract base class for Persistent Merkle Collections
 * 
 * @param <T> Type of elements in this collection
 */
public abstract class ACollection<T extends ACell> extends ADataStructure<T> implements Collection<T> {

	@Override
	public abstract int encode(byte[] bs, int pos);

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
	public abstract <R extends ACell> AVector<R> toVector();

	
	/**
	 * Converts this collection to a new Cell array
	 * @return A new cell array containing the elements of this sequence
	 */
	public ACell[] toCellArray() {
		int n=Utils.checkedInt(count());
		ACell[] cells=new ACell[n];
		int i=0;
		for (ACell cell: this) {
			cells[i++]=cell;
		}
		return cells;
	}
	/**
	 * Adds an element to this collection, according to the natural semantics of the collection
	 * @param x Value to add
	 * @return The updated collection
	 */
	@Override
	public abstract <R extends ACell> ACollection<R> conj(R x);
	
	public abstract <R extends ACell> ACollection<R> map(Function<? super T, ? extends R> mapper);

	/**
	 * Gets the element at the specified index in this collection
	 * 
	 * @param index Index of element to get
	 * @return Element at the specified index
	 */
	public abstract T get(long i);
}
