package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/** Hash-map operations over shared storage. Merge and publication policy belong to the domain. */
public class MapComponent<K extends ACell,V extends ACell> extends AMapComponent<K,V,AHashMap<K,V>> {

	private static final AString EXPECTED_MAP=Strings.intern("Expected a hash map");

	public MapComponent(AForkableCursor<AHashMap<K,V>> cursor) { super(cursor); }

	@Override protected AHashMap<K,V> createContainer() { return Maps.empty(); }

	@Override protected AHashMap<K,V> requireMap(ACell value) {
		if (value==null) return null;
		AHashMap<K,V> map=RT.ensureHashMap(value);
		if (map==null) throw new IllegalStateException("Map operation requires a hash map at this path");
		return map;
	}

	@Override public AString checkValue(ACell value) {
		return value==null || value instanceof AHashMap<?,?>?null:EXPECTED_MAP;
	}
}
