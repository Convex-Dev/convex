package convex.core.data;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import convex.core.data.type.AType;
import convex.core.data.type.Types;

/**
 * Abstract base class for BlobMaps: a sorted radix-tree map of Blobs to Values.
 * 
 * Primary benefits: - Provide sorted orderings for indexes - Support Schedule
 * data structure
 * 
 * @param <K> Type of BlobMap keys
 * @param <V> Type of BlobMap values
 */
public abstract class ABlobMap<K extends ABlobLike<?>, V extends ACell> extends AMap<K, V> {
	protected ABlobMap(long count) {
		super(count);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final V get(ACell key) {
		if (!(key instanceof ABlobLike)) return null;
		return get((K) key);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean containsKey(ACell key) {
		if (!(key instanceof ABlobLike)) return false;
		return (getEntry((K) key) != null);
	}

	/**
	 * Gets the map entry for a given blob
	 * 
	 * @param key Key to lookup up
	 * @return The value specified by the given blob key or null if not present.
	 */
	public abstract V get(K key);

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
	
	@Override
	public AType getType() {
		return Types.BLOBMAP;
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
		if (key instanceof ABlobLike) {
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

	@SuppressWarnings("unchecked")
	public MapEntry<K, V> getEntry(ACell key) {
		if (key instanceof ABlobLike) return getEntry((K)key);
		return null;
	}
	
	public abstract MapEntry<K, V> getEntry(K key);

	@Override
	public abstract int estimatedEncodingSize();

}
