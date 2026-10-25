package convex.lattice;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/** Ordered index operations over shared storage, with no implied replication rule. */
public class IndexComponent<K extends ABlobLike<?>,V extends ACell> extends AMapComponent<K,V,Index<K,V>> {

	public IndexComponent(AForkableCursor<Index<K,V>> cursor) { super(cursor); }

	@Override protected Index<K,V> createContainer() { return Index.none(); }

	/** Index keys must be blob-like; nil is not an index key. */
	@Override public <T extends V> AForkableCursor<T> path(K key) {
		if (key==null) throw new IllegalArgumentException("Index key must not be nil");
		return super.path(key);
	}

	@Override protected Index<K,V> requireMap(ACell value) {
		if (value==null) return null;
		Index<K,V> index=RT.ensureIndex(value);
		if (index==null) throw new IllegalStateException("Index operation requires an index at this path");
		return index;
	}
}
