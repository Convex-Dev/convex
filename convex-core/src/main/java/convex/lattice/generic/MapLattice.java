package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Maps;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;

/**
 * A lattice representing a hash map that merges values
 * @param <K> Key type
 * @param <V> Value type
 */
public class MapLattice<K extends ACell,V extends ACell> extends ALattice<AHashMap<K,V>> {

	protected final ALattice<V> valueNode;
	
	protected final MergeFunction<V> mergeFunction;

	public MapLattice(ALattice<V> valueNode) {
		this.valueNode=valueNode;
		this.mergeFunction=(a,b)->{
			return valueNode.merge(a, b);
		};
	}

	public static <K extends ACell,V extends ACell> MapLattice<K,V> create(ALattice<V> valueNode) {
		
		return new MapLattice<K,V>(valueNode);
	}

	@Override
	public AHashMap<K,V> merge(AHashMap<K, V> ownValue, AHashMap<K, V> otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return ownValue.mergeDifferences(otherValue, mergeFunction);
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
		// Any value should use child lattice
		return (ALattice<T>) valueNode;
	}
	

}
