package convex.lattice;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/**
 * Generic ordered index operations over shared storage, for use as they are or held
 * by a domain component, with no implied replication rule.
 */
public class IndexComponent<K extends ABlobLike<?>,V extends ACell> extends AMapComponent<K,V,Index<K,V>> {

	public IndexComponent(AForkableCursor<Index<K,V>> cursor) { super(cursor); }

	@Override protected Index<K,V> createContainer() { return Index.none(); }

	/** Index keys must be blob-like; nil is not an index key. */
	@Override protected <T extends V> AForkableCursor<T> entry(K key) {
		if (key==null) throw new IllegalArgumentException("Index key must not be nil");
		return super.entry(key);
	}

	@Override protected Index<K,V> requireMap(ACell value) {
		if (value==null) return null;
		Index<K,V> index=RT.ensureIndex(value);
		if (index==null) throw new IllegalStateException("Index operation requires an index at this path");
		return index;
	}

	@Override public Index<K,V> get() { return super.get(); }
	@Override public V get(K key) { return super.get(key); }
	@Override public boolean containsKey(K key) { return super.containsKey(key); }
	@Override public long size() { return super.size(); }
	@Override public boolean isEmpty() { return super.isEmpty(); }
	@Override public V put(K key,V value) { return super.put(key,value); }
	@Override public V remove(K key) { return super.remove(key); }
	@Override public void clear() { super.clear(); }
}
