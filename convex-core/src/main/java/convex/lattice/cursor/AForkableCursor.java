package convex.lattice.cursor;

import java.util.function.BinaryOperator;

import convex.core.data.ACell;

/**
 * Mutable cursors whose paths are themselves mutable cursors and which can be
 * forked. A fork is an isolated working copy that reconciles with this cursor
 * through a merge function supplied to each sync. Cursors carry no lattice, context or signing policy: the merge function
 * closes over whatever policy its owner chooses. A short atomic edit is a
 * {@link Transactions} draft, and a detached snapshot is simply
 * {@code Root.create(cursor.get())}.
 *
 * @param <V> Value type
 */
public abstract class AForkableCursor<V extends ACell> extends ACursor<V> {

	/**
	 * Presents a caller-supplied value in place of absent storage, without creating
	 * state. Update functions see that default too; unchanged writes keep storage
	 * absent. Writing null clears storage and restores the default view.
	 *
	 * @param defaultValue Non-null default, for example an empty map
	 * @return Live cursor with the explicit default
	 */
	public AForkableCursor<V> withDefault(V defaultValue) {
		return new DefaultCursor<>(this,defaultValue);
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

	/** Creates isolated working storage which publishes here when explicitly synced. */
	public ForkedCursor<V> fork() {
		return ForkedCursor.create(this);
	}

	/**
	 * Returns the current value. Ordinary cursors, including paths and transformed
	 * views over forks, never publish enclosing storage or invoke the merge function.
	 * ForkedCursor overrides this to reconcile at its own destination.
	 */
	public V sync(BinaryOperator<V> merge) {
		return get();
	}
}
