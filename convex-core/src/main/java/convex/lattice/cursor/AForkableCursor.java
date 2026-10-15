package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * Mutable cursors with isolated copies and explicit write-back.
 * {@link #detach()} creates an independent snapshot; {@link #merge(AForkableCursor)}
 * attempts to install it by CAS. A raw {@link #fork()} overwrites its parent on sync.
 * Components provide lattice reconciliation when concurrent changes must merge.
 *
 * @param <V> Value type
 */
public abstract class AForkableCursor<V extends ACell> extends ACursor<V> {

	private final V initialValue;

	/**
	 * Creates a forkable cursor with the specified initial value.
	 *
	 * @param value Initial value (may be null)
	 */
	protected AForkableCursor(V value) {
		super();
		this.initialValue = value;
	}


	/**
	 * Gets the initial value of this cursor when it was created or forked: the
	 * baseline a compare-and-set merge checks against.
	 *
	 * @return Value of cursor when initialised (possibly null)
	 */
	public V getInitialValue() {
		return initialValue;
	}

	/**
	 * Merges a detached cursor back into this cursor using compare-and-set: succeeds
	 * only if this cursor still holds the detached cursor's initial value.
	 *
	 * @param detached The detached cursor to merge back (must have been detached from this cursor)
	 * @return true if merged, false if this cursor changed since detachment
	 * @see #detach()
	 * @see #sync() for explicit write-back on raw forks
	 */
	public boolean merge(AForkableCursor<V> detached) {
		V newValue = detached.get();
		V detachedValue = detached.getInitialValue();
		return compareAndSet(detachedValue, newValue);
	}


	/**
	 * Creates a path cursor navigating into a nested location within this cursor.
	 *
	 * @param <T> Type of value at the path
	 * @param path Keys to navigate through nested data structures
	 * @return A path cursor positioned at the nested path (returns this if path is empty)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> AForkableCursor<T> path(ACell... path) {
		if (path.length == 0) return (AForkableCursor<T>) this;
		return PathCursor.create(this, path);
	}

	/**
	 * Creates an independent copy of this cursor's value for isolated modifications,
	 * with no link back. Use {@link #merge(AForkableCursor)} to attempt a
	 * compare-and-set merge of the result.
	 *
	 * @param <T> Type of the detached cursor value
	 * @return A new independent Root cursor with the current value
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> AForkableCursor<T> detach() {
		return (AForkableCursor<T>) Root.create(get());
	}


	/**
	 * Creates a working copy with write-back semantics: {@link #sync()} overwrites
	 * this cursor's value with the fork's, whatever happened here in between. Use
	 * component-owned working state wherever concurrent writers are possible.
	 *
	 * @return A new forked cursor with local storage
	 */
	public ForkedCursor<V> fork() {
		return new ForkedCursor<>(this, get());
	}

	/**
	 * Synchronises this cursor towards its parent. On a raw fork, writes the working
	 * copy into the cursor it was forked from. On a live cursor, propagates to the
	 * root, where the host's publication policy runs.
	 *
	 * @return The value at this cursor after synchronisation
	 */
	public abstract V sync();
}
