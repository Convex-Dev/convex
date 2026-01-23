package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * Abstract base class for cursors that support forking (detachment) operations.
 *
 * <p>A forkable cursor can be detached from its parent to create an independent
 * working copy. Changes to the detached cursor don't affect the parent until
 * explicitly merged back.</p>
 *
 * <p>The {@link #merge(AForkableCursor)} method uses CAS (Compare-And-Set) semantics,
 * which means it will fail if the parent has been modified since the fork was created.
 * For lattice-aware merging that always succeeds, see {@link ALatticeCursor}.</p>
 *
 * @param <V> Type of cursor values
 * @see ALatticeCursor for lattice-aware cursors with guaranteed merge success
 */
public abstract class AForkableCursor<V extends ACell> extends ACursor<V> {

	private final V initialValue;

	protected AForkableCursor(V value) {
		super();
		this.initialValue = value;
	}

	/**
	 * Gets the initial value of this cursor when it was created/forked.
	 *
	 * @return Value of cursor when initialised (possibly null)
	 */
	public V getInitialValue() {
		return initialValue;
	}

	/**
	 * Merge a detached cursor back into this cursor using CAS semantics.
	 *
	 * <p>This method attempts to update this cursor's value to match the detached
	 * cursor's current value, but only if this cursor's value still equals the
	 * detached cursor's initial value (i.e., the value when it was forked).</p>
	 *
	 * <p>If the parent has been modified since the fork, the merge will fail and
	 * return false. The caller must handle the conflict manually (e.g., by
	 * re-reading and reapplying changes).</p>
	 *
	 * @param detached The detached cursor to merge back
	 * @return true if merge was successful, false if parent changed since fork
	 */
	public boolean merge(AForkableCursor<V> detached) {
		V newValue = detached.get();
		V detachedValue = detached.getInitialValue();

		boolean updated = compareAndSet(detachedValue, newValue);
		return updated;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> AForkableCursor<T> path(ACell... path) {
		if (path.length == 0) return (AForkableCursor<T>) this;
		return PathCursor.create(this, path);
	}
}
