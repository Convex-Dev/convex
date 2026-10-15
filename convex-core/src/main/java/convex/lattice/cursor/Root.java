package convex.lattice.cursor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * AtomicReference-backed value storage.
 * Publication is an optional {@link PublishingCursor} wrapper; ordinary roots
 * retain only their current value and have no sync lock or callbacks.
 *
 * @param <V> Value type
 */
public class Root<V extends ACell> extends AForkableCursor<V> {

	final AtomicReference<V> value;

	public Root() {
		this((V) null);
	}

	public Root(V initialValue) {
		this.value = new AtomicReference<>(initialValue);
	}

	/**
	 * Create a new Root cursor with the given initial value
	 * @param <V> Type of cursor values
	 * @param value Initial value for cursor
	 * @return Root cursor instance
	 */
	public static <V extends ACell> Root<V> create(V value) {
		return new Root<V>(value);
	}


	/** Ordinary storage has no publication work. */
	@Override public V sync() { return get(); }


	// ===== Standard cursor operations =====

	@Override
	public V get() {
		return value.get();
	}

	@Override
	public V getAndSet(V newValue) {
		return value.getAndSet(newValue);
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		return value.compareAndSet(expected, newValue);
	}

	@Override
	public void set(V newValue) {
		value.set(newValue);
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return value.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return value.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return value.getAndAccumulate(x,accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return value.accumulateAndGet(x,accumulatorFunction);
	}

}
