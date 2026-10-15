package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.Shape;
import convex.lattice.Stamping;

/**
 * A same-type view which stamps changed values using an explicit clock function.
 * One logical atomic write resolves the clock once, including across CAS retries.
 * No-op writes retain their timestamp. Components merge incoming values through
 * the underlying storage cursor so accepted remote values are not re-stamped.
 *
 * <p>A component can optionally compose this wrapper with its own cursor to
 * stamp local edits at a chosen boundary:</p>
 * <pre>{@code
 * var edits = StampedCursor.create(storage, stamping, context::currentTimestamp);
 * edits.updateAndGet(this::applyLocalChange);
 * }</pre>
 * <p>The component supplies the stamping rule and clock. Changed writes through
 * {@code edits}, including writes through its descendant cursors, update the
 * boundary's value and timestamp atomically. Writes directly through
 * {@code storage} bypass stamping; retain that cursor for incoming merges which
 * must preserve their existing timestamps. The wrapper neither publishes changes
 * nor schedules work.</p>
 *
 * @param <V> Value type
 */
public class StampedCursor<V extends ACell> extends AUpdateCursor<V, V> {

	private final Stamping<V> stamping;
	private final Supplier<CVMLong> clock;

	StampedCursor(ACursor<V> base, Stamping<V> stamping, Supplier<CVMLong> clock) {
		super(base, stamping);
		this.clock=Objects.requireNonNull(clock);
		this.stamping = stamping;
	}

	/**
	 * Wraps a cursor at a stamp-on-write level, using the runtime clock. This is how a component crosses a stamping boundary:
	 * {@code StampedCursor.create(cursor.child(key, STAMPING), STAMPING).child(...)}.
	 *
	 * @param <V> Value type
	 * @param base Cursor at the stamped level
	 * @param stamping The stamp-on-write rule declared for this level, usually its lattice
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, Stamping<V> stamping) {
		return create(base, stamping, Utils::getCurrentTimestampCell);
	}

	/**
	 * Wraps a cursor at a stamp-on-write level with an explicit clock.
	 *
	 * @param <V> Value type
	 * @param base Cursor at the stamped level
	 * @param stamping The stamp-on-write rule declared for this level
	 * @param clock Timestamp source, resolved once per changed atomic operation
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, Stamping<V> stamping, Supplier<CVMLong> clock) {
		if (stamping == null) throw new IllegalArgumentException("Stamping rule must not be null");
		return new StampedCursor<>(base, stamping, clock);
	}

	/**
	 * Creates a StampedCursor from a shape and a stamp function, for adapters and
	 * tests that have no declared stamping lattice.
	 *
	 * @param <V> Value type
	 * @param base Base cursor holding the cell to stamp on write
	 * @param shape Shape of this level for writes below it, or null for none
	 * @param clock Timestamp source, resolved once per changed atomic operation
	 * @param stamp Injects a given timestamp (from the clock at write time) into a value
	 * @return New StampedCursor
	 */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, Shape<V> shape, Supplier<CVMLong> clock, BiFunction<V, CVMLong, V> stamp) {
		if (stamp == null) throw new IllegalArgumentException("Stamp function must not be null");
		Shape<V> s = (shape == null) ? Shape.none() : shape;
		return new StampedCursor<>(base, new Stamping<V>() {
			@Override public V zero() { return s.zero(); }
			@Override public boolean isStructural() { return s.isStructural(); }
			@Override public V stamp(V value, CVMLong timestamp) { return stamp.apply(value, timestamp); }
		}, clock);
	}


	@Override
	protected V prepareWrite(V value) {
		// An unchanged write never reaches here, so the stamp isn't bumped for no reason
		return stamping.stamp(value, clock.get());
	}

	@Override
	protected Function<V,V> prepareWriteOperation() {
		CVMLong timestamp=clock.get();
		return value -> stamping.stamp(value,timestamp);
	}

	// view inherited (identity) — the read view is never changed
}
