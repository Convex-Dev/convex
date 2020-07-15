package convex.core.data;

import java.util.Set;

import convex.core.exceptions.TODOException;

/**
 * Abstract base class for a sorted radix-tree map of Blobs to values.
 * 
 * Primary benefits: - Provide sorted orderings for indexes - Support Schedule
 * data structure
 *
 * @param <V>
 */
public abstract class ABlobMap<K extends ABlob, V> extends AMap<K, V> {
	protected ABlobMap(long count) {
		super(count);
	}

	@Override
	public final V get(Object key) {
		if (!(key instanceof ABlob)) return null;
		return get((ABlob) key);
	}

	@Override
	public boolean containsKey(Object key) {
		if (!(key instanceof ABlob)) return false;
		return (getEntry((ABlob) key) != null);
	}

	/**
	 * Gets the map entry for a given blob
	 * 
	 * @param key
	 * @return The value specified by the given blob key or null if not present.
	 */
	public abstract V get(ABlob key);

	@Override
	public Set<K> keySet() {
		throw new TODOException();
	}

	@Override
	public AVector<V> values() {
		throw new TODOException();
	}

	@Override
	public boolean equalsKeys(AMap<K, V> map) {
		// TODO: probably not needed? Only need this for set implementations
		throw new TODOException();
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		throw new TODOException();
	}

	@Override
	public abstract int getRefCount();

	@Override
	public abstract <R> Ref<R> getRef(int i);

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public abstract ABlobMap<K, V> assoc(K key, V value);

	@Override
	public abstract ABlobMap<K, V> dissoc(K key);

	@Override
	public MapEntry<K, V> getKeyRefEntry(Ref<K> ref) {
		return getEntry(ref.getValue());
	}

	@Override
	public abstract MapEntry<K, V> entryAt(long i);

	@Override
	public abstract MapEntry<K, V> getEntry(ABlob key);

	@Override
	public abstract int estimatedEncodingSize();

}
