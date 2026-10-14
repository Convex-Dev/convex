package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.LatticeContext;
import convex.lattice.Merge;
import convex.lattice.Shape;

/**
 * A forked cursor: an independent working copy of a parent cursor's value.
 *
 * <p>Uses a {@link Root} internally for local storage. Modifications don't affect
 * the parent until {@link #sync()} is called. A fork captures its resolved context
 * policy for local writes, so a dynamic policy continues to resolve dynamically.</p>
 *
 * <p>The merge function supplied at fork time decides what {@code sync()} does when
 * the parent has changed since the fork: with one, the two values converge; without
 * one, the fork's value overwrites the parent (write-back). The component owning the
 * level supplies the function, usually its lattice.</p>
 *
 * <h3>Concurrent writes during sync</h3>
 * <p>{@link #sync()} is safe to call while other threads are writing to the fork.
 * Sync atomically propagates local changes to the parent, then optimistically
 * updates the local cursor via CAS. If concurrent writes landed between the
 * read and the CAS, the CAS fails and sync falls back to a merge,
 * preserving both the synced value and the concurrent writes.</p>
 *
 * @param <V> Type of cursor values
 */
public class ForkedCursor<V extends ACell> extends AForkableCursor<V> {

	private final AForkableCursor<V> parent;
	private final Root<V> localCursor;
	private volatile V forkPoint;
	private final LatticeContext forkContext;
	private final Merge<V> merge;

	/**
	 * Creates a forked cursor from a parent.
	 *
	 * @param parent The parent cursor to fork from
	 * @param currentValue The value at the time of fork
	 * @param context Resolved context policy captured at fork time
	 * @param merge Merge used by sync, or null for write-back
	 */
	ForkedCursor(AForkableCursor<V> parent, V currentValue, LatticeContext context, Merge<V> merge) {
		super(currentValue);
		this.parent = parent;
		this.merge = merge;
		Shape<V> shape = parent.shape();
		this.localCursor = (parent.exclusiveLock() != null)
			? new TransactionalRootCursor<>(currentValue, shape, context)
			: new Root<>(currentValue, shape, context);
		this.forkPoint = currentValue;
		this.forkContext = (context != null) ? context : LatticeContext.EMPTY;
	}

	@Override
	public Shape<V> shape() {
		return parent.shape();
	}

	@Override
	protected Lock exclusiveLock() {
		return localCursor.exclusiveLock();
	}

	@Override
	protected LatticeContext inheritedContext() {
		return forkContext;
	}

	@Override
	public V sync() {
		V localVal = localCursor.get();

		// Atomically update parent and get the merged result.
		//
		// Argument order is critical: merge positions are not equivalent.
		// The fork's local edits are the value being applied, so they are
		// "own" — for LWW-style lattices that prefer own on timestamp ties,
		// a locally made edit (e.g. a deletion re-stamped in the same
		// millisecond as its predecessor) must win against a concurrent
		// parent advance such as a propagator merge-back of an older
		// snapshot. Strictly newer parent values still win via timestamp.
		V synced = parent.updateAndGet(parentValue -> {
			if (parentValue == forkPoint) {
				// Fast path: parent unchanged since fork
				return localVal;
			} else if (merge != null) {
				return merge.merge(parent.getContext(), localVal, parentValue);
			} else {
				// No merge function: write-back (overwrite parent)
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
			if (merge != null) {
				localCursor.updateAndGet(current ->
					merge.merge(getContext(), current, synced));
			}
			// No merge function with concurrent writers: no well-defined merge.
			// Leave concurrent writes in place — they will propagate to parent
			// on the next sync. This preserves caller data at the cost of a
			// brief local/parent divergence.
		}

		return synced;
	}

	// ===== Delegate to local root =====

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
