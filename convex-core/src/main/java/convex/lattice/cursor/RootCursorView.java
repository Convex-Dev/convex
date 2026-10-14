package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.LatticeContext;

/**
 * Live root view with its own context override and no independent state.
 * Descendants inherit this view's context; publication belongs to the root.
 */
final class RootCursorView<V extends ACell> extends ALatticeCursor<V> {

	private final RootLatticeCursor<V> root;

	RootCursorView(RootLatticeCursor<V> root) {
		super(root.getLattice(),null,null);
		this.root=root;
	}

	@Override
	protected Lock exclusiveLock() {
		return root.exclusiveLock();
	}

	@Override
	protected LatticeContext getInheritedContext() {
		return root.getContext();
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
