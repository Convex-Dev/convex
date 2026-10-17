package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/**
 * Base class for an application participating in a lattice root.
 *
 * <p>An application component works at the same cursor position as its
 * {@link LatticeHost} host and declares the root lattice: the shape of the root,
 * including which regions participate, with a default merge for that shape. The
 * application owns {@link #merge}: it delegates to the lattice merge, for the whole
 * root or for the parts it wants, or applies its own rule. Subclasses expose
 * lightweight components at paths within the root, each declaring the shape of the
 * level it hands out.</p>
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
	 * Creates private working state over a host. The host delegates incoming values
	 * to this application's {@link #merge}; it does not independently accept values
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
	 * @param lattice Root lattice: the shape of this application's root and its default merge
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
	 * @return Shape of the root, its navigation and its default merge
	 */
	public final ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Application-defined preparation for publication, normally syncing the
	 * components that publish through their own forks of the host before this
	 * application publishes any root-level state of its own. No generic registry
	 * is maintained. Failure prevents this call from publishing the application;
	 * component publications already completed stand. The hook may be called
	 * concurrently and must use thread-safe component APIs. Call application
	 * {@code sync()} to run this lifecycle: syncing an individual component or the
	 * exposed cursor does not invoke this hook. This hook does not make the
	 * publications of several independently editable components one atomic
	 * transaction.
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
	 * Merges an incoming root value into this application's working state. The
	 * lattice offers a default merge for its shape; this application decides whether
	 * to delegate to it, for the whole root or for the parts it wants, or to apply its
	 * own rule. Delegating is one atomic update of the working cursor:
	 *
	 * <pre>
	 * return cursor.updateAndGet(own -&gt; getLattice().merge(getContext(),own,other));
	 * </pre>
	 *
	 * <p>This changes local state only. Call {@link #sync()} to publish to the
	 * destination, then sync the host when persistence or propagation is required.</p>
	 *
	 * @param other Incoming root value
	 * @return Current application working root
	 */
	public abstract V merge(V other);

	/**
	 * Delivers a value at a path within this application's root as the partial root
	 * that holds it: the value at its path inside the empty containers the lattice
	 * declares along the way, handed to {@link #merge} like any other root. One merge
	 * therefore sees every delivery. A path the lattice does not declare, or cannot
	 * build containers for, delivers nothing, just as an undeclared key in a root
	 * value contributes nothing.
	 *
	 * @param value Incoming value at the path
	 * @param path Keys from the application root
	 * @return Current application working root, not a publication acknowledgement
	 */
	@SuppressWarnings("unchecked")
	public final V mergeAt(ACell value, ACell... path) {
		if (path.length==0) return merge((V)value);
		ALattice<?>[] levels=new ALattice<?>[path.length];
		ALattice<?> level=lattice;
		for (int i=0; i<path.length; i++) {
			levels[i]=level;
			level=level.path(path[i]);
			if (level==null) return cursor.get();
		}
		ACell partial=value;
		for (int i=path.length-1; i>=0; i--) {
			if (!(levels[i].zero() instanceof ADataStructure<?> empty)) return cursor.get();
			partial=RT.assoc((ADataStructure<ACell>)empty,path[i],partial);
			if (partial==null) return cursor.get();
		}
		return merge((V)partial);
	}
}
