package convex.lattice.generic;

import java.util.function.ToLongFunction;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Last-Write-Preferred lattice wrapper — a <b>merge</b> layer.
 *
 * <p>Before delegating to the inner lattice's merge, reorders the arguments so
 * that the value with the higher timestamp becomes "own" (first argument). The
 * inner lattice then merges with its normal semantics, and the prefer-own
 * tiebreaker convention ensures the more recent write wins any unresolved
 * conflicts.</p>
 *
 * <p>This enables timestamped variants of any lattice. For example:</p>
 * <ul>
 *   <li>Wrapping a {@link MapLattice} gives per-key merge where the newer
 *       timestamp's entries win leaf conflicts, but both sides' unique keys are
 *       preserved.</li>
 *   <li>Wrapping a value lattice (prefer-own) gives pure LWW semantics.</li>
 * </ul>
 *
 * <p>As an {@link ADelegatingLattice} it owns only the merge concern (delegating
 * navigation and write-interception to the inner lattice). It is the sibling of
 * {@link LWWLattice}: LWP <em>delegates</em> merge to the inner, LWW merges
 * whole-value — the only axis that differs is whether merge recurses.</p>
 *
 * @param <V> Type of lattice values
 */
public class LWPLattice<V extends ACell> extends ADelegatingLattice<V> {

	private final ToLongFunction<V> timestampFn;

	private LWPLattice(ALattice<V> inner, ToLongFunction<V> timestampFn) {
		super(inner);
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
		if (other == null) return own;
		// Validate/sanitise the foreign operand while it is still in the child's
		// foreign position. Timestamp preference may reorder it below, and must not
		// accidentally turn an unvalidated foreign value into the child's own value.
		V validated=inner.merge(inner.zero(),other);
		if (!convex.core.util.Utils.equals(validated,other)) other=validated;
		if (own == null) return other;

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
		if (other == null) return own;
		V validated=inner.merge(context,inner.zero(),other);
		if (!convex.core.util.Utils.equals(validated,other)) other=validated;
		if (own == null) return other;

		long ownTS = timestampFn.applyAsLong(own);
		long otherTS = timestampFn.applyAsLong(other);

		if (otherTS > ownTS) {
			return inner.merge(context, other, own);
		}
		return inner.merge(context, own, other);
	}
}
