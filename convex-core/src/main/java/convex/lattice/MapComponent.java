package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Maps;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/**
 * Generic hash-map operations over shared storage, for use as they are or held
 * by a domain component. Merge and publication policy belong to the domain.
 */
public class MapComponent<K extends ACell,V extends ACell> extends AMapComponent<K,V,AHashMap<K,V>> {

	public MapComponent(AForkableCursor<AHashMap<K,V>> cursor) { super(cursor); }

	@Override protected AHashMap<K,V> createContainer() { return Maps.empty(); }

	@Override protected AHashMap<K,V> requireMap(ACell value) {
		if (value==null) return null;
		AHashMap<K,V> map=RT.ensureHashMap(value);
		if (map==null) throw new IllegalStateException("Map operation requires a hash map at this path");
		return map;
	}

	@Override public AHashMap<K,V> get() { return super.get(); }
	@Override public V get(K key) { return super.get(key); }
	@Override public boolean containsKey(K key) { return super.containsKey(key); }
	@Override public long size() { return super.size(); }
	@Override public boolean isEmpty() { return super.isEmpty(); }
	@Override public V put(K key,V value) { return super.put(key,value); }
	@Override public V remove(K key) { return super.remove(key); }
	@Override public void clear() { super.clear(); }
}
