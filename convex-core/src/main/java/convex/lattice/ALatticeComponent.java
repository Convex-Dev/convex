package convex.lattice;

import convex.core.data.ACell;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Abstract base class for lattice application components that wrap a cursor.
 *
 * <p>A lattice component provides domain-specific access to a section of
 * lattice state. It wraps an {@link ALatticeCursor} and exposes
 * {@link #cursor()} for direct lattice operations and {@link #sync()}
 * for propagating changes back to the parent cursor.</p>
 *
 * <p>Implementations should create appropriate domain-specific getters
 * and setters rather than exposing raw {@code get()} / {@code set()}
 * on the cursor. This ensures type safety and encapsulates the
 * underlying lattice structure.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	protected final ALatticeCursor<V> cursor;

	protected ALatticeComponent(ALatticeCursor<V> cursor) {
		this.cursor = cursor;
	}

	/**
	 * Returns the underlying lattice cursor for direct operations.
	 *
	 * @return The cursor wrapped by this component
	 */
	public ALatticeCursor<V> cursor() {
		return cursor;
	}

	/**
	 * Syncs this component's cursor back to its parent.
	 *
	 * <p>For forked cursors, this merges local changes into the parent
	 * using CRDT lattice merge semantics. For root or connected cursors,
	 * this handles persistence and broadcast.</p>
	 */
	public void sync() {
		cursor.sync();
	}
}
