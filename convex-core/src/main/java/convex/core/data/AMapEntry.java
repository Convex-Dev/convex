package convex.core.data;

import java.util.Iterator;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

public abstract class AMapEntry<K extends ACell, V extends ACell> extends ASpecialVector<ACell> implements Map.Entry<K, V> {

	public AMapEntry(long count) {
		super(2);
	}

	@Override
	public abstract ACell get(long i);

	@Override
	public final AVector<ACell> appendChunk(AVector<ACell> chunk) {
		throw new IllegalArgumentException("Can't append chunk to a MapEntry of size: 2");
	}

	@Override
	public final VectorLeaf<ACell> getChunk(long offset) {
		return toVector().getChunk(offset);
	}

	@Override
	public final boolean isFullyPacked() {
		return false;
	}

	@Override
	public abstract int getRefCount();

	@Override
	public abstract <R extends ACell> Ref<R> getRef(int i);

	@Override
	public abstract K getKey();

	@Override
	public abstract V getValue();

	@Override
	public final V setValue(V value) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public abstract boolean isCanonical();

	@Override
	public AVector<ACell> append(ACell value) {
		return (AVector<ACell>) toVector().append(value);
	}

	@Override
	public ListIterator<ACell> listIterator(long index) {
		return toVector().listIterator(index);
	}

	@Override
	public ListIterator<ACell> listIterator() {
		return toVector().listIterator();
	}

	@Override
	public Iterator<ACell> iterator() {
		return toVector().iterator();
	}

	@Override
	public long longIndexOf(ACell o) {
		if (Utils.equals(o,get(0))) return 0;
		if (Utils.equals(o,get(1))) return 1;
		return -1;
	}

	@Override
	public long longLastIndexOf(ACell o) {
		if (Utils.equals(o,get(1))) return 1;
		if (Utils.equals(o,get(0))) return 0;
		return -1;
	}
	
	@Override
	public AVector<ACell> slice(long start, long end) {
		if ((start<0)||(end>2)) return null;
		if (start>end) return null;
		if (start==end) return Vectors.empty();
		if ((start==0)&&(end==2)) return this;
		return Vectors.of(start==0?getKey():getValue());
	}

	@Override
	public long commonPrefixLength(AVector<ACell> b) {
		if (b == this) return 2;
		long bc = b.count();
		if (bc == 0) return 0;
		if (!Cells.equals(getKey(), b.get(0))) return 0;
		if (bc == 1) return 1;
		if (!Cells.equals(getValue(), b.get(1))) return 1;
		return 2;
	}

	/**
	 * Create a new MapEntry with an updated key. Shares old value. Returns the same
	 * MapEntry if unchanged
	 * 
	 * @param key Key to update
	 * @return
	 */
	protected abstract AMapEntry<K, V> withKey(K key);

	/**
	 * Create a new MapEntry with an updated value. Shares old key. Returns the same
	 * MapEntry if unchanged
	 * 
	 * @param value Value to update
	 * @return
	 */
	protected abstract AMapEntry<K, V> withValue(V value);

	@Override
	public AVector<ACell> next() {
		return Vectors.of(getValue());
	}

	@Override
	public abstract int encode(byte[] bs, int pos);

	@Override
	public boolean anyMatch(Predicate<? super ACell> pred) {
		return toVector().anyMatch(pred);
	}

	@Override
	public boolean allMatch(Predicate<? super ACell> pred) {
		return toVector().allMatch(pred);
	}

	@Override
	public void forEach(Consumer<? super ACell> action) {
		action.accept(getKey());
		action.accept(getValue());
	}
}
