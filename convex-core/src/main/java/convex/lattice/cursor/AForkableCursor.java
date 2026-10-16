package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * Mutable cursors whose paths are themselves mutable cursors.
 * Cursors neither fork nor sync: an isolated working copy is a component with its
 * own working state, which reconciles with its destination under the component's
 * lattice, and a short atomic edit is a {@link Transactions} draft. A detached
 * snapshot is simply {@code Root.create(cursor.get())}.
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
}
