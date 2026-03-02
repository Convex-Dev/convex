package convex.lattice.generic;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.lattice.ALattice;

/**
 * Content-Addressed Storage (CAS) lattice for Index types.
 *
 * <p>A generic lattice for content-addressed data where keys are blob-like
 * (typically cryptographic hashes). Since the same key always refers to the
 * same immutable content, merge is simply a union of entries.
 *
 * <h2>Merge Semantics</h2>
 * <p>Union merge: all entries from both indexes are included. When the same
 * key exists in both, either value may be kept (same key = same content
 * for content-addressed data).
 *
 * @param <K> Key type extending ABlobLike (Hash, Blob, AString, etc.)
 * @param <V> Value type extending ACell
 */
public class CASLattice<K extends ABlobLike<?>, V extends ACell> extends ALattice<Index<K, V>> {

	@SuppressWarnings("rawtypes")
	private static final CASLattice INSTANCE = new CASLattice();

	private CASLattice() {
	}

	/**
	 * Get a CASLattice instance. Returns a shared singleton since CASLattice
	 * is stateless.
	 *
	 * @param <K> Key type extending ABlobLike
	 * @param <V> Value type extending ACell
	 * @return CASLattice instance
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ABlobLike<?>, V extends ACell> CASLattice<K, V> create() {
		return INSTANCE;
	}

	@Override
	public Index<K, V> merge(Index<K, V> ownValue, Index<K, V> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) return otherValue;
		if (ownValue.equals(otherValue)) return ownValue;

		return (Index<K, V>) ownValue.merge(otherValue);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<K, V> zero() {
		return (Index<K, V>) Index.EMPTY;
	}

	@Override
	public boolean checkForeign(Index<K, V> value) {
		return value instanceof Index;
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null; // Leaf lattice
	}
}
