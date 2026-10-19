package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;

/**
 * Standard component whose incoming merge delegates to its supplied lattice.
 * Use directly as a region or extend with domain operations. A staged component
 * publishes only at its destination cursor; a live view writes there immediately.
 *
 * @param <V> Value at this component's position
 */
public class LatticeComponent<V extends ACell> extends ALatticeComponent<V> {
	private final ALattice<V> lattice;

	/** Creates private working state, published to the destination by sync. */
	public LatticeComponent(ALattice<V> lattice,ACursor<V> destination,LatticeContext context) {
		super(lattice,destination);
		this.lattice=lattice;
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

	@Override
	public ALattice<V> getLattice() {
		return lattice;
	}

	@Override
	public V merge(V incoming) {
		return cursor.updateAndGet(own -> lattice.merge(getContext(),own,incoming));
	}
}
