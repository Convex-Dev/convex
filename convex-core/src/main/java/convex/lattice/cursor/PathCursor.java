package convex.lattice.cursor;

import java.util.Arrays;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.LatticeOps;

/**
 * A cursor that navigates to a path within a base cursor's value.
 *
 * <p>Required reads throw for absence, including unresolvable paths; a stored nil remains
 * nil. Optional reads handle absence explicitly. Changed writes rebuild existing ancestors.
 * A write through a missing container, or to one that cannot hold its key, throws
 * {@link convex.lattice.PathWriteException} at that key.
 * Update functions receive the actual
 * value, including null; components supply any defaults or construction logic.
 * Atomic operations, unchanged-write handling and claims come from
 * {@link ATransformCursor}.</p>
 *
 * @param <V> Type of cursor values at the path location
 */
public class PathCursor<V extends ACell> extends ATransformCursor<V,ACell> {

	private final ACell[] path;

	public PathCursor(ACursor<?> base, ACell... path) {
		this(base,path,0,path.length);
	}

	/** Creates a view owning a copy of the keys in {@code [start,end)}. */
	@SuppressWarnings("unchecked")
	public PathCursor(ACursor<?> base,ACell[] path,int start,int end) {
		super((ACursor<ACell>)base);
		Objects.checkFromToIndex(start,end,path.length);
		this.path=Arrays.copyOfRange(path,start,end);
	}

	public static <T extends ACell,V extends ACell> PathCursor<T> create(ACursor<V> base,ACell[] path) {
		return new PathCursor<>(base,path);
	}

	@Override protected Object viewValue(ACell stored) {
		return CursorValues.at(stored,path);
	}

	/** Write operations retain the existing nil input for absent entries. */
	@Override protected V view(ACell stored) {
		return RT.getIn(stored,path);
	}

	/** Writing back what is there changes nothing, and fails exactly where a changed write would. */
	@Override protected void requireWritable(ACell stored) {
		write(stored,view(stored));
	}

	/** Associates through existing containers, preserving sibling values. */
	@Override protected ACell write(ACell current,V value) {
		return LatticeOps.assocExisting(current,value,path);
	}
}
