package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * A same-type view which stamps changed values using an explicit clock function.
 * Each attempt at a changed write reads the clock, so a write that loses a race and
 * retries is stamped no earlier than the write it lost to.
 * No-op writes retain their timestamp. Components merge incoming values through
 * the underlying storage cursor so accepted remote values are not re-stamped.
 *
 * <p>A component can optionally compose this wrapper with its own cursor to
 * stamp local edits at a chosen boundary:</p>
 * <pre>{@code
 * var edits = StampedCursor.create(storage, context::currentTimestamp, this::stamp);
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
public class StampedCursor<V extends ACell> extends ATransformCursor<V, V> {

	private final BiFunction<V,CVMLong,V> stamp;
	private final Supplier<CVMLong> clock;

	private StampedCursor(ACursor<V> base, Supplier<CVMLong> clock, BiFunction<V,CVMLong,V> stamp) {
		super(base);
		this.clock=Objects.requireNonNull(clock);
		this.stamp=Objects.requireNonNull(stamp);
	}

	/** Wraps local edits with an explicit clock and timestamp injection function. */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base,
			Supplier<CVMLong> clock, BiFunction<V,CVMLong,V> stamp) {
		return new StampedCursor<>(base,clock,stamp);
	}

	/** Wraps local edits using the runtime clock and an explicit stamp function. */
	public static <V extends ACell> StampedCursor<V> create(ACursor<V> base, BiFunction<V,CVMLong,V> stamp) {
		return create(base,Utils::getCurrentTimestampCell,stamp);
	}

	@Override protected V view(V stored) { return stored; }

	@Override protected V write(V current,V value) {
		return value==null?null:stamp.apply(value,clock.get());
	}
}
