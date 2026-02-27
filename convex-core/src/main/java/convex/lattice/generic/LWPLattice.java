package convex.lattice.generic;

import java.util.function.ToLongFunction;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Last-Write-Preferred lattice wrapper.
 *
 * <p>Wraps an inner lattice with timestamp-based preference. Before
 * delegating to the inner lattice's merge, reorders the arguments so
 * that the value with the higher timestamp becomes "own" (first argument).
 * The inner lattice then merges with its normal semantics, and the
 * prefer-own tiebreaker convention ensures the more recent write wins
 * any unresolved conflicts.</p>
 *
 * <p>This enables timestamped variants of any lattice. For example:</p>
 * <ul>
 *   <li>Wrapping a {@link MapLattice} gives per-key merge where the
 *       newer timestamp's entries win leaf conflicts, but both sides'
 *       unique keys are preserved.</li>
 *   <li>Wrapping a value lattice (prefer-own) gives pure LWW semantics.</li>
 * </ul>
 *
 * @param <V> Type of lattice values
 */
public class LWPLattice<V extends ACell> extends ALattice<V> {

	private final ALattice<V> inner;
	private final ToLongFunction<V> timestampFn;

	private LWPLattice(ALattice<V> inner, ToLongFunction<V> timestampFn) {
		this.inner = inner;
		this.timestampFn = timestampFn;
	}

	/**
	 * Creates a LWP lattice wrapping an inner lattice with a timestamp extractor.
	 *
	 * @param <V> Value type
	 * @param inner Inner lattice to delegate merge to
	 * @param timestampFn Function to extract a long timestamp from a value
	 * @return New LWPLattice instance
	 */
	public static <V extends ACell> LWPLattice<V> create(ALattice<V> inner, ToLongFunction<V> timestampFn) {
		return new LWPLattice<>(inner, timestampFn);
	}

	@Override
	public V merge(V own, V other) {
		if (own == null) return other;
		if (other == null) return own;

		long ownTS = timestampFn.applyAsLong(own);
		long otherTS = timestampFn.applyAsLong(other);

		if (otherTS > ownTS) {
			// Other is newer — make it "own" for the inner merge
			return inner.merge(other, own);
		}
		// Own is newer or equal — keep natural ordering
		return inner.merge(own, other);
	}

	@Override
	public V merge(LatticeContext context, V own, V other) {
		if (own == null) return other;
		if (other == null) return own;

		long ownTS = timestampFn.applyAsLong(own);
		long otherTS = timestampFn.applyAsLong(other);

		if (otherTS > ownTS) {
			return inner.merge(context, other, own);
		}
		return inner.merge(context, own, other);
	}

	@Override
	public V zero() {
		return inner.zero();
	}

	@Override
	public boolean checkForeign(V value) {
		return inner.checkForeign(value);
	}

	@Override
	public ACell resolveKey(ACell key) {
		return inner.resolveKey(key);
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell child) {
		return inner.path(child);
	}
}
