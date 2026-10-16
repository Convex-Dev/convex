package convex.lattice;

import java.util.Map;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.AKeyedLattice;

/**
 * Base class for an application participating in a lattice root.
 *
 * <p>An application component works at the same cursor position as its
 * {@link LatticeHost} host and declares the root lattice, which defines the
 * complete root merge, including which regions participate. Merging requires no
 * domain components or component registry. Subclasses expose lightweight
 * components at paths within that root, each declaring the shape of the level it
 * hands out.</p>
 *
 * <p>The host constructor creates private working state, with context resolved at
 * construction. Lightweight children share that state. The explicit lattice/cursor
 * constructor adopts the supplied working state, including a live view
 * when immediate visibility is required.</p>
 *
 * <p>Applications on the global lattice use its keyed map root and compose the
 * region lattices they understand. The base also accepts other lattice value
 * types for local applications and tests.</p>
 *
 * <p>The application does not own the root, store or replication lifecycle.
 * Calling {@link #sync()} first runs application-defined preparation, then publishes
 * into its destination cursor. For private working state this stops at the host;
 * host sync and physical durability ({@link LatticeHost#flush()}) are separate.</p>
 *
 * @param <V> Complete application root value type
 */
public abstract class ALatticeApplication<V extends ACell> extends ALatticeComponent<V> {

	private final ALattice<V> lattice;

	/**
	 * Creates private working state with application-specific merge semantics.
	 * The host delegates incoming values; it does not independently accept values
	 * this application ignores. The caller must supply compatible value structure.
	 */
	protected ALatticeApplication(ALattice<V> lattice,LatticeHost<V> host) {
		super(lattice,requireHost(host).cursor());
		this.lattice=lattice;
		setContext(host.getContext());
	}

	/**
	 * Binds an application view to a working cursor, such as another component's
	 * working state or a transaction draft. Rebinding factories rebuild child components against the
	 * new view rather than reusing children bound to the live cursor.
	 *
	 * @param lattice Root lattice: the complete merge for this application's root
	 * @param cursor Cursor at the complete application root
	 */
	protected ALatticeApplication(ALattice<V> lattice, AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice,"Application lattice must not be null");
	}

	private static <V extends ACell> LatticeHost<V> requireHost(LatticeHost<V> host) {
		if (host==null) throw new IllegalArgumentException("Application host must not be null");
		return host;
	}

	/**
	 * Returns this application's root lattice.
	 *
	 * @return Root merge and navigation semantics
	 */
	public final ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Application-defined preparation for publication, normally checkpointing owned
	 * signing components into this application's working cursor. No generic registry
	 * is maintained. Failure prevents this call from publishing the application;
	 * child checkpoints already completed remain in its working state for retry.
	 * The hook may be called concurrently and must use thread-safe component APIs.
	 * Call application {@code sync()} to run this lifecycle: syncing an individual
	 * child or the exposed cursor does not invoke this hook. This hook does not make
	 * checkpoints of multiple independently editable children one atomic transaction.
	 */
	protected void beforeSync() {
	}

	/** Runs application preparation, then publishes only this component's working boundary. */
	@Override
	public V sync() {
		beforeSync();
		return super.sync();
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
	 * Merges an incoming root value into the application state. The default is an
	 * atomic lattice merge. Overrides may instead delegate accepted subtrees to
	 * their components and discard other input, with no additional host merge.
	 *
	 * <p>The default merges with this application's lattice under the component's
	 * explicit context, with the current local value in the own position. The lattice handles all region
	 * dispatch and verification; no components need to have been constructed.
	 * Concurrent writes are reconciled by retrying against the latest root. If merge
	 * throws, this default operation installs no state. Overrides delegating to
	 * independently editable children need not be atomic across those children.</p>
	 *
	 * <p>This changes local state only. Call {@link #sync()} to publish to the
	 * destination, then sync the host when persistence or propagation is required.</p>
	 *
	 * @param other Incoming root value
	 * @return Root value installed by this merge
	 */
	public V merge(V other) {
		return cursor.updateAndGet(own -> lattice.merge(getContext(),own,other));
	}

	/**
	 * Reconciles a staged publication so that only the regions this application's
	 * lattice declares are published. Declared regions merge with local edits as
	 * own; every other region adopts the destination. An application sharing a host
	 * therefore never overwrites or removes regions it does not understand with the
	 * stale copies held in its working state, and after publication its working
	 * state carries the host's current value for those regions. Roots that are not
	 * keyed maps use the plain lattice merge.
	 */
	@Override
	@SuppressWarnings("unchecked")
	protected V mergePublication(ALattice<V> lattice,V own,V other) {
		V merged=lattice.merge(getContext(),own,other);
		if (!(lattice instanceof AKeyedLattice<?,?> keyed) || !(other instanceof AMap<?,?>)) return merged;
		if (merged==null) return other;
		AMap<ACell,ACell> result=(AMap<ACell,ACell>)other;
		for (Map.Entry<ACell,ACell> entry:((AMap<ACell,ACell>)merged).entrySet()) {
			if (keyed.path(entry.getKey())!=null) result=result.assoc(entry.getKey(),entry.getValue());
		}
		return (V)result;
	}

	/**
	 * Delivers an incoming subtree at a path relative to this application's root.
	 * The default calls {@link #merge} for an empty path and ignores all other
	 * paths, even when the lattice declares them. Applications override this hook
	 * to select their interests, verify input and delegate to domain components.
	 * Knowing a path's type does not imply interest in receiving updates there.
	 * Overrides must verify enclosing signed envelopes before accepting interior
	 * data. The path is borrowed read-only for the duration of the call.
	 * No sync or preparation hook is invoked automatically.
	 *
	 * @return Current application working root, not a publication acknowledgement
	 */
	@SuppressWarnings("unchecked")
	public V mergeAt(ACell value, ACell... path) {
		if (path.length==0) return merge((V)value);
		return cursor.get();
	}
}
