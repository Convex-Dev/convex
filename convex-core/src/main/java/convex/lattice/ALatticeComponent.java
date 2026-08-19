package convex.lattice;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.store.AStore;
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
	 * <p>Persistence fails unless the component overrides
	 * {@link #persist(ACell)} with a concrete storage policy.</p>
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
	 * Returns the physical store supplied by the containing host, if any.
	 *
	 * <p>This is an internal component resource: application factories should
	 * continue to accept components rather than stores. Standalone components
	 * which are not attached to a {@link RootComponent} return {@code null}.</p>
	 *
	 * @return Host store, or {@code null} when this component is not store-backed
	 */
	protected AStore store() {
		return (parent==null)?null:parent.store();
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
	 * <p>The default implementation delegates to the parent component. A component
	 * with no parent and no concrete storage policy fails rather than pretending
	 * that the value was persisted. Hosting components may override this method to
	 * persist into an underlying store.</p>
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
		if (parent==null) {
			throw new IllegalStateException("No store-backed component available for persistence");
		}
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
	 * Synchronises this component's cursor through its immediate cursor boundary.
	 *
	 * <p>For a forked cursor this merges local changes into the cursor it was forked
	 * from; it does not necessarily publish the complete application root. Call
	 * the containing {@link ALatticeApplication application's} {@code sync()} when
	 * the merged state should cross the host publication boundary.</p>
	 *
	 * <p>A sync invoked on an application or another live path reaches the hosted
	 * root and runs its synchronous persistence and publication policy. Physical
	 * durability remains the separate application {@code flush()} operation.</p>
	 */
	public void sync() {
		cursor.sync();
	}
}
