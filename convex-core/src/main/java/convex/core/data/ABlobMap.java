package convex.core.data;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import convex.core.exceptions.TODOException;

/**
 * Abstract base class for a sorted radix-tree map of Blobs to values.
 * 
 * Primary benefits: - Provide sorted orderings for indexes - Support Schedule
 * data structure
 * 
 * @param <K> Type of BlobMap keys
 * @param <V> Type of BlobMap values
 */
public abstract class ABlobMap<K extends ABlob, V extends ACell> extends AMap<K, V> {
	protected ABlobMap(long count) {
		super(count);
	}

	@Override
	public final V get(ACell key) {
		if (!(key instanceof ABlob)) return null;
		return get((ABlob) key);
	}

	@Override
	public boolean containsKey(ACell key) {
		if (!(key instanceof ABlob)) return false;
		return (getEntry((ABlob) key) != null);
	}

	/**
	 * Gets the map entry for a given blob
	 * 
	 * @param key Key to lookup up
	 * @return The value specified by the given blob key or null if not present.
	 */
	public abstract V get(ABlob key);

	@Override
	public boolean equalsKeys(AMap<K, V> map) {
		// TODO: probably not needed? Only need this for set implementations
		throw new TODOException();
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		HashSet<Entry<K,V>> hs=new HashSet<>(size());
		long n=count();
		for (long i=0; i<n; i++) {
			MapEntry<K,V> me=entryAt(i);
			hs.add(me);
		}
		return Collections.unmodifiableSet(hs);
	}

	@Override
	public abstract int getRefCount();

	@Override
	public abstract <R extends ACell> Ref<R> getRef(int i);

	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Associates a blob key with a value in this data structure.
	 * 
	 * Returns null if the key is not a valid BlobMap key
	 */
	@Override
	public abstract ABlobMap<K, V> assoc(ACell key, ACell value);

	@SuppressWarnings("unchecked")
	@Override
	public final ABlobMap<K, V> dissoc(ACell key) {
		if (key instanceof ABlob) {
			return dissoc((K)key);
		}
		return this;
	}
	
	public abstract ABlobMap<K, V> dissoc(K key);

	@Override
	public MapEntry<K, V> getKeyRefEntry(Ref<ACell> ref) {
		return getEntry(ref.getValue());
	}

	@Override
	public abstract MapEntry<K, V> entryAt(long i);

	public MapEntry<K, V> getEntry(ACell key) {
		if (key instanceof ABlob) return getEntry((ABlob)key);
		return null;
	}
	
	public abstract MapEntry<K, V> getEntry(ABlob key);

	@Override
	public abstract int estimatedEncodingSize();

}
