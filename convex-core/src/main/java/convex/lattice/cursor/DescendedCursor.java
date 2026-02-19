package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * A cursor descended into a sub-path of a parent lattice cursor.
 *
 * <p>Delegates read/write operations to a {@link PathCursor} for
 * {@code RT.getIn}/{@code RT.assocIn} through the parent. Adds lattice
 * operations (merge, fork, sync) using the sub-lattice at this path.</p>
 *
 * <p>With a null lattice (navigated beyond the lattice hierarchy),
 * merge bubbles up via the parent and sync uses write-back semantics.</p>
 *
 * @param <V> Type of cursor values at this level
 */
public class DescendedCursor<P extends ACell, V extends ACell> extends ALatticeCursor<V> {

	private final ALatticeCursor<P> parent;
	private final PathCursor<V> pathCursor;
	private final ACell[] pathKeys;

	/**
	 * Creates a descended cursor from a range within a keys array.
	 *
	 * @param parent Parent lattice cursor
	 * @param keys Full keys array
	 * @param start Start index (inclusive)
	 * @param end End index (exclusive)
	 * @param lattice Sub-lattice at this position (may be null)
	 * @param context Merge context
	 */
	DescendedCursor(ALatticeCursor<P> parent, ACell[] keys, int start, int end, ALattice<V> lattice, LatticeContext context) {
		super(lattice, context, null);
		this.parent = parent;
		this.pathKeys = (start == 0 && end == keys.length) ? keys : copyRange(keys, start, end);
		this.pathCursor = new PathCursor<>(parent, pathKeys);
	}

	private static ACell[] copyRange(ACell[] keys, int start, int end) {
		ACell[] result = new ACell[end - start];
		System.arraycopy(keys, start, result, 0, result.length);
		return result;
	}

	/**
	 * Merge with null lattice: bubble up via parent.
	 * Constructs a parent-level value via assocIn and calls parent.merge(),
	 * which propagates up the chain until a cursor with a lattice handles it.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public V merge(V other) {
		if (lattice != null) {
			return updateAndGet(current -> lattice.merge(context, current, other));
		}
		// Null lattice: construct parent-level value and bubble up
		P parentLevel = (P) RT.assocIn(parent.get(), other, pathKeys);
		parent.merge(parentLevel);
		return get();
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
