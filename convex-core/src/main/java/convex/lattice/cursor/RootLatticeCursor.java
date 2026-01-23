package convex.lattice.cursor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Root lattice cursor - the top-level cursor in a lattice cursor hierarchy.
 *
 * <p>A root cursor has no parent and holds the authoritative value in an
 * AtomicReference for thread-safe access.</p>
 *
 * @param <V> Type of cursor values
 */
public class RootLatticeCursor<V extends ACell> extends ALatticeCursor<V> {

	private final AtomicReference<V> value;

	/**
	 * Creates a root lattice cursor.
	 *
	 * @param lattice The lattice defining merge semantics
	 * @param initialValue Initial value for the cursor
	 * @param context Initial context for merge operations
	 */
	public RootLatticeCursor(ALattice<V> lattice, V initialValue, LatticeContext context) {
		super(lattice, context, initialValue);
		this.value = new AtomicReference<>(initialValue);
	}

	/**
	 * Creates a root lattice cursor with empty context.
	 *
	 * @param lattice The lattice defining merge semantics
	 * @param initialValue Initial value for the cursor
	 */
	public RootLatticeCursor(ALattice<V> lattice, V initialValue) {
		this(lattice, initialValue, LatticeContext.EMPTY);
	}

	// ===== Standard cursor operations =====

	@Override
	public V get() {
		return value.get();
	}

	@Override
	public void set(V newValue) {
		value.set(newValue);
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
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return value.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return value.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return value.getAndAccumulate(x, accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return value.accumulateAndGet(x, accumulatorFunction);
	}
}
