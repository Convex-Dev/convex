package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;

/**
 * Independent working storage which reconciles with a destination on sync.
 * An unchanged fork adopts the destination; an unchanged destination accepts
 * the working value. When both changed, the merge supplied to sync decides.
 *
 * <p>Sync updates the destination atomically while holding the working root's
 * write lock. Working reads and writes are ordered around sync. Failed
 * reconciliation or destination preparation leaves the working value and
 * baseline unchanged. Successful publication requires no further merge.</p>
 *
 * <p>The destination has the same value type as the fork. Compose a cursor view,
 * such as {@link SignedCursor}, for representation changes or write policy.
 * Sync stops at that destination and never publishes it further.</p>
 *
 * <p>Merge and destination write functions must not mutate or sync this fork,
 * or wait for another operation on it.</p>
 *
 * @param <V> Working and destination value type
 */
public final class ForkedCursor<V extends ACell> extends AForkableCursor<V> {

	private final ACursor<V> destination;
	private final TransactionalRootCursor<V> working;
	/** Last reconciled destination value, protected by the working root lock. */
	private V baseline;

	private ForkedCursor(ACursor<V> destination) {
		this.destination=Objects.requireNonNull(destination,"Destination must not be null");
		this.baseline=destination.get();
		this.working=new TransactionalRootCursor<>(baseline);
	}

	/**
	 * Creates an independent working copy of the destination.
	 * @param <V> Value type
	 * @param destination Cursor the copy publishes into
	 * @return Working copy initialised from the destination
	 */
	public static <V extends ACell> ForkedCursor<V> create(ACursor<V> destination) {
		return new ForkedCursor<>(destination);
	}

	/**
	 * Reconciles and publishes through one atomic destination update. Its cursor
	 * handles CAS retries and any signing or stamping. Concurrent working writes
	 * wait until publication and adoption of the result are complete.
	 * @param merge Reconciliation of working and destination values when both changed;
	 *        must be retry-safe and must not mutate either cursor
	 * @return Published value
	 */
	@Override
	public V sync(BinaryOperator<V> merge) {
		Objects.requireNonNull(merge,"Merge function must not be null");
		Lock lock=working.exclusiveLock();
		lock.lock();
		try {
			V published=destination.updateAndGet(new Reconciliation(working.get(),merge));
			working.set(published);
			baseline=published;
			return published;
		} finally {
			lock.unlock();
		}
	}

	/** Retains reconciled changes as the destination update retries. */
	private final class Reconciliation implements UnaryOperator<V> {
		private final BinaryOperator<V> merge;
		private V previous=baseline;
		private V candidate;

		Reconciliation(V snapshot,BinaryOperator<V> merge) {
			this.merge=merge;
			candidate=snapshot;
		}

		@Override public V apply(V other) {
			if (Utils.equals(candidate,previous) || Utils.equals(candidate,other)) {
				candidate=other;
			} else if (!Utils.equals(other,previous)) {
				candidate=merge.apply(candidate,other);
			}
			previous=other;
			return candidate;
		}
	}

	// ===== Working storage =====

	@Override
	protected Lock exclusiveLock() {
		return working.exclusiveLock();
	}

	@Override
	public V get() {
		Lock lock=working.exclusiveLock();
		lock.lock();
		try { return working.get(); } finally { lock.unlock(); }
	}

	@Override
	public void set(V newValue) {
		working.set(newValue);
	}

	@Override
	public V getAndSet(V newValue) {
		return working.getAndSet(newValue);
	}

	@Override
	public boolean compareAndSet(V expected,V newValue) {
		return working.compareAndSet(expected,newValue);
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return working.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return working.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x,BinaryOperator<V> accumulatorFunction) {
		return working.getAndAccumulate(x,accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x,BinaryOperator<V> accumulatorFunction) {
		return working.accumulateAndGet(x,accumulatorFunction);
	}
}
