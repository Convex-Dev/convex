package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ProjectionCursor;

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

	/** Creates private working state, published to the destination by sync. */
	public LatticeComponent(ALattice<V> lattice,ACursor<V> destination,LatticeContext context) {
		super(destination);
		this.lattice=Objects.requireNonNull(lattice,"Component lattice must not be null");
		setContext(context);
	}

	/** Creates a live view over caller-supplied working storage. */
	protected LatticeComponent(ALattice<V> lattice,AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice,"Component lattice must not be null");
	}

	/** Creates a live component, with no independent publication boundary. */
	public static <V extends ACell> LatticeComponent<V> live(ALattice<V> lattice,
			AForkableCursor<V> cursor,LatticeContext context) {
		var component=new LatticeComponent<>(lattice,cursor);
		component.setContext(context);
		return component;
	}

	/** Returns the optional lattice implementation used by this adapter. */
	public ALattice<V> getLattice() {
		return lattice;
	}

	@Override
	public V merge(V incoming) {
		return cursor.updateAndGet(own -> lattice.merge(getContext(),own,incoming));
	}

	/** Reconciles declared lattice values while preserving unrelated destination keys. */
	@Override
	protected V mergePublication(V own,V other) {
		return LatticeOps.mergePublication(lattice,getContext(),own,other);
	}

	@Override
	protected V mergeAtPath(ACell value,ACell... path) {
		V reduced=LatticeOps.reduce(lattice,value,path);
		return reduced==null?cursor.get():merge(reduced);
	}


	/**
	 * Builds child views with component-supplied read/write functions. Each write
	 * function uses the corresponding lattice to construct an absent container
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
			final ALattice<?> parentLattice=lat;
			final ACell[] step={key};
			c=ProjectionCursor.create(c,
				value -> RT.getIn(value,step),
				(value,child) -> Utils.equals(RT.getIn(value,step),child)
					?value:LatticeOps.assocIn(value,child,parentLattice,step));
			lat=(lat==null)?null:lat.path(key);
		}
		return (AForkableCursor<T>)c;
	}
}
