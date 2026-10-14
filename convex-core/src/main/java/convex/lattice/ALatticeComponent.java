package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Domain operations over a lattice cursor at one path.
 *
 * <p>A component holds exactly one cursor and nothing else. State changes, forks and
 * sync travel through that cursor; there is no component parent, host or store link.
 * Persistence belongs to the host ({@link RootComponent}) and to components which
 * explicitly own a store for their own data, such as DLFS drives.</p>
 *
 * @param <V> Value type of the wrapped cursor
 */
public abstract class ALatticeComponent<V extends ACell> {

	protected final ALatticeCursor<V> cursor;

	/** Creates a component over a cursor. */
	protected ALatticeComponent(ALatticeCursor<V> cursor) {
		this.cursor=Objects.requireNonNull(cursor,"Component cursor must not be null");
	}

	/** Returns the cursor responsible for all state updates and sync. */
	public ALatticeCursor<V> cursor() {
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
}
