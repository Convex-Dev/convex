package convex.lattice.generic;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;

/**
 * A recursive lattice for arbitrary-depth JSON-like structures.
 *
 * <p>Children at any key are also {@code JSONValueLattice}, enabling
 * recursive per-key merge of nested maps. Non-map leaf values use a
 * deterministic hash-based tiebreaker (commutative and associative).</p>
 *
 * <p>Two singleton instances control the container type for {@link #zero()}:</p>
 * <ul>
 *   <li>{@link #INDEX_INSTANCE} — sorted keys via {@link Index}, suitable
 *       for keyword-keyed records</li>
 *   <li>{@link #MAP_INSTANCE} — unordered keys via {@link AHashMap}, suitable
 *       for dynamic string keys</li>
 * </ul>
 */
public class JSONValueLattice extends ALattice<ACell> {

	/** Instance that uses {@link Index} as the zero container (sorted keys). */
	@SuppressWarnings("unchecked")
	public static final JSONValueLattice INDEX_INSTANCE = new JSONValueLattice(true);

	/** Instance that uses {@link AHashMap} as the zero container (unordered keys). */
	public static final JSONValueLattice MAP_INSTANCE = new JSONValueLattice(false);

	private final boolean useIndex;

	private final MergeFunction<ACell> mergeFunction = (a, b) -> merge(a, b);

	private JSONValueLattice(boolean useIndex) {
		this.useIndex = useIndex;
	}

	@SuppressWarnings("unchecked")
	@Override
	public ACell merge(ACell own, ACell other) {
		if (own == null) return other;
		if (other == null) return own;

		// Both maps: recursive per-key merge
		if (own instanceof AMap ownMap && other instanceof AMap otherMap) {
			return ownMap.mergeDifferences(otherMap, mergeFunction);
		}

		// Leaf values: deterministic tiebreaker (hash comparison)
		int cmp = own.getHash().compareTo(other.getHash());
		return (cmp >= 0) ? own : other;
	}

	@SuppressWarnings("unchecked")
	@Override
	public ACell zero() {
		return useIndex ? (ACell) Index.EMPTY : (ACell) Maps.empty();
	}

	@Override
	public boolean checkForeign(ACell value) {
		return true; // JSON values are permissive
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return (ALattice<T>) this; // recursive — children are same lattice
	}
}
