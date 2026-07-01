package convex.lattice.cursor;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * A transparent boundary cursor that stamps values on write — the write-side
 * dual of a last-write-wins timestamp.
 *
 * <p>Presents the underlying value unchanged on read ({@link #decode} = identity)
 * and applies a stamp function on every write ({@link #encode}), so a whole-value
 * LWW merge upstream picks the freshly-stamped value. It is the {@link ABoundaryCursor}
 * counterpart to {@link SignedCursor}: same-type ({@code V → V}) rather than
 * type-changing, and transparent rather than key-consuming.</p>
 *
 * <p>Inserted by {@code LWWLattice.createPathCursor} as a transparent
 * ({@code consumesPathKey == false}) write boundary, so a deep write below a
 * whole-value-LWW leaf re-stamps the whole leaf value on the way up.</p>
 *
 * @param <V> Type of the (same) stored and view value
 */
public class StampedCursor<V extends ACell> extends ABoundaryCursor<V, V> {

	private final UnaryOperator<V> stamp;

	StampedCursor(ACursor<V> base, ALattice<V> lattice, LatticeContext context, UnaryOperator<V> stamp) {
		super(base, lattice, context);
		this.stamp = stamp;
	}

	/**
	 * Creates a StampedCursor wrapping a base cursor.
	 *
	 * @param <V> Value type
	 * @param base Base cursor holding the value to stamp on write
	 * @param lattice Lattice for navigation below this cursor (may be null)
	 * @param context Lattice context
	 * @param stamp Stamp function applied to values on write (injects a timestamp)
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, ALattice<V> lattice, LatticeContext context, UnaryOperator<V> stamp) {
		return new StampedCursor<>(base, lattice, context, stamp);
	}

	@Override
	protected V encode(V view) {
		return stamp.apply(view); // stamp on write
	}

	@Override
	protected V decode(V stored) {
		return stored; // identity on read
	}

	/**
	 * Merge is convergence between values, not a new write: pick the winner via the
	 * lattice and store it <b>without re-stamping</b> — a merged/chosen value keeps
	 * its own timestamp, so a whole-value LWW winner is never bumped. (Contrast
	 * {@link SignedCursor}, which re-signs a merged value: the default
	 * {@link ABoundaryCursor} merge re-runs {@code encode}, which is right for
	 * signing but wrong for stamping.)
	 */
	@Override
	public V merge(V other) {
		if (lattice == null) throw new UnsupportedOperationException("Cannot merge without a lattice");
		return base.updateAndGet(current -> lattice.merge(context, current, other));
	}
}
