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
 * <h3>Concurrent writes during sync</h3>
 * <p>{@link #sync()} is safe to call while other threads are writing to the fork.
 * Sync atomically propagates local changes to the parent, then optimistically
 * updates the local cursor via CAS. If concurrent writes landed between the
 * read and the CAS, the CAS fails and sync falls back to a lattice merge,
 * preserving both the synced value and the concurrent writes.</p>
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

		forkPoint = synced;

		// Optimistically CAS local to the synced value. If no concurrent writes
		// happened on the fork since we read localVal, the CAS succeeds.
		// If it fails, another thread wrote to the fork during our sync — those
		// writes must be preserved, so we merge via an atomic updateAndGet.
		//
		// Argument order is critical: for LWW-style lattices that prefer the
		// first (own) argument on timestamp ties, we must pass the fork's
		// current state as own, because concurrent writes are semantically
		// newer than our sync snapshot (they happened after we read localVal).
		// Using current as own ensures: (a) ties resolve in favour of the
		// recent write, (b) strictly newer writes still win via timestamp
		// comparison, (c) strictly older writes cannot clobber the fork.
		if (!localCursor.compareAndSet(localVal, synced)) {
			if (lattice != null) {
				localCursor.updateAndGet(current ->
					lattice.merge(context, current, synced));
			}
			// Null lattice with concurrent writers: no well-defined merge.
			// Leave concurrent writes in place — they will propagate to parent
			// on the next sync. This preserves caller data at the cost of a
			// brief local/parent divergence.
		}

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
