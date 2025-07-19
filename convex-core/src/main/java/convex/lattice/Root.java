package convex.lattice;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Root cursor for an atomically updatable CVM value. This is a lightweight CVM wrapper over java.util.concurrent.atomic.AtomicReference
 * @param <V>
 */
public class Root<V extends ACell> extends ACursor<V> {

	AtomicReference<V> value;
	
	public Root() {
		this(null);
	}
	
	public Root(V initialValue) {
		value=new AtomicReference<V>(initialValue);
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
