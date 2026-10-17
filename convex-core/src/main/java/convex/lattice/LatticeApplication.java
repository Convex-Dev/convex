package convex.lattice;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;

/**
 * Standard application whose merge is its lattice's default merge, applied to every
 * incoming root. Use directly when no custom domain operations or input selection
 * are needed. Private applications publish explicitly; a live application is
 * suitable for a relay whose accepted merges should immediately become host state.
 *
 * @param <V> Application root value type
 */
public final class LatticeApplication<V extends ACell> extends ALatticeApplication<V> {

	/** Creates private working state, published to the host by {@link #sync()}. */
	public LatticeApplication(ALattice<V> lattice,LatticeHost<V> host) {
		super(lattice,host);
	}

	private LatticeApplication(ALattice<V> lattice,AForkableCursor<V> cursor) {
		super(lattice,cursor);
	}

	/** Creates a live application; the caller configures it on the host explicitly. */
	public static <V extends ACell> LatticeApplication<V> live(ALattice<V> lattice,LatticeHost<V> host) {
		var application=new LatticeApplication<>(lattice,host.cursor());
		application.setContext(host.getContext());
		return application;
	}

	/** Delegates to the lattice merge under this application's context. */
	@Override
	public V merge(V other) {
		return cursor.updateAndGet(own -> getLattice().merge(getContext(),own,other));
	}
}
