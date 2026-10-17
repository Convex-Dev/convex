package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;

/**
 * Component at one level whose merge is its lattice's default merge. An application
 * hands one out for a path it has no component of its own for, so a delivery there
 * merges with the lattice the application declares at that level, under the
 * application's context.
 *
 * @param <V> Value type at this level
 */
public final class LatticeComponent<V extends ACell> extends ALatticeComponent<V> {

	private final ALattice<V> lattice;

	/** Creates a live view over a cursor, merging with the given lattice. */
	public LatticeComponent(ALattice<V> lattice, AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice,"Component lattice must not be null");
	}

	/** Returns the lattice for this level. */
	public ALattice<V> getLattice() {
		return lattice;
	}

	/** Delegates to the lattice merge under this component's context. */
	@Override
	public V merge(V other) {
		return cursor.updateAndGet(own -> lattice.merge(getContext(),own,other));
	}
}
