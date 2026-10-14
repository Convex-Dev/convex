package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;

/**
 * A stamp-on-write level, as the component that owns it needs to wrap it: where to
 * inject the write clock into a value, plus the shape of the level for writes below.
 *
 * <p>{@code StampingLattice} implements this; a component passes its declared
 * stamping lattice to {@code StampedCursor.create}.</p>
 *
 * @param <V> Value type at the stamped level
 */
public interface Stamping<V extends ACell> extends Shape<V> {

	/**
	 * Injects a write timestamp into a value.
	 *
	 * @param value Value being written
	 * @param timestamp Write clock reading
	 * @return Stamped value
	 */
	V stamp(V value, CVMLong timestamp);
}
