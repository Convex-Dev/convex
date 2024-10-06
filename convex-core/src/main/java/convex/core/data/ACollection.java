package convex.core.data;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

import convex.core.data.type.AType;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * Abstract base class for Persistent Merkle Collections
 * 
 * <p>
 * A Collection is a data structure that contains zero or more elements. Possible collection subtypes include:
 * </p>
 * <ul>
 * <li>Sequential collections (Lists, Vectors) - see {@link ASequence}
 * <li>Sets (with unique elements) - see {@link ASet}</li> 
 * </ul>
 * 
 * @param <T> Type of elements in this collection
 */
public abstract class ACollection<T extends ACell> extends ADataStructure<T> implements Collection<T> {

	protected ACollection(long count) {
		super(count);
	}
	
	@Override
	public abstract AType getType();
	
	@Override
	public abstract int encode(byte[] bs, int pos);

	@Override
	public abstract boolean contains(Object o);

	@Override
	public Iterator<T> iterator() {
		return new BasicIterator(0);
	}

	/**
	 * Custom ListIterator for ListVector
	 */
	private class BasicIterator implements ListIterator<T> {
		long pos;

		public BasicIterator(long index) {
			if (index < 0L) throw new IndexOutOfBoundsException((int)index);

			long c = count();
			if (index > c) throw new IndexOutOfBoundsException((int)index);
			pos = index;
		}

		@Override
		public boolean hasNext() {
			return pos < count();
		}

		@Override
		public T next() {
			return get(pos++);
		}

		@Override
		public boolean hasPrevious() {
			if (pos > 0) return true;
			return false;
		}

		@Override
		public T previous() {
			if (pos > 0) return get(--pos);
			throw new NoSuchElementException();
		}

		@Override
		public int nextIndex() {
			return Utils.checkedInt(pos);
		}

		@Override
		public int previousIndex() {
			return Utils.checkedInt(pos - 1);
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
	
	@Override
	public final boolean add(T e) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final boolean remove(Object o) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
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
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final boolean removeAll(Collection<?> c) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final boolean retainAll(Collection<?> c) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final void clear() {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	/**
	 * Converts this collection to a canonical vector of elements
	 * @return This collection coerced to a vector
	 */
	public abstract AVector<T> toVector();

	/**
	 * Copies the elements of this collection in order to an array at the specified offset
	 * 
	 * @param <R>    Type of array elements required
	 * @param arr
	 * @param offset
	 */
	protected abstract <R> void copyToArray(R[] arr, int offset);	
	
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
	
	@SuppressWarnings("unchecked")
	@Override
	public <V> V[] toArray(V[] a) {
		int s = size();
		if (s > a.length) {
			Class<V> c = (Class<V>) a.getClass().getComponentType();
			a = (V[]) Array.newInstance(c, s);
		}
		copyToArray(a, 0);
		if (s < a.length) a[s] = null;
		return a;
	}
	
	@Override
	public Object[] toArray() {
		int n=size();
		Object[] os=new Object[n];
		for (int i=0; i<n; i++) {
			os[i]=this.get(i);
		}
		return os;
	}
	
	/**
	 * Adds an element to this collection, according to the natural semantics of the collection
	 * @param x Value to add, should be the element type of the data structure
	 * @return The updated collection
	 */
	@Override
	public abstract ACollection<T> conj(ACell x);
	
	/**
	 * Maps a function over a collection, applying it to each element in turn.
	 * 
	 * @param <R> Type of element in resulting collection
	 * @param mapper Function to map over collection
	 * @return Collection after function applied to each element
	 */
	public abstract <R extends ACell> ACollection<R> map(Function<? super T, ? extends R> mapper);


}
