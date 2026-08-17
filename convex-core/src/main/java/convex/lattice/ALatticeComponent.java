package convex.lattice;

import java.io.IOException;

import convex.core.data.ACell;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Abstract base class for lattice application components that wrap a cursor.
 *
 * <p>A lattice component provides domain-specific access to a section of
 * lattice state. It wraps an {@link ALatticeCursor} and may have a parent
 * component representing its containing application policy. This relationship
 * need not be the cursor's logical parent: a fork, for example, keeps the same
 * containing component while its cursor synchronises to the original cursor.</p>
 *
 * <p>Implementations should create appropriate domain-specific getters
 * and setters rather than exposing raw {@code get()} / {@code set()}
 * on the cursor. This ensures type safety and encapsulates the
 * underlying lattice structure.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	private final ALatticeComponent<?> parent;
	protected final ALatticeCursor<V> cursor;

	/**
	 * Creates a standalone root component with no component parent.
	 *
	 * <p>Persistence is a no-op unless the root component overrides
	 * {@link #persist(ACell)}.</p>
	 *
	 * @param cursor Cursor wrapped by this component
	 */
	protected ALatticeComponent(ALatticeCursor<V> cursor) {
		this(null,cursor);
	}

	/**
	 * Creates a component nested under a parent component.
	 *
	 * @param parent Parent component providing containing application policy
	 * @param cursor Cursor wrapped by this component
	 */
	protected ALatticeComponent(ALatticeComponent<?> parent, ALatticeCursor<V> cursor) {
		if (cursor==null) throw new IllegalArgumentException("Component cursor must not be null");
		this.parent=parent;
		this.cursor = cursor;
	}

	/**
	 * Returns the containing policy component, if this is a nested component.
	 *
	 * @return Parent component, or {@code null} for a standalone root
	 */
	protected final ALatticeComponent<?> parent() {
		return parent;
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
	 * Persists a value using the policy supplied by the containing component.
	 *
	 * <p>The default implementation delegates to the parent component. A root
	 * component with no parent returns the value unchanged. Hosting components
	 * may override this method to persist into an underlying store.</p>
	 *
	 * <p>Persistence does not update or sync this component's cursor. Callers
	 * that need store-backed references in their working state must install the
	 * returned value explicitly.</p>
	 *
	 * @param <T> Cell type
	 * @param value Value to persist
	 * @return Persisted value, potentially containing store-backed references
	 * @throws IOException If persistence fails
	 */
	protected <T extends ACell> T persist(T value) throws IOException {
		if (parent==null) return value;
		return parent.persist(value);
	}

	/**
	 * Persists this component's current value without changing its cursor.
	 *
	 * @return Persisted current value
	 * @throws IOException If persistence fails
	 */
	public V persist() throws IOException {
		return persist(cursor.get());
	}

	/**
	 * Syncs this component's cursor back to its parent.
	 *
	 * <p>For forked cursors, this merges local changes into the parent using
	 * lattice merge semantics. Persistence, publication and propagation are
	 * separate application responsibilities.</p>
	 */
	public void sync() {
		cursor.sync();
	}
}
