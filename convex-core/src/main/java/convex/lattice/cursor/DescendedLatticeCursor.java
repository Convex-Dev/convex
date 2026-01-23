package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * A cursor that has descended into a sub-lattice of a parent cursor.
 *
 * <p>Wraps a {@link PathCursor} for standard cursor operations while adding
 * lattice-aware operations ({@code fork}, {@code sync}, {@code merge}, {@code descend}).</p>
 *
 * @param <V> Type of cursor values at this level
 */
public class DescendedLatticeCursor<V extends ACell> extends ALatticeCursor<V> {

	private final PathCursor<V> pathCursor;

	/**
	 * Creates a descended cursor.
	 *
	 * @param parent Parent cursor to descend from
	 * @param pathKey Key at which this cursor is located in the parent
	 * @param lattice Lattice defining merge semantics at this level
	 * @param context Merge context
	 */
	@SuppressWarnings("unchecked")
	DescendedLatticeCursor(ALatticeCursor<?> parent, ACell pathKey, ALattice<V> lattice, LatticeContext context) {
		super(lattice, context, null); // initialValue not used - we delegate to pathCursor
		this.pathCursor = new PathCursor<>((ACursor<ACell>) parent, pathKey);
	}

	// ===== Delegate to PathCursor =====

	@Override
	public V get() {
		return pathCursor.get();
	}

	@Override
	public void set(V newValue) {
		pathCursor.set(newValue);
	}

	@Override
	public V getAndSet(V newValue) {
		return pathCursor.getAndSet(newValue);
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		return pathCursor.compareAndSet(expected, newValue);
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return pathCursor.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return pathCursor.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return pathCursor.getAndAccumulate(x, accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return pathCursor.accumulateAndGet(x, accumulatorFunction);
	}
}
