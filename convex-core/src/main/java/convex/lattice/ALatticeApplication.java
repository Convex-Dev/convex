package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;

/**
 * Base class for an application participating in a lattice root.
 *
 * <p>An application component works at the same cursor position as its
 * {@link RootComponent} host and declares the root lattice, which defines the
 * complete root merge, including which regions participate. Merging requires no
 * domain components or component registry. Subclasses expose lightweight
 * components at paths within that root, each declaring the shape of the level it
 * hands out.</p>
 *
 * <p>Each application has a live cursor view with an independent context override.
 * Setting its cursor context affects its descendants, not the host or other
 * applications. Without an override it inherits the host's context live. All views
 * share the same atomic state; only an explicit fork creates a working copy.</p>
 *
 * <p>Applications on the global lattice use its keyed map root and compose the
 * region lattices they understand. The base also accepts other lattice value
 * types for local applications and tests.</p>
 *
 * <p>The application does not own the root, store or replication lifecycle.
 * Calling {@link #sync()} invokes the host's root publication policy; physical
 * durability is the host's {@link RootComponent#flush()}.</p>
 *
 * @param <V> Complete application root value type
 */
public abstract class ALatticeApplication<V extends ACell> extends ALatticeComponent<V> {

	private final ALattice<V> lattice;

	/**
	 * Creates an application over an existing lattice host, sharing its root lattice.
	 *
	 * @param host Root component providing shared state, lattice and host policy
	 */
	protected ALatticeApplication(RootComponent<V> host) {
		this(requireHost(host).getLattice(),host.cursor().view());
	}

	/**
	 * Binds an application view to a working cursor, such as a fork or a
	 * transaction draft. Rebinding factories rebuild child components against the
	 * new view rather than reusing children bound to the live cursor.
	 *
	 * @param lattice Root lattice: the complete merge for this application's root
	 * @param cursor Cursor at the complete application root
	 */
	protected ALatticeApplication(ALattice<V> lattice, AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice,"Application lattice must not be null");
	}

	private static <V extends ACell> RootComponent<V> requireHost(RootComponent<V> host) {
		if (host==null) throw new IllegalArgumentException("Application host must not be null");
		return host;
	}

	/**
	 * Returns the root lattice used by both the application and its host.
	 *
	 * @return Root merge and navigation semantics
	 */
	public final ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Returns a cursor at a path within the application root, with each level's
	 * shape declared from the root lattice so that writes can create absent regions.
	 * This is the typed navigation an application offers over its root; owner slots
	 * below it are crossed by the component owning that level.
	 *
	 * @param <T> Value type at the path
	 * @param path Keys from the application root
	 * @return Cursor at the path
	 */
	public <T extends ACell> AForkableCursor<T> resolve(ACell... path) {
		return resolve(cursor,lattice,path);
	}

	/**
	 * Atomically merges an incoming root value into the live application state.
	 *
	 * <p>Merges with the root lattice under the cursor's effective context, with the
	 * current local value in the own position. The lattice handles all region
	 * dispatch and verification; no components need to have been constructed.
	 * Concurrent writes are reconciled by retrying against the latest root. If merge
	 * throws, this operation installs no state.</p>
	 *
	 * <p>This changes local state only. Call {@link #sync()} to persist and publish
	 * it through the host.</p>
	 *
	 * @param other Incoming root value
	 * @return Root value installed by this merge
	 */
	public V merge(V other) {
		return cursor.merge(lattice,other);
	}
}
