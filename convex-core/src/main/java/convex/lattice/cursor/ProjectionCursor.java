package convex.lattice.cursor;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.data.ACell;

/**
 * Atomic view defined by caller-supplied read and write functions.
 * The write function replaces the viewed value within the current source value.
 * Both functions must be pure: updates may retry against a changed source.
 * Reading a written value must return that value; writing the current view back
 * must leave the source unchanged (by value equality, including null).
 * The functions must preserve unrelated source data. Any construction or validation belongs to those functions;
 * this cursor has no knowledge of containers, paths or lattices.
 *
 * @param <S> Source value type
 * @param <V> Viewed value type
 */
public final class ProjectionCursor<S extends ACell,V extends ACell> extends ATransformCursor<V,S> {

	private final Function<S,V> read;
	private final BiFunction<S,V,S> write;

	private ProjectionCursor(ACursor<S> source,Function<S,V> read,BiFunction<S,V,S> write) {
		super(source);
		this.read=read;
		this.write=write;
	}

	/** Creates a view without changing the source. */
	public static <S extends ACell,V extends ACell> ProjectionCursor<S,V> create(
			ACursor<S> source,Function<S,V> read,BiFunction<S,V,S> write) {
		return new ProjectionCursor<>(Objects.requireNonNull(source),Objects.requireNonNull(read),Objects.requireNonNull(write));
	}

	@Override protected V view(S stored) { return read.apply(stored); }
	@Override protected S write(S current,V value) { return write.apply(current,value); }
}
