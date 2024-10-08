package convex.core.data;

import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.exceptions.InvalidDataException;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

public abstract class AHashMap<K extends ACell, V extends ACell> extends AMap<K, V> {

	protected AHashMap(long count) {
		super(count);
	}

	@Override
	public AHashMap<K, V> empty() {
		return Maps.empty();
	}

	@Override
	public abstract AHashMap<K, V> assoc(ACell key, ACell value);
	
	public abstract AHashMap<K, V> assocRef(Ref<K> keyRef, V value);

	public abstract AHashMap<K, V> assocEntry(MapEntry<K, V> e);

	@Override
	public abstract AHashMap<K, V> dissoc(ACell key);
	
	/**
	 * Dissoc given a Hash for the key value.
	 * @param key Hash of key to remove
	 * @return Map with specified key removed.
	 */
	public abstract AHashMap<K, V> dissocHash(Hash key);

	/**
	 * Merge another map into this map. Replaces existing entries if they are
	 * different
	 * 
	 * O(n) in size of map to merge.
	 * 
	 * @param m HashMap to merge into this HashMap 
	 * @return Merged HashMap
	 */
	public AHashMap<K, V> merge(AHashMap<K, V> m) {
		AHashMap<K, V> result = this;
		long n = m.count();
		for (int i = 0; i < n; i++) {
			result = result.assocEntry(m.entryAt(i));
		}
		return result;
	}
	
	@Override
	public AHashMap<K, V> merge(AMap<K, V> m) {
		if (m instanceof AHashMap) return merge((AHashMap<K,V>)m);
		return (AHashMap<K, V>) super.merge(m);
	}

	/**
	 * Merge this map with another map, using the given function for each key that
	 * is present in either map and has a different value
	 * 
	 * The function is passed null for missing values in either map, and must return
	 * type V.
	 * 
	 * If the function returns null, the entry is removed.
	 * 
	 * Returns the same map if no changes occurred.
	 * 
	 * @param b    Other map to merge with
	 * @param func Merge function, returning a new value for each key
	 * @return A merged map, or this map if no changes occurred
	 */
	public abstract AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func);

	protected abstract AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func, int shift);

	/**
	 * Merge this map with another map, using the given function for each key that
	 * is present in either map. The function is applied to the corresponding values
	 * with the same key.
	 * 
	 * The function is passed null for missing values in either map, and must return
	 * type V.
	 * 
	 * If the function returns null, the entry is removed.
	 * 
	 * Returns the same map if no changes occurred.
	 * 
	 * PERF WARNING: This method's contract requires calling the function on all
	 * values in both sets, which will cause a full data structure traversal. If the
	 * function will only return one or other of the compared values consider using
	 * mergeDifferences instead.
	 * 
	 * @param b    Other map to merge with
	 * @param func Merge function, returning a new value for each key
	 * @return A merged map, or this map if no changes occurred
	 */
	public abstract AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func);

	protected abstract AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func, int shift);

	@Override
	public AHashMap<K, V> filterValues(Predicate<V> pred) {
		return mergeWith(this, (a, b) -> pred.test(a) ? a : null);
	}

	/**
	 * Maps a function over all entries in this Map to produce updated entries.
	 * 
	 * May not change keys, but may return null to remove an entry.
	 * 
	 * @param func A function that maps old map entries to updated map entries.
	 * @return The updated Map, or this Map if no changes
	 */
	public abstract AHashMap<K, V> mapEntries(Function<MapEntry<K, V>, MapEntry<K, V>> func);

	/**
	 * Validates the map checking the prefix of children is consistent for the given shift level
	 * 
	 * @throws InvalidDataException
	 */
	protected abstract void validateWithPrefix(Hash prefix, int shift) throws InvalidDataException;

	@Override
	public abstract AHashMap<K,V> updateRefs(IRefFunction func);

	/**
	 * Returns true if this map contains all the same keys as another map
	 * @param map Map to compare with
	 * @return True if this map contains all the keys of the other
	 */
	public abstract boolean containsAllKeys(AHashMap<K, V> map);

	@SuppressWarnings("unchecked")
	public final boolean containsKey(ACell key) {
		if (count==0) return false;
		return getEntry((K)key)!=null;
	}
	
	/**
	 * Writes this HashMap to a byte array. Will include values by default.
	 * @param bs Byte array to encode into
	 * @param pos Start position to encode at
	 * @return Updated position
	 */
	public abstract int encode(byte[] bs, int pos);
	
	/**
	 * Gets the keys in this Map as a Vector
	 * 
	 * @return Vector of keys in map defined order
	 */
	public AVector<K> getKeys() {
		int n=Utils.checkedInt(count);
		ACell[] keys=new ACell[n];
		for (int i=0; i<n; i++) {
			keys[i]=entryAt(i).getKey();
		}
		return Vectors.wrap(keys);
	}
	
	/**
	 * Gets the Hash for the first entry. Useful for prefix comparisons etc.
	 * @return
	 */
	protected abstract Hash getFirstHash();
	
	@Override
	public AHashMap<K,V> slice(long start) {
		return slice(start,count);
	}

	@Override
	public abstract AHashMap<K,V> slice(long start, long end);

	// TODO: better conversion to Sets
	// public abstract AHashSet<MapEntry<K,V>> buildEntrySet();
	// public abstract AHashSet<K> buildKeySet();

}
