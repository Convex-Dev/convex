package convex.lattice.cursor;

import java.util.function.BiFunction;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * A cursor that overrides <em>update semantics</em>: every value written through
 * it is passed through a {@code stamp} function before being stored, while reads
 * pass straight through unchanged.
 *
 * <p>This is a same-type ({@code V → V}) <b>update override</b>, not a view
 * boundary. Contrast {@link SignedCursor}, whose stored cell is a fundamentally
 * different envelope type ({@code SignedData<V>}). A StampedCursor never changes
 * the type or the view: it operates on the whole cell at its position — a cell that
 * may carry other meaningful fields — and {@link #get} returns exactly what is
 * stored, timestamp and all. It only changes <em>how writes land</em>: it stamps on
 * the way in. It inherits the identity {@link #view} and adds no navigation or key
 * consumption; any navigation below is ordinary and explicit, stacked on top.</p>
 *
 * <p>The timestamp is sourced from the {@link LatticeContext} at write time — the
 * single write clock, symmetric with {@link SignedCursor} sourcing its key from the
 * context. The {@code stamp} function only says <em>where</em> to inject a given
 * timestamp into the value's shape. A write throws {@link IllegalStateException} if
 * the context carries no timestamp (the enforcement point).</p>
 *
 * <p><b>Update vs merge.</b> The stamp overrides update semantics only. A
 * {@link #merge} is convergence, not a fresh write: it picks the winner via the
 * lattice and stores it <em>without</em> stamping, so a whole-value LWW winner keeps
 * its own timestamp and is never bumped.</p>
 *
 * <p>Inserted by {@code StampingLattice.createPathCursor} as a transparent write
 * boundary so that a deep write below a whole-value-LWW leaf re-stamps the whole
 * leaf on the way up, letting a whole-value LWW merge pick the freshest.</p>
 *
 * @param <V> Type of the (unchanged) cell at this cursor position
 */
public class StampedCursor<V extends ACell> extends AUpdateCursor<V, V> {

	private final BiFunction<V, CVMLong, V> stamp;

	StampedCursor(ACursor<V> base, ALattice<V> lattice, LatticeContext context, BiFunction<V, CVMLong, V> stamp) {
		super(base, lattice, context);
		this.stamp = stamp;
	}

	/**
	 * Creates a StampedCursor wrapping a base cursor.
	 *
	 * @param <V> Value type
	 * @param base Base cursor holding the cell to stamp on write
	 * @param lattice Lattice for navigation below this cursor (may be null)
	 * @param context Lattice context (supplies the write timestamp)
	 * @param stamp Injects a given timestamp (from the context at write time) into a value
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, ALattice<V> lattice, LatticeContext context, BiFunction<V, CVMLong, V> stamp) {
		return new StampedCursor<>(base, lattice, context, stamp);
	}

	@Override
	protected V updateOnWrite(V current, V value) {
		// Unchanged write: keep the current cell so the stamp isn't bumped for no reason
		if (Utils.equals(value, current)) return current;
		if (value == null) return null;
		CVMLong ts = context.getTimestamp();
		if (ts == null) throw new IllegalStateException("StampedCursor requires a timestamp in the LatticeContext");
		return stamp.apply(value, ts);
	}

	// view inherited (identity) — the read view is never changed

	/**
	 * Merge is convergence, not an update: pick the winner via the lattice and store
	 * it <b>without stamping</b>, so a whole-value LWW winner keeps its own timestamp.
	 */
	@Override
	public V merge(V other) {
		if (lattice == null) throw new UnsupportedOperationException("Cannot merge without a lattice");
		return base.updateAndGet(current -> lattice.merge(context, current, other));
	}
}
