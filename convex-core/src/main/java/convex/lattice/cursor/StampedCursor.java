package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;
import convex.lattice.Merge;
import convex.lattice.Shape;
import convex.lattice.Stamping;

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
 * the way in. Any navigation below is ordinary and explicit, stacked on top, and
 * inherits the stamped level's shape for creating missing containers.</p>
 *
 * <p>The timestamp is sourced from the {@link LatticeContext} at write time — the
 * single write clock, symmetric with {@link SignedCursor} sourcing its signer from
 * the context. The {@link Stamping} rule only says <em>where</em> to inject a given
 * timestamp into the value's shape. One logical write resolves the clock once, even
 * when it retries under contention.</p>
 *
 * <p>The component that owns a stamped level wraps it with
 * {@link #create(ACursor, Stamping)} before navigating below, so that a deep write
 * below a whole-value-LWW leaf re-stamps the whole leaf on the way up, letting a
 * whole-value LWW merge pick the freshest. Navigation never inserts this cursor.</p>
 *
 * @param <V> Type of the (unchanged) cell at this cursor position
 */
public class StampedCursor<V extends ACell> extends AUpdateCursor<V, V> {

	private final Stamping<V> stamping;

	StampedCursor(ACursor<V> base, Stamping<V> stamping, LatticeContext context) {
		super(base, stamping, context);
		this.stamping = stamping;
	}

	/**
	 * Wraps a cursor at a stamp-on-write level, inheriting the base cursor's context
	 * for the write clock. This is how a component crosses a stamping boundary:
	 * {@code StampedCursor.create(cursor.child(key, STAMPING), STAMPING).child(...)}.
	 *
	 * @param <V> Value type
	 * @param base Cursor at the stamped level
	 * @param stamping The stamp-on-write rule declared for this level, usually its lattice
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, Stamping<V> stamping) {
		return create(base, stamping, null);
	}

	/**
	 * Wraps a cursor at a stamp-on-write level with an explicit context override.
	 *
	 * @param <V> Value type
	 * @param base Cursor at the stamped level
	 * @param stamping The stamp-on-write rule declared for this level
	 * @param context Local context override, or null to inherit from the base cursor
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, Stamping<V> stamping, LatticeContext context) {
		if (stamping == null) throw new IllegalArgumentException("Stamping rule must not be null");
		return new StampedCursor<>(base, stamping, context);
	}

	/**
	 * Creates a StampedCursor from a shape and a stamp function, for adapters and
	 * tests that have no declared stamping lattice.
	 *
	 * @param <V> Value type
	 * @param base Base cursor holding the cell to stamp on write
	 * @param shape Shape of this level for writes below it, or null for none
	 * @param context Local context override, or null to inherit from the base cursor
	 *                (the effective context supplies the write timestamp)
	 * @param stamp Injects a given timestamp (from the context at write time) into a value
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, Shape<V> shape, LatticeContext context, BiFunction<V, CVMLong, V> stamp) {
		if (stamp == null) throw new IllegalArgumentException("Stamp function must not be null");
		Shape<V> s = (shape == null) ? Shape.none() : shape;
		return new StampedCursor<>(base, new Stamping<V>() {
			@Override public V zero() { return s.zero(); }
			@Override public boolean isStructural() { return s.isStructural(); }
			@Override public V stamp(V value, CVMLong timestamp) { return stamp.apply(value, timestamp); }
		}, context);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>A merge result is authored by the merge function, not by this writer, so it
	 * is installed as it is: the winner of a last-write-wins merge keeps its own
	 * timestamp rather than being re-stamped with the local clock.</p>
	 */
	@Override
	public V merge(Merge<V> merge, V other) {
		Objects.requireNonNull(merge, "Merge function must not be null");
		return base.updateAndGet(current -> merge.merge(getContext(), current, other));
	}

	@Override
	protected V prepareWrite(V value) {
		// An unchanged write never reaches here, so the stamp isn't bumped for no reason
		return stamping.stamp(value, getContext().currentTimestamp());
	}

	@Override
	protected Function<V,V> prepareWriteOperation() {
		CVMLong timestamp=getContext().currentTimestamp();
		return value -> stamping.stamp(value,timestamp);
	}

	// view inherited (identity) — the read view is never changed
}
