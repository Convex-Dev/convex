package convex.lattice.cursor;

import java.util.function.BinaryOperator;

import convex.core.data.ACell;

/**
 * Mutable cursors whose paths are themselves mutable cursors and which can be
 * forked. A fork is an isolated working copy that reconciles with this cursor
 * through a merge function supplied when it is created; there is no fork without
 * one. Cursors carry no lattice, context or signing policy: the merge function
 * closes over whatever policy its owner chooses. A short atomic edit is a
 * {@link Transactions} draft, and a detached snapshot is simply
 * {@code Root.create(cursor.get())}.
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
	 * Creates an isolated working copy of this cursor. Its {@link ForkedCursor#sync()}
	 * publishes the working snapshot here by compare-and-set, applying {@code merge}
	 * with the working snapshot as own whenever this cursor has moved in between. An
	 * unchanged working copy adopts this cursor's value instead.
	 *
	 * @param merge Merge of own and other, typically a lattice merge under a context
	 * @return Working copy initialised to this cursor's current value
	 */
	public ForkedCursor<V> fork(BinaryOperator<V> merge) {
		return ForkedCursor.create(this,Reconciler.of(merge));
	}
}
