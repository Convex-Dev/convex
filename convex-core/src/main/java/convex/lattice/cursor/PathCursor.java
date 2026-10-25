package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.LatticeOps;

/**
 * A cursor that navigates to a path within a base cursor's value.
 *
 * <p>Reads resolve the nested value; changed writes rebuild existing ancestors.
 * A write through a missing container fails. Update functions receive the actual
 * value, including null; components supply any defaults or construction logic.
 * Atomic operations, unchanged-write handling and transaction locking come from
 * {@link ATransformCursor}.</p>
 *
 * @param <V> Type of cursor values at the path location
 */
public class PathCursor<V extends ACell> extends ATransformCursor<V,ACell> {

	private final ACell[] path;

	@SuppressWarnings("unchecked")
	public PathCursor(ACursor<?> base, ACell... path) {
		super((ACursor<ACell>)base);
		this.path=path.clone();
	}

	public static <T extends ACell,V extends ACell> PathCursor<T> create(ACursor<V> base,ACell[] path) {
		return new PathCursor<>(base,path);
	}

	/** Returns the actual value at the path, including nil. */
	@Override protected V view(ACell stored) {
		return RT.getIn(stored,path);
	}

	/** Associates through existing containers, preserving sibling values. */
	@Override protected ACell write(ACell current,V value) {
		return LatticeOps.assocExisting(current,value,path);
	}
}
