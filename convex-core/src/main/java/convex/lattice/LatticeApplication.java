package convex.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.cursor.AForkableCursor;

/**
 * Optional application adapter for an explicitly supplied root lattice. Use directly
 * for its default merge, or extend when custom input selection still uses the supplied
 * lattice. Ordinary applications extend {@link ALatticeApplication} or compose
 * regions instead. Private applications publish explicitly; a live application is
 * suitable for a relay whose accepted merges should immediately become host state.
 *
 * @param <V> Application root value type
 */
public class LatticeApplication<V extends ACell> extends ALatticeApplication<V> {

	private final ALattice<V> lattice;
	private volatile LatticeContext context=LatticeContext.EMPTY;

	/** Optional policy for the supplied lattice implementation, local to this adapter. */
	public final LatticeContext getContext() { return context; }

	/** Configures this adapter only; component bases and hosts carry no context. */
	public final void setContext(LatticeContext context) {
		this.context=(context==null)?LatticeContext.EMPTY:context;
	}

	/** Creates private working state, published to the host by {@link #sync()}. */
	public LatticeApplication(ALattice<V> lattice,LatticeHost<V> host) {
		super(host);
		this.lattice=Objects.requireNonNull(lattice,"Application lattice must not be null");
	}

	/** Shares working storage while opting into the supplied lattice implementation. */
	protected LatticeApplication(ALattice<V> lattice,AForkableCursor<V> cursor) {
		super(cursor);
		this.lattice=Objects.requireNonNull(lattice,"Application lattice must not be null");
	}

	/** Creates a live application; the caller configures it on the host explicitly. */
	public static <V extends ACell> LatticeApplication<V> live(ALattice<V> lattice,LatticeHost<V> host) {
		return new LatticeApplication<>(lattice,host.cursor());
	}

	/** Delegates to the lattice merge under this application's context. */
	@Override
	public V merge(V other) {
		return cursor().updateAndGet(own -> getLattice().merge(getContext(),own,other));
	}

	/** The explicitly supplied implementation; ordinary applications need no lattice definition. */
	public final ALattice<V> getLattice() { return lattice; }

	/** Navigates using this adapter's declared container types. */
	public <T extends ACell> AForkableCursor<T> resolve(ACell... path) {
		return LatticeComponent.resolve(cursor(),lattice,path);
	}

	@Override
	protected V mergePublication(V own,V other) {
		return LatticeOps.mergePublication(lattice,getContext(),own,other);
	}

	@Override
	protected V mergeAtPath(ACell value,ACell... path) {
		V reduced=LatticeOps.reduce(lattice,value,path);
		return reduced==null?cursor().get():merge(reduced);
	}

}
