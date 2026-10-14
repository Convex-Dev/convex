package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;

/**
 * Domain operations over a cursor at one path.
 *
 * <p>A component holds exactly one cursor and nothing else. State changes, forks and
 * sync travel through that cursor; there is no component parent, host or store link.
 * The cursor knows nothing about lattices: the component declares the structure of
 * the level it owns, typically by passing its lattice as the {@link Shape} of child
 * cursors, the {@link Merge} of forks, and the {@link Signing} or {@link Stamping}
 * rule of a boundary cursor it wraps.</p>
 *
 * <p>Persistence belongs to the host ({@link RootComponent}) and to components which
 * explicitly own a store for their own data, such as DLFS drives.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	protected final AForkableCursor<V> cursor;

	/** Creates a component over a cursor. */
	protected ALatticeComponent(AForkableCursor<V> cursor) {
		this.cursor=Objects.requireNonNull(cursor,"Component cursor must not be null");
	}

	/** Returns the cursor responsible for all state updates and sync. */
	public AForkableCursor<V> cursor() {
		return cursor;
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
	 * durability remains the host's separate {@link RootComponent#flush()}.</p>
	 */
	public void sync() {
		cursor.sync();
	}

	/**
	 * Navigates from a cursor along a path, declaring the shape of each level from
	 * the corresponding node of a lattice, so that a write through the result can
	 * create absent intermediates with their correct types. A level the lattice does
	 * not describe is navigated untyped. Owner boundaries are not crossed here: an
	 * owner's slot is wrapped by the component owning that level, with
	 * {@link convex.lattice.cursor.SignedCursor#forOwner}.
	 *
	 * @param <T> Value type at the end of the path
	 * @param cursor Cursor to navigate from
	 * @param lattice Lattice describing the value at {@code cursor}, or null for none
	 * @param path Keys to navigate
	 * @return Cursor at the path
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	protected static <T extends ACell> AForkableCursor<T> resolve(AForkableCursor<?> cursor,
			ALattice<?> lattice, ACell... path) {
		AForkableCursor c=cursor;
		ALattice lat=lattice;
		for (ACell key: path) {
			lat=(lat==null)?null:lat.path(key);
			c=(lat==null)?c.path(key):c.child(key,lat);
		}
		return c;
	}
}
