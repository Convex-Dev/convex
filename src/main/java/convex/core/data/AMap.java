package convex.core.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.util.Errors;
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
		implements Map<K, V> {

	protected AMap(long count) {
		super(count);
	}
	
	@Override
	public AType getType() {
		return Types.MAP;
	}

	@Override
	public boolean isEmpty() {
		return count == 0L;
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
	
	// TODO: Review plausible alternative implementation for values()
	//
	//	@Override
	//	public AVector<V> values() {
	//		return reduceValues((v,e)->((AVector<V>)v).append(e), Vectors.empty());
	//	}
	
	/**
	 * Associates the given key with the specified value.
	 * 
	 * @param key
	 * @param value
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
		return false;
	}

	/**
	 * Get an entry given a Ref to the key value. This is more efficient than
	 * directly looking up using the key for some map types, and should be preferred
	 * if the caller already has a Ref available.
	 * 
	 * @param ref
	 * @return MapEntry for the given key ref
	 */
	public abstract MapEntry<K, V> getKeyRefEntry(Ref<ACell> ref);

	/**
	 * Accumulate all entries from this map in the given HashSet.
	 * 
	 * @param h
	 */
	protected abstract void accumulateEntrySet(HashSet<Entry<K, V>> h);

	/**
	 * Accumulate all keys from this map in the given HashSet.
	 * 
	 * @param h
	 */
	protected abstract void accumulateKeySet(HashSet<K> h);

	/**
	 * Accumulate all values from this map in the given ArrayList.
	 * 
	 * @param h
	 */
	protected abstract void accumulateValues(ArrayList<V> al);

	@Override
	public final V put(K key, V value) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final V remove(Object key) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final void putAll(Map<? extends K, ? extends V> m) {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public final void clear() {
		throw new UnsupportedOperationException(Errors.immutable(this));
	}

	@Override
	public abstract void forEach(BiConsumer<? super K, ? super V> action);

	@Override
	public void print(StringBuilder sb) {
		sb.append('{');
		this.forEach((k, v) -> {
			Utils.print(sb,k);
			sb.append(' ');
			Utils.print(sb,v);
			sb.append(',');
		});
		if (count() > 0) sb.setLength(sb.length() - 1); // delete trailing comma
		sb.append('}');
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
	 * map-specific order.
	 * 
	 * @param i
	 * @return MapEntry at the specified index.
	 * @throws IndexOutOfBoundsException If this index is not valid
	 */
	public abstract MapEntry<K, V> entryAt(long i);
	
	@Override
	public Ref<MapEntry<K, V>> getElementRef(long index) {
		return entryAt(index).getRef();
	}
	
	@Override
	public final MapEntry<K, V> get(long i) {
		return entryAt(i);
	}

	/**
	 * Gets the MapEntry for the given key
	 * 
	 * @param k
	 * @return The map entry, or null if the key is not found
	 */
	public abstract MapEntry<K, V> getEntry(ACell k);
	
	@Override
	public V get(Object key) {
		if (key instanceof ACell) return (V) get((ACell)key);
		return null;
	}
	
	public abstract V get(ACell key); 

	/**
	 * Gets the value at a specified key, or returns the fallback value if not found
	 * 
	 * @param key
	 * @param notFound Fallback value to return if key is not present
	 * @return Value for the specified key, or the notFound value.
	 */
	@SuppressWarnings("unchecked")
	public final V get(ACell key, ACell notFound) {
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

	
	/**
	 * Returns true if this map has exactly the same keys as the other map
	 * 
	 * @param map
	 * @return true if maps have the same keys, false otherwise
	 */
	public abstract boolean equalsKeys(AMap<K, V> map);

	@SuppressWarnings("unchecked")
	@Override
	public final boolean equals(ACell a) {
		if (!(a instanceof AMap)) return false;
		return equals((AMap<K, V>) a);
	}

	/**
	 * Checks this map for equality with another map. In general, maps should be
	 * considered equal if they have the same canonical representation, i.e. the
	 * same hash value.
	 * 
	 * Subclasses may override this this they have a more efficient equals
	 * implementation or a more specific definition of equality.
	 * 
	 * @param a
	 * @return true if maps are equal, false otherwise.
	 */
	public boolean equals(AMap<K, V> a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		if (!(a.getClass() == this.getClass())) return false;
		if (this.count() != a.count()) return false;
		return getHash().equals(a.getHash());
	}

	/**
	 * Gets the map entry with the specified hash
	 * 
	 * @param hash
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
	@SuppressWarnings("unchecked")
	public <R extends ACell> ADataStructure<R> conj(R x) {
		MapEntry<K, V> me = RT.ensureMapEntry(x);
		if (me == null) return null;
		return (ADataStructure<R>) assocEntry(me);
	}

	/**
	 * Gets a vector of all map entries.
	 * 
	 * @return Vector map entries, in map-defined order.
	 */
	public AVector<MapEntry<K, V>> entryVector() {
		return reduceEntries((acc, e) -> acc.conj(e), Vectors.empty());
	}
}
