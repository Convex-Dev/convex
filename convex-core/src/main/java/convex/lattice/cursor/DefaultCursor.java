package convex.lattice.cursor;

import java.util.Objects;

import convex.core.data.ACell;

/**
 * A lazy cursor whose location is its source's whole value, with a default. While the
 * source is absent or nil it reads as the default, and update functions see the
 * default too. Writing back the default leaves the source absent; a changed value is
 * written to the source, so it lands only where the source's place exists or the
 * source constructs it. Writing null clears the source, after which reads again
 * return the default.
 *
 * <p>The default is a value chosen by whoever makes the cursor, not an inferred
 * container type or lattice policy.</p>
 *
 * @param <V> Value type
 */
public final class DefaultCursor<V extends ACell> extends ALazyCursor<V> {

	private DefaultCursor(ACursor<V> source,V defaultValue) {
		super(Objects.requireNonNull(source),Objects.requireNonNull(defaultValue));
	}

	/**
	 * Creates a cursor over a source that reads as a default while the source is
	 * absent or nil.
	 *
	 * @param <V> Value type
	 * @param source Cursor whose value this cursor reads and writes
	 * @param defaultValue Value read while the source is absent or nil, for example an empty map
	 * @return Cursor over the source with the default
	 */
	public static <V extends ACell> DefaultCursor<V> create(ACursor<V> source,V defaultValue) {
		return new DefaultCursor<>(source,defaultValue);
	}

	@Override protected Object locate(ACell stored) {
		return (stored==null)?MISSING:stored;
	}

	@Override protected ACell construct(ACell stored,V value) {
		return value;
	}
}
