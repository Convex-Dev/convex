package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * Mutable cursors with isolated copies and explicit write-back.
 * A raw {@link #fork()} overwrites its parent on sync. Atomic drafts are supplied
 * by {@link Transactions}; cursors do not retain historical snapshots.
 * Components provide lattice reconciliation when concurrent changes must merge.
 *
 * @param <V> Value type
 */
public abstract class AForkableCursor<V extends ACell> extends ACursor<V> {

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
