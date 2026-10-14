package convex.lattice;

import java.io.IOException;

import convex.core.data.ACell;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Base class for an application participating in a lattice root.
 *
 * <p>An application component works at the same cursor position as its
 * {@link RootComponent} host. The supplied lattice defines the complete root
 * merge, including which regions participate. Merging requires no domain
 * components or component registry. Subclasses may expose lightweight components
 * at paths within that root, with storage operations routed through the host cursor.</p>
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

	/**
	 * Creates an application over an existing lattice host.
	 *
	 * @param host Root component providing shared state, lattice and host policy
	 */
	protected ALatticeApplication(RootComponent<V> host) {
		this(host,requireHost(host).cursor().view());
	}

	/**
	 * Binds an application view to a working cursor and copies configuration from
	 * the source component. Factories pass this application as source when rebinding
	 * and rebuild children against the new view. No source component is retained.
	 */
	protected ALatticeApplication(ALatticeComponent<V> source,ALatticeCursor<V> cursor) {
		super(java.util.Objects.requireNonNull(source),cursor);
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
		cursor.flush();
	}
}
