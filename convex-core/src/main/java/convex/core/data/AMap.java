package convex.core.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * Abstract base class for maps.
 * 
 * Maps are Smart Data Structures that represent an immutable mapping of keys to
 * values. The can also be seen as a data structure where the elements are map entries
 * (equivalent to length 2 vectors)
 * 
 * Ordering of map entries (as seen through iterators etc.) depends on map type.
 * 
 * @param <K> Type of keys
 * @param <V> Type of values
 */
public abstract class AMap<K extends ACell, V extends ACell> extends ADataStructure<MapEntry<K, V>>
		implements Map<K, V>, IAssociative<K,V> {

	protected AMap(long count) {
		super(count);
	}
	
	@Override
	public AType getType() {
		return Types.MAP;
	}

	/**
	 * Associates the given key with the specified value.
	 * 
	 * @param key Map key to associate
	 * @param value Map value
	 * @return An updated map with the new association, or null if the association fails
	 */
	public abstract AMap<K,V> assoc(ACell key, ACell value);


	/**
	 * Dissociates a key from this map, returning an updated map if the key was
	 * removed, or the same unchanged map if the key is not present.
	 * 
	 * @param key Key to remove.
	 * @return Updated map
	 */
	public abstract AMap<K, V> dissoc(ACell key);

	public final boolean containsKeyRef(Ref<ACell> ref) {
		return getKeyRefEntry(ref) != null;
	}
	
	@SuppressWarnings("unchecked")
	public boolean containsKey(ACell key) {
		return getEntry((K)key)!=null;
	}

	@Override
	public final boolean containsKey(Object key) {
		if ((key==null)||(key instanceof ACell)) {
			return containsKey((ACell)key);
		}
		// If not a valid CVM value, cannot contain key
		return false;
	}
	
	@Override
	public final boolean containsValue(Object value) {
		if (value instanceof ACell) return containsValue((ACell)value);
		return false;
	}

	/**
	 * CHecks if this map contains the given value. WARNING: probably O(n)
	 * @param value Value to check
	 * @return true if map contains value, false otherwise
	 */
	public abstract boolean containsValue(ACell value);

	/**
	 * Get an entry given a Ref to the key value. This is more efficient than
	 * directly looking up using the key for some map types, and should be preferred
	 * if the caller already has a Ref available.
	 * 
	 * @param ref Ref to Map key
	 * @return MapEntry for the given key ref
	 */
	public abstract MapEntry<K, V> getKeyRefEntry(Ref<ACell> ref);

	/**
	 * Accumulate all entries from this map in the given collection
	 * 
	 * @param h HashSet in which to accumulate entries
	 */
	protected abstract void accumulateEntries(Collection<Entry<K, V>> h);

	/**
	 * Accumulate all keys from this map in the given mutable Set.
	 * 
	 * @param h HashSet in which to accumulate keys
	 */
	protected abstract void accumulateKeySet(Set<K> h);

	/**
	 * Accumulate all values from this map in the given mutable list.
	 * 
	 * @param al ArrayList in which to accumulate values
	 */
	protected abstract void accumulateValues(java.util.List<V> al);

	@Override
	public final V put(K key, V value) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final V remove(Object key) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final void putAll(Map<? extends K, ? extends V> m) {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public final void clear() {
		throw new UnsupportedOperationException(ErrorMessages.immutable(this));
	}

	@Override
	public abstract void forEach(BiConsumer<? super K, ? super V> action);

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append('{');
		long n=count();
		for (long i=0; i<n; i++) {
			MapEntry<K,V> e=entryAt(i);
			K k=e.getKey();
			if (!RT.print(sb,k,limit)) return false;
			sb.append(' ');
			V v=e.getValue();
			if (!RT.print(sb,v,limit)) return false;
			if (i<(n-1)) sb.append(',');
		}
		sb.append('}');
		return sb.check(limit);
	}

	/**
	 * Associate the given map entry into the map. May return null if the map entry is not valid for this map type.
	 * 
	 * @param e A map entry
	 * @return The updated map
	 */
	public abstract AMap<K, V> assocEntry(MapEntry<K, V> e);

	/**
	 * Gets the entry in this map at a specified index, according to the
	 * map-specific order. Caller responsible for bounds check!
	 * 
	 * @param i Index of entry
	 * @return MapEntry at the specified index.
	 */
	public abstract MapEntry<K, V> entryAt(long i);
	
	@Override
	public Ref<MapEntry<K, V>> getElementRef(long index) {
		checkIndex(index);
		return entryAt(index).getRef();
	}
	
	/**
	 * Gets the map entry at the specified position in this Map
	 * 
	 * @param i Index of entry to get
	 * @return map entry
	 */
	@Override
	public final MapEntry<K, V> get(long i) {
		checkIndex(i);
		return entryAt(i);
	}

	/**
	 * Gets the MapEntry for the given key
	 * 
	 * @param k Key to lookup in Map
	 * @return The map entry, or null if the key is not found
	 */
	public abstract MapEntry<K, V> getEntry(ACell k);
	
	@Override
	public final V get(Object key) {
		if ((key==null)||(key instanceof ACell)) return (V) get((ACell)key);
		return null;
	}
	
	public abstract V get(ACell key); 

	/**
	 * Gets the value at a specified key, or returns the fallback value if not found
	 * 
	 * @param key Key to lookup in Map
	 * @param notFound Fallback value to return if key is not present
	 * @return Value for the specified key, or the notFound value.
	 */
	@SuppressWarnings("unchecked")
	public V get(ACell key, ACell notFound) {
		MapEntry<K, V> me = getEntry((K) key);
		if (me == null) {
			return (V) notFound;
		} else {
			return me.getValue();
		}
	}

	/**
	 * Reduce over all values in this map
	 * 
	 * @param <R>     Type of reduction return value
	 * @param func    A function taking the reduction value and a map value
	 * @param initial Initial reduction value
	 * @return The final reduction value
	 */
	public abstract <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial);

	
	/**
	 * Filters all values in this map with the given predicate.
	 * 
	 * @param pred A predicate specifying which elements to retain.
	 * @return The updated map containing those entries where the predicate returned
	 *         true.
	 */
	public AMap<K, V> filterValues(Predicate<V> pred) {
		throw new TODOException();
	}

	/**
	 * Reduce over all map entries in this map
	 * 
	 * @param <R>     Type of reduction return value
	 * @param func    A function taking the reduction value and a map entry
	 * @param initial Initial reduction value
	 * @return The final reduction value
	 */
	public abstract <R> R reduceEntries(BiFunction<? super R, MapEntry<K, V>, ? extends R> func, R initial);

	@SuppressWarnings("unchecked")
	@Override
	public Set<K> keySet() {
		ASet<K> ks=reduceEntries((s,me)->s.conj(me.getKey()), (ASet<K>)(Sets.empty()));
		return ks;
	}
	
	@Override
	public Set<Entry<K, V>> entrySet() {
		int len = size();
		HashSet<Map.Entry<K, V>> h = new HashSet<Map.Entry<K, V>>(len);
		accumulateEntries(h);
		return h;
	}
	
	/**
	 * Gets the map entry with the specified hash
	 * 
	 * @param hash Hash of key to lookup
	 * @return The specified MapEntry, or null if not found.
	 */
	protected abstract MapEntry<K, V> getEntryByHash(Hash hash);

	/**
	 * Adds a new map entry to this map. The argument must be a valid map entry or
	 * length 2 vector.
	 * 
	 * @param x An object that can be cast to a MapEntry
	 * @return Updated map with the specified entry added, or null if the argument
	 *         is not a valid map entry
	 */
	public AMap<K,V> conj(ACell x) {
		MapEntry<K, V> me = RT.ensureMapEntry(x);
		if (me == null) return null;
		return assocEntry(me);
	}

	/**
	 * Gets a vector of all map entries.
	 * 
	 * @return Vector map entries, in map-defined order.
	 */
	public AVector<MapEntry<K, V>> entryVector() {
		return reduceEntries((acc, e) -> acc.conj(e), Vectors.empty());
	}
	
	/**
	 * Merge another map into this map. Replaces existing entries if they are
	 * different.
	 * 
	 * O(n) in size of map to merge. Preserves the type of the current map.
	 * 
	 * @param m A map to merge into this map
	 * @return The updated map, or null if convesrion fails
	 */
	public AMap<K, V> merge(AMap<K, V> m) {
		AMap<K, V> result = this;
		long n = m.count();
		for (int i = 0; i < n; i++) {
			result = result.assocEntry(m.entryAt(i));
			if (result==null) return null;
		}
		return result;
	}

	@Override
	public AMap<K,V> slice(long start) {
		return slice(start,count);
	}
	
	@Override
	public abstract AMap<K,V> empty();

	@Override
	public AMap<K,V> slice(long start, long end) {
		AMap<K,V> r=empty();
 		for (long i=start; i<end; i++) {
			r=(AMap<K, V>) r.conj(get(i));
			if (r==null) return null;
		}
 		return r;
	}

	/**
	 * Gets a vector of keys for this Map. 
	 * O(n) in general.
	 * 
	 * @return Vector of Keys
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
	 * Gets the values from this map, in map-determined order
	 */
	@Override
	public AVector<V> values() {
		int len = size();
		ArrayList<V> al = new ArrayList<V>(len);
		accumulateValues(al);
		return Vectors.create(al);
	}
	

}
