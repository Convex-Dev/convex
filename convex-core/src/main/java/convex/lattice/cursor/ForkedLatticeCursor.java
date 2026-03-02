package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * A forked lattice cursor - an independent working copy forked from a parent.
 *
 * <p>Uses a {@link Root} cursor internally for local storage, adding lattice-aware
 * sync operations on top.</p>
 *
 * <p>Modifications to a forked cursor don't affect the parent until {@link #sync()}
 * is called. The sync operation uses lattice merge semantics and always succeeds.</p>
 *
 * @param <V> Type of cursor values
 */
public class ForkedLatticeCursor<V extends ACell> extends ALatticeCursor<V> {

	private final ALatticeCursor<V> parent;
	private final Root<V> localCursor;
	private volatile V forkPoint;

	/**
	 * Creates a forked cursor from a parent.
	 *
	 * @param parent The parent cursor to fork from
	 * @param lattice The lattice defining merge semantics
	 * @param currentValue The value at the time of fork
	 * @param context The merge context
	 */
	ForkedLatticeCursor(ALatticeCursor<V> parent, ALattice<V> lattice, V currentValue, LatticeContext context) {
		super(lattice, context, currentValue);
		this.parent = parent;
		this.localCursor = Root.create(currentValue);
		this.forkPoint = currentValue;
	}

	@Override
	public V sync() {
		V localVal = localCursor.get();

		// Atomically update parent and get the merged result
		V synced = parent.updateAndGet(parentValue -> {
			if (parentValue == forkPoint) {
				// Fast path: parent unchanged since fork
				return localVal;
			} else if (lattice != null) {
				// Parent changed: perform lattice merge
				return lattice.merge(context, parentValue, localVal);
			} else {
				// Null lattice: write-back (overwrite parent)
				return localVal;
			}
		});

		// Update local state for subsequent syncs
		forkPoint = synced;
		localCursor.set(synced);

		return synced;
	}

	// ===== Delegate to Root cursor =====

	@Override
	public V get() {
		return localCursor.get();
	}

	@Override
	public void set(V newValue) {
		localCursor.set(newValue);
	}

	@Override
	public V getAndSet(V newValue) {
		return localCursor.getAndSet(newValue);
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		return localCursor.compareAndSet(expected, newValue);
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return localCursor.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return localCursor.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return localCursor.getAndAccumulate(x, accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return localCursor.accumulateAndGet(x, accumulatorFunction);
	}

	/**
	 * Gets the value at the time this cursor was forked.
	 *
	 * @return The fork point value
	 */
	public V getForkPoint() {
		return forkPoint;
	}
}
