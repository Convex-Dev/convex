 package convex.lattice.cursor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Root cursor for an atomically updatable CVM value.
 *
 * <p>This is a lightweight CVM wrapper over {@link java.util.concurrent.atomic.AtomicReference},
 * providing thread-safe atomic operations on CVM values.</p>
 *
 * @param <V> Type of cursor values
 */
public class Root<V extends ACell> extends AForkableCursor<V> {

	final AtomicReference<V> value;
	ACursor<V> parent;
	
	public Root() {
		this((V)null);
	}
	
	public Root(V initialValue) {
		super(initialValue);
		value=new AtomicReference<V>(initialValue);
	}
	
	public Root(ACursor<V> parent) {
		this(parent.get());
		this.parent=parent;
	}
	
	/**
	 * Create a new Root cursor with the given initial value and no parent
	 * @param <V> Type of cursor values
	 * @param value Initial value for cursor
	 * @return Root cursor instance
	 */
	public static <V extends ACell> Root<V> create(V value) {
		return new Root<V>(value);
	}
	
	/**
	 * Create a new Root cursor based on the parent
	 * @param <V> Type of cursor values
	 * @param parent Parent cursor
	 * @return Root cursor instance
	 */
	public static <V extends ACell> Root<V> create(ACursor<V> parent) {
		return new Root<V>(parent);
	}

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
