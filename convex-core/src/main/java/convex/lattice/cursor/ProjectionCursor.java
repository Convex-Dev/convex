package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

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
public final class ProjectionCursor<S extends ACell,V extends ACell> extends AForkableCursor<V> {

	private final AForkableCursor<S> source;
	private final Function<S,V> read;
	private final BiFunction<S,V,S> write;

	private ProjectionCursor(AForkableCursor<S> source,Function<S,V> read,BiFunction<S,V,S> write) {
		this.source=source;
		this.read=read;
		this.write=write;
	}

	/** Creates a view without changing the source. */
	public static <S extends ACell,V extends ACell> ProjectionCursor<S,V> create(
			AForkableCursor<S> source,Function<S,V> read,BiFunction<S,V,S> write) {
		return new ProjectionCursor<>(Objects.requireNonNull(source),Objects.requireNonNull(read),Objects.requireNonNull(write));
	}

	@Override public V get() { return read.apply(source.get()); }

	@Override public void set(V value) { source.update(s -> write.apply(s,value)); }

	@Override public V getAndSet(V value) {
		return read.apply(source.getAndUpdate(s -> write.apply(s,value)));
	}

	@Override public V getAndUpdate(UnaryOperator<V> action) {
		return read.apply(source.getAndUpdate(s -> write.apply(s,action.apply(read.apply(s)))));
	}

	@Override public V updateAndGet(UnaryOperator<V> action) {
		return read.apply(source.updateAndGet(s -> write.apply(s,action.apply(read.apply(s)))));
	}

	@Override public V getAndAccumulate(V value,BinaryOperator<V> action) {
		return getAndUpdate(current -> action.apply(current,value));
	}

	@Override public V accumulateAndGet(V value,BinaryOperator<V> action) {
		return updateAndGet(current -> action.apply(current,value));
	}

	/** Single attempt against the observed source, comparing the view by value. */
	@Override public boolean compareAndSet(V expected,V value) {
		S current=source.get();
		if (!Utils.equals(expected,read.apply(current))) return false;
		return source.compareAndSet(current,write.apply(current,value));
	}

	@Override protected Lock exclusiveLock() { return source.exclusiveLock(); }

	@Override public V sync() {
		source.sync();
		return get();
	}
}
