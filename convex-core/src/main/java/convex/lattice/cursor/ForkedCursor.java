package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * An isolated value with explicit overwrite-on-sync semantics.
 * This cursor performs no lattice reconciliation. Components that need CAS-or-merge
 * publication own a working root and destination instead. Concurrent local edits
 * remain in local storage for a later sync.
 *
 * @param <V> Value type
 */
public class ForkedCursor<V extends ACell> extends AForkableCursor<V> {

	private final AForkableCursor<V> parent;
	private final Root<V> localCursor;

	/**
	 * Creates a forked cursor from a parent.
	 *
	 * @param parent The parent cursor to fork from
	 * @param currentValue The value at the time of fork
	 */
	ForkedCursor(AForkableCursor<V> parent, V currentValue) {
		this.parent = parent;
		this.localCursor = (parent.exclusiveLock() != null)
			? new TransactionalRootCursor<>(currentValue)
			: new Root<>(currentValue);
	}


	@Override
	protected Lock exclusiveLock() {
		return localCursor.exclusiveLock();
	}


	@Override
	public V sync() {
		V snapshot=localCursor.get();
		V published=parent.updateAndGet(ignored -> snapshot);
		// A destination view may prepare the value (e.g. stamp it). Adopt that
		// result only while the local snapshot is unchanged; newer edits stay pending.
		localCursor.compareAndSet(snapshot,published);
		return published;
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

}
