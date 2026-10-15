package convex.lattice.cursor;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Explicit publication wrapper around atomic storage. Sync callbacks are serialised;
 * ordinary writes and exclusive transactions retain the source cursor's semantics.
 * The wrapper carries no lattice, store, clock or signing context.
 *
 * @param <V> Stored value type
 */
public final class PublishingCursor<V extends ACell> extends AForkableCursor<V> {

	private final AForkableCursor<V> source;
	private final ReentrantLock syncLock = new ReentrantLock();
	private volatile Publication<V> publication;

	private record Publication<V>(Function<V,V> publish, BinaryOperator<V> merge) {}

	/** Wraps existing storage; continue through this wrapper to reach publication on sync. */
	public PublishingCursor(AForkableCursor<V> source) {
		this.source=Objects.requireNonNull(source);
	}

	/** Creates ordinary atomic storage with an explicit publication boundary. */
	public static <V extends ACell> PublishingCursor<V> create(V initialValue) {
		return new PublishingCursor<>(Root.create(initialValue));
	}

	/**
	 * Installs the host's publication policy, invoked by {@link #sync()}. The
	 * callback receives the current value and returns the published result (e.g.
	 * with store-backed references), which is installed back here. Callbacks run
	 * synchronously on the thread calling {@code sync()} and are serialised with
	 * callbacks from other concurrent sync callers; ordinary reads and writes may
	 * proceed while a callback is running.
	 *
	 * @param publish Function from current value to published value, or null to clear
	 * @param merge Merge used when a concurrent write lands during publication, or
	 *              null if such a write should fail the sync instead
	 */
	public void onSync(Function<V, V> publish, BinaryOperator<V> merge) {
		this.publication = new Publication<>(publish,merge);
	}

	@Override
	public V sync() {
		return sync(null);
	}

	/**
	 * Publishes using a per-call callback, sharing the normal sync ordering and
	 * CAS-or-merge write-back. This lets a host select publication policy for an
	 * explicit persistence operation without replacing the configured callback.
	 * The callback runs once, outside retryable atomic update functions.
	 *
	 * @param callback Publication callback, or null to use the configured callback
	 * @return Exact published value; concurrent writes may remain pending locally
	 */
	public V sync(Function<V, V> callback) {
		syncLock.lock();
		try {
			Publication<V> policy=publication;
			if (callback == null && policy != null) callback = policy.publish();
			if (callback == null) return get();

			V current = get();
			V synced = callback.apply(current);
			// Fast path: no concurrent writes, CAS succeeds
			if (compareAndSet(current, synced)) return synced;
			// Concurrent write: fall back to the host-supplied merge
			BinaryOperator<V> merge = policy == null ? null : policy.merge();
			if (merge == null) {
				throw new IllegalStateException("Concurrent write during sync with no merge function to resolve it");
			}
			updateAndGet(c -> merge.apply(c, synced));
			// The concurrent value remains pending in the cursor, with current/local
			// state in the directional merge's own position. Return only the exact
			// value completed by this callback: this is the snapshot the caller can
			// rely on as published and store-backed.
			return synced;
		} finally {
			syncLock.unlock();
		}
	}

	@Override
	protected Lock exclusiveLock() {
		return source.exclusiveLock();
	}


	@Override
	public V get() {
		return source.get();
	}

	@Override
	public void set(V newValue) {
		source.set(newValue);
	}

	@Override
	public V getAndSet(V newValue) {
		return source.getAndSet(newValue);
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		return source.compareAndSet(expected,newValue);
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return source.getAndUpdate(updateFunction);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return source.updateAndGet(updateFunction);
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return source.getAndAccumulate(x,accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return source.accumulateAndGet(x,accumulatorFunction);
	}
}
