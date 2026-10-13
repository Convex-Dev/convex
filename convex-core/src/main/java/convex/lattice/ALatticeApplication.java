package convex.lattice;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.store.AStore;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Base class for an application participating in a lattice root.
 *
 * <p>An application component works at the same cursor position as its
 * {@link RootComponent} parent. The supplied lattice defines the complete root
 * merge, including which regions participate. Merging requires no domain
 * components or component registry. Subclasses may expose lightweight components
 * at paths within that root, while persistence delegates to the host.</p>
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
 * Calling {@link #sync()} invokes the host's root publication policy;
 * {@link #flush()} remains a distinct durability boundary.</p>
 *
 * @param <V> Complete application root value type
 */
public abstract class ALatticeApplication<V extends ACell> extends ALatticeComponent<V> {

	private final RootComponent<V> host;

	/**
	 * Creates an application over an existing lattice host.
	 *
	 * @param host Root component providing shared state, lattice and host policy
	 */
	protected ALatticeApplication(RootComponent<V> host) {
		this(host,requireHost(host).cursor().view());
	}

	/**
	 * Binds an application view to an explicit working cursor, retaining its host's
	 * storage policy. Subclass transaction factories use this constructor and must
	 * rebuild their child components against this cursor.
	 */
	protected ALatticeApplication(RootComponent<V> host,ALatticeCursor<V> cursor) {
		super(requireHost(host),cursor);
		this.host=host;
	}

	/**
	 * Creates a local application at the supplied lattice's zero value.
	 *
	 * <p>The caller owns the store. Construction does not restore its retained
	 * root; {@link #sync()} publishes the new application state. To restore an
	 * existing root, pass a host created with
	 * {@link RootComponent#open(ALattice, AStore)} instead.</p>
	 *
	 * @param lattice Root merge and navigation semantics
	 * @param store Store providing persistence and publication
	 */
	protected ALatticeApplication(ALattice<V> lattice, AStore store) {
		this(RootComponent.create(lattice,store));
	}

	private static <V extends ACell> RootComponent<V> requireHost(RootComponent<V> host) {
		if (host==null) throw new IllegalArgumentException("Application host must not be null");
		return host;
	}

	/**
	 * Returns the application host for specialised application infrastructure.
	 *
	 * @return Hosted root component
	 */
	protected final RootComponent<V> host() {
		return host;
	}

	/**
	 * Returns the root lattice used by both the application and its host.
	 *
	 * @return Root merge and navigation semantics
	 */
	public final ALattice<V> getLattice() {
		return cursor.getLattice();
	}

	/**
	 * Atomically merges an incoming root value into the live application state.
	 *
	 * <p>Delegates to the root cursor with its effective context and the current
	 * local value in the own position. The lattice handles all region dispatch
	 * and verification; no components need to have been constructed. Concurrent
	 * writes are reconciled by retrying against the latest root. If merge throws,
	 * this operation installs no state.</p>
	 *
	 * <p>This changes local state only. Call {@link #sync()} to persist and publish
	 * it through the host, and {@link #flush()} for physical durability.</p>
	 *
	 * @param other Incoming root value
	 * @return Root value installed by this merge
	 */
	public V merge(V other) {
		return cursor.merge(other);
	}

	/**
	 * Requests the host store's physical durability barrier.
	 *
	 * <p>Call {@link #sync()} first when working state must become the selected,
	 * published root. Flush deliberately does not move or merge any cursor.</p>
	 *
	 * @throws IOException If the store flush fails
	 */
	public void flush() throws IOException {
		host.flush();
	}
}
