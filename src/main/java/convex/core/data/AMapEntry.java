package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

import convex.core.exceptions.TODOException;
import convex.core.util.Errors;
import convex.core.util.Utils;

public abstract class AMapEntry<K, V> extends AVector<Object> implements List<Object>, Map.Entry<K, V> {

	@Override
	public abstract Object get(long i);

	@Override
	public final AVector<Object> appendChunk(VectorLeaf<Object> listVector) {
		throw new IllegalArgumentException("Can't append chunk to a MapEntry of size: 2");
	}

	@Override
	public final VectorLeaf<Object> getChunk(long offset) {
		throw new IllegalStateException("Can only get full chunk");
	}

	@Override
	public final boolean isPacked() {
		return false;
	}

	@Override
	public abstract int getRefCount();

	@Override
	public abstract <R> Ref<R> getRef(int i);

	@Override
	public abstract K getKey();

	@Override
	public abstract V getValue();

	@Override
	public final V setValue(V value) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public abstract boolean isCanonical();

	@Override
	public AVector<Object> append(Object value) {
		return toVector().append(value);
	}

	@Override
	public Spliterator<Object> spliterator(long position) {
		return toVector().spliterator(position);
	}

	@Override
	public ListIterator<Object> listIterator(long index) {
		return toVector().listIterator(index);
	}

	@Override
	public ListIterator<Object> listIterator() {
		return toVector().listIterator();
	}

	@Override
	public Iterator<Object> iterator() {
		return toVector().iterator();
	}

	@Override
	public long longIndexOf(Object o) {
		return toVector().longIndexOf(o);
	}

	@Override
	public long longLastIndexOf(Object o) {
		return toVector().longLastIndexOf(o);
	}

	@Override
	public long commonPrefixLength(AVector<Object> b) {
		if (b == this) return 2;
		long bc = b.count();
		if (bc == 0) return 0;
		if (!Utils.equals(getKey(), b.get(0))) return 0;
		if (bc == 1) return 1;
		if (!Utils.equals(getValue(), b.get(1))) return 1;
		return 2;
	}

	/**
	 * Create a new MapEntry with an updated key. Shares old value. Returns the same
	 * MapEntry if unchanged
	 * 
	 * @param value
	 * @return
	 */
	protected abstract AMapEntry<K, V> withKey(K key);

	/**
	 * Create a new MapEntry with an updated value. Shares old key. Returns the same
	 * MapEntry if unchanged
	 * 
	 * @param value
	 * @return
	 */
	protected abstract AMapEntry<K, V> withValue(V value);

	@Override
	public AVector<Object> next() {
		return Vectors.of(getValue());
	}

	@Override
	public abstract ByteBuffer write(ByteBuffer b);

	@Override
	public long count() {
		return 2;
	}

	@Override
	public final Object set(int index, Object element) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean anyMatch(Predicate<? super Object> pred) {
		return toVector().anyMatch(pred);
	}

	@Override
	public boolean allMatch(Predicate<? super Object> pred) {
		return toVector().allMatch(pred);
	}

	@Override
	public void forEach(Consumer<? super Object> action) {
		action.accept(getKey());
		action.accept(getValue());
	}

	@Override
	public Object[] toArray() {
		return new Object[] { getKey(), getValue() };
	}

	@Override
	public <T> T[] toArray(T[] a) {
		throw new TODOException();
	}

}
