package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Standard application accepting the complete supplied lattice and its paths.
 * Use directly when no custom domain operations or input selection are needed.
 * Private applications publish explicitly; a live application is suitable for a
 * relay whose accepted merges should immediately become host state.
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

	/** Accepts declared paths, requiring complete signed envelopes at ownership boundaries. */
	@Override
	@SuppressWarnings("unchecked")
	public V mergeAt(ACell value,ACell... path) {
		if (path.length==0) return merge((V)value);
		ALattice<?> target=getLattice();
		for (int i=0;i<path.length;i++) {
			if (target instanceof SignedLattice<?>) {
				throw new IllegalArgumentException("Incoming path must include the complete signed envelope");
			}
			// Legacy owner maps bind their verification predicate through the context.
			if (i==path.length-1 && target instanceof OwnerLattice<?> && value instanceof SignedData<?> signed
					&& !getContext().verifyOwner(path[i],signed.getAccountKey())) return cursor.get();
			target=target.path(path[i]);
			if (target==null) throw new IllegalArgumentException("Path is outside the served lattice");
		}
		ALattice<ACell> lattice=(ALattice<ACell>)target;
		this.<ACell>resolve(path).updateAndGet(own -> lattice.merge(getContext(),own,value));
		return cursor.get();
	}
}
