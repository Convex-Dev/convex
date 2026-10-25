package convex.lattice.generic;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.lattice.ALattice;

/**
 * An Index (sorted radix-tree map) whose entries all merge with one child lattice: a
 * union of keys with the child merge per entry.
 *
 * Analogous to {@link MapLattice} but for {@link Index}, providing lexicographic
 * key ordering.
 *
 * @param <K> Key type (must extend ABlobLike)
 * @param <V> Value type
 */
public class IndexLattice<K extends ABlobLike<?>, V extends ACell> extends AUnionLattice<K,V,Index<K,V>> {

	protected final ALattice<V> valueNode;

	public IndexLattice(ALattice<V> valueNode) {
		this.valueNode=valueNode;
	}

	public static <K extends ABlobLike<?>, V extends ACell> IndexLattice<K,V> create(ALattice<V> valueNode) {
		return new IndexLattice<K,V>(valueNode);
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
		// Every key uses the one child lattice
		return (ALattice<T>) valueNode;
	}
}
