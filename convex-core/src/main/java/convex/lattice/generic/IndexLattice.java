package convex.lattice.generic;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;

/**
 * A lattice representing an Index (sorted radix-tree map) that merges values
 * using a child value lattice.
 *
 * Analogous to {@link MapLattice} but for {@link Index}, providing lexicographic
 * key ordering.
 *
 * @param <K> Key type (must extend ABlobLike)
 * @param <V> Value type
 */
public class IndexLattice<K extends ABlobLike<?>, V extends ACell> extends ALattice<Index<K,V>> {

	protected final ALattice<V> valueNode;

	protected final MergeFunction<V> mergeFunction;

	public IndexLattice(ALattice<V> valueNode) {
		this.valueNode=valueNode;
		this.mergeFunction=(a,b)->{
			return valueNode.merge(a, b);
		};
	}

	public static <K extends ABlobLike<?>, V extends ACell> IndexLattice<K,V> create(ALattice<V> valueNode) {
		return new IndexLattice<K,V>(valueNode);
	}

	@Override
	public Index<K,V> merge(Index<K, V> ownValue, Index<K, V> otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return ownValue.mergeDifferences(otherValue, mergeFunction);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<K, V> zero() {
		return (Index<K,V>) Index.EMPTY;
	}

	@Override
	public boolean checkForeign(Index<K, V> value) {
		return (value instanceof Index);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return (ALattice<T>) valueNode;
	}

}
