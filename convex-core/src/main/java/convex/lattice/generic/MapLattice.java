package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Maps;
import convex.lattice.ALattice;

/**
 * A hash map whose entries all merge with one child lattice: a union of keys with the
 * child merge per entry.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
public class MapLattice<K extends ACell,V extends ACell> extends AUnionLattice<K,V,AHashMap<K,V>> {

	protected final ALattice<V> valueNode;

	public MapLattice(ALattice<V> valueNode) {
		this.valueNode=valueNode;
	}

	public static <K extends ACell,V extends ACell> MapLattice<K,V> create(ALattice<V> valueNode) {
		return new MapLattice<K,V>(valueNode);
	}

	@Override
	public AHashMap<K, V> zero() {
		return Maps.empty();
	}

	@Override
	public boolean checkForeign(AHashMap<K, V> value) {
		return (value instanceof AHashMap);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// Every key uses the one child lattice
		return (ALattice<T>) valueNode;
	}
}
