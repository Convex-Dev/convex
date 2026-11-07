package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Read-only view of a forkable cursor, created by {@link AForkableCursor#readOnly()}.
 * Reads pass through to the source from one snapshot under its read contract.
 * Every write is refused with {@link ReadOnlyCursorException} before the source is
 * touched, whether or not it would change the value; persisting is a write too.
 * Paths, defaults and transformed views over this cursor write through it, so they
 * are read-only as well. A fork is ordinary writable working storage: its sync
 * publishes through this view and is refused, leaving the fork's edits in place.
 *
 * @param <V> Value type
 */
public final class ReadOnlyCursor<V extends ACell> extends AForkableCursor<V> {

	private final AForkableCursor<V> source;

	ReadOnlyCursor(AForkableCursor<V> source) {
		this.source=Objects.requireNonNull(source);
	}

	@Override public V get() { return source.get(); }
	@Override protected Object readValue() { return source.readValue(); }
	@Override protected Lock exclusiveLock() { return source.exclusiveLock(); }
	@Override public AForkableCursor<V> readOnly() { return this; }

	@Override public V getAndSet(V newValue) { throw new ReadOnlyCursorException(); }
	@Override public boolean compareAndSet(V expected,V newValue) { throw new ReadOnlyCursorException(); }
	@Override public void set(V newValue) { throw new ReadOnlyCursorException(); }
	@Override public V getAndUpdate(UnaryOperator<V> updateFunction) { throw new ReadOnlyCursorException(); }
	@Override public V updateAndGet(UnaryOperator<V> updateFunction) { throw new ReadOnlyCursorException(); }
	@Override public V getAndAccumulate(V x,BinaryOperator<V> accumulatorFunction) { throw new ReadOnlyCursorException(); }
	@Override public V accumulateAndGet(V x,BinaryOperator<V> accumulatorFunction) { throw new ReadOnlyCursorException(); }
}
