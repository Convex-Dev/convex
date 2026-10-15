package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.Shape;

/**
 * Live root view sharing state, shape and publication, with no independent policy.
 */
final class RootCursorView<V extends ACell> extends AForkableCursor<V> {

	private final Root<V> root;

	RootCursorView(Root<V> root) {
		super(root.get());
		this.root=root;
	}

	@Override
	public Shape<V> shape() {
		return root.shape();
	}

	@Override
	protected Lock exclusiveLock() {
		return root.exclusiveLock();
	}


	@Override
	public V sync() {
		return root.sync();
	}

	@Override
	public V get() {
		return root.get();
	}

	@Override
	public void set(V newValue) {
		root.set(newValue);
	}

	@Override
	public V getAndSet(V newValue) {
		return root.getAndSet(newValue);
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		return root.compareAndSet(expected,newValue);
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return root.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return root.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return root.getAndAccumulate(x,accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return root.accumulateAndGet(x,accumulatorFunction);
	}
}
