package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.PathCursor;

/**
 * Optional adapter whose incoming merge, path reduction and publication use a supplied lattice.
 * Useful for simple values and caller-supplied lattice implementations. Domain
 * components normally extend {@link ALatticeComponent} and call reusable lattice
 * rules only where their operations need them. A staged adapter publishes only at
 * its destination cursor; a live view writes there immediately.
 *
 * @param <V> Value at this component's position
 */
public class LatticeComponent<V extends ACell> extends ALatticeComponent<V> {
	private final ALattice<V> lattice;
	private volatile LatticeContext context=LatticeContext.EMPTY;

	/** Optional policy for the supplied lattice implementation, local to this adapter. */
	public final LatticeContext getContext() { return context; }

	/** Configures this adapter only; component bases and hosts carry no context. */
	public final void setContext(LatticeContext context) {
		this.context=(context==null)?LatticeContext.EMPTY:context;
	}

	/** Binds supplied working storage and optional policy for the lattice adapter. */
	public LatticeComponent(ALattice<V> lattice,AForkableCursor<V> cursor,LatticeContext context) {
		this(lattice,cursor);
		setContext(context);
	}

	/** Binds supplied working storage without creating a fork. */
	public LatticeComponent(ALattice<V> lattice,AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice,"Component lattice must not be null");
	}

	@Override
	public LatticeComponent<V> rebind(AForkableCursor<V> cursor) {
		return new LatticeComponent<>(lattice,cursor,context);
	}

	@Override
	public LatticeComponent<V> fork() {
		return rebind(storage.fork());
	}

	/** Binds a component directly to the supplied cursor. */
	public static <V extends ACell> LatticeComponent<V> live(ALattice<V> lattice,
			AForkableCursor<V> cursor,LatticeContext context) {
		return new LatticeComponent<>(lattice,cursor,context);
	}

	/** Returns the optional lattice implementation used by this adapter. */
	public ALattice<V> getLattice() {
		return lattice;
	}

	@Override
	public V merge(V incoming) {
		return cursor().updateAndGet(own -> lattice.merge(getContext(),own,incoming));
	}

	/** Reconciles declared lattice values while preserving unrelated destination keys. */
	@Override
	protected V mergePublication(V own,V other) {
		return LatticeOps.mergePublication(lattice,getContext(),own,other);
	}

	@Override
	protected V mergeAtPath(ACell value,ACell... path) {
		V reduced=LatticeOps.reduce(lattice,value,path);
		return reduced==null?cursor().getOrDefault(null):merge(reduced);
	}

	/**
	 * Builds a cursor at a path of lattice keys, one {@link PathCursor} per key. Each
	 * reads as any path does: a missing key is absent, so a required read throws.
	 * Each write uses the lattice at its level to construct an absent container
	 * within the atomic update. Reads and view construction never mutate state.
	 * A missing container at an unknown level causes the write to fail. Owner
	 * boundaries are not crossed here: an owner's slot is wrapped by its component with
	 * an explicitly bound signing function.
	 *
	 * @param <T> Value type at the end of the path
	 * @param cursor Cursor to navigate from
	 * @param lattice Lattice describing the value at {@code cursor}, or null for none
	 * @param path Keys to navigate
	 * @return Cursor at the path
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AForkableCursor<T> resolve(AForkableCursor<?> cursor,
			ALattice<?> lattice, ACell... path) {
		AForkableCursor<ACell> c=(AForkableCursor<ACell>)cursor;
		ALattice<?> lat=lattice;
		for (ACell key: path) {
			c=new LatticeStep(c,lat,key);
			lat=(lat==null)?null:lat.path(key);
		}
		return (AForkableCursor<T>)c;
	}

	/** One key of a lattice path: reads as a path, and constructs an absent container from its lattice. */
	private static final class LatticeStep extends PathCursor<ACell> {
		private final ALattice<?> lattice;
		private final ACell[] step;

		LatticeStep(AForkableCursor<ACell> source,ALattice<?> lattice,ACell key) {
			super(source,key);
			this.lattice=lattice;
			this.step=new ACell[] {key};
		}

		/** Creates its entry on a changed write, so only a container it cannot build or use refuses it. */
		@Override protected void requireWritable(ACell stored) {
			write(stored,view(stored));
		}

		@Override protected ACell write(ACell current,ACell value) {
			return LatticeOps.assocIn(current,value,lattice,step);
		}
	}
}
