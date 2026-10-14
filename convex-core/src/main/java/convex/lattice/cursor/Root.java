package convex.lattice.cursor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.LatticeContext;
import convex.lattice.Merge;
import convex.lattice.Shape;

/**
 * Root cursor: the authoritative holder of a value, over an
 * {@link AtomicReference}. Every other cursor is a view, path or fork reaching back
 * to a root.
 *
 * <p>A root may carry a {@link Shape} for its position and a default write-policy
 * context, both supplied by whoever creates it. It has one host seam:
 * {@link #onSync} installs the publication callback that {@link #sync()} runs, with
 * the merge function used to resolve a write which lands during publication. The
 * root itself knows nothing about storage or lattices.</p>
 *
 * @param <V> Type of cursor values
 */
public class Root<V extends ACell> extends AForkableCursor<V> {

	final AtomicReference<V> value;
	private final Shape<V> shape;
	private final ReentrantLock syncLock = new ReentrantLock();
	private volatile Function<V, V> publish;
	private volatile Merge<V> publishMerge;

	public Root() {
		this((V) null);
	}

	public Root(V initialValue) {
		this(initialValue, null, null);
	}

	/**
	 * Creates a root with a declared shape and default context.
	 *
	 * @param initialValue Initial value (may be null)
	 * @param shape Shape of this position, or null for none
	 * @param context Default write policy, or null for {@link LatticeContext#EMPTY}
	 */
	public Root(V initialValue, Shape<V> shape, LatticeContext context) {
		super(initialValue, context);
		this.value = new AtomicReference<>(initialValue);
		this.shape = (shape == null) ? Shape.none() : shape;
	}

	/**
	 * Create a new Root cursor with the given initial value
	 * @param <V> Type of cursor values
	 * @param value Initial value for cursor
	 * @return Root cursor instance
	 */
	public static <V extends ACell> Root<V> create(V value) {
		return new Root<V>(value);
	}

	@Override
	public Shape<V> shape() {
		return shape;
	}

	/**
	 * Creates a live view sharing this root's state and shape, with an independent
	 * context override. Initially the view inherits this root's context live;
	 * {@link ACursor#setContext(LatticeContext)} changes only that view.
	 * Descendants must be navigated from the view to inherit its context.
	 *
	 * <p>Writes and merges are immediately visible through all views. Forks remain
	 * isolated until synced into their originating view. View sync publishes through
	 * this root, using the root's context for any publication retry merge.</p>
	 *
	 * @return A new live cursor view, with no independent state storage
	 */
	public AForkableCursor<V> view() {
		return new RootCursorView<>(this);
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
	public void onSync(Function<V, V> publish, Merge<V> merge) {
		this.publish = publish;
		this.publishMerge = merge;
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
			if (callback == null) callback = publish;
			if (callback == null) return get();

			V current = get();
			V synced = callback.apply(current);
			// Fast path: no concurrent writes, CAS succeeds
			if (compareAndSet(current, synced)) return synced;
			// Concurrent write: fall back to the host-supplied merge
			Merge<V> merge = publishMerge;
			if (merge == null) {
				throw new IllegalStateException("Concurrent write during sync with no merge function to resolve it");
			}
			updateAndGet(c -> merge.merge(getContext(), c, synced));
			// The concurrent value remains pending in the cursor, with current/local
			// state in the directional merge's own position. Return only the exact
			// value completed by this callback: this is the snapshot the caller can
			// rely on as published and store-backed.
			return synced;
		} finally {
			syncLock.unlock();
		}
	}

	// ===== Standard cursor operations =====

	@Override
	public V get() {
		return value.get();
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
	public void set(V newValue) {
		value.set(newValue);
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
		return value.getAndAccumulate(x,accumulatorFunction);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return value.accumulateAndGet(x,accumulatorFunction);
	}

}
