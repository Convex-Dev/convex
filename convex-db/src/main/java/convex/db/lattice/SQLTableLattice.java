package convex.db.lattice;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Lattice for individual SQL table entries (schema + rows).
 *
 * <p>Merges table entries using LWW for schema and row-level merge for data.
 */
public class SQLTableLattice extends ALattice<AVector<ACell>> {

	public static final SQLTableLattice INSTANCE = new SQLTableLattice();

	private SQLTableLattice() {}

	@Override
	public AVector<ACell> zero() {
		return null;
	}

	@Override
	public AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		return SQLTable.merge(a, b);
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		return value instanceof AVector && value.count() >= 3;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// Table vector: [0]=schema, [1]=rows, [2]=utime
		// Only rows (position 1) has a sub-lattice
		if (childKey instanceof CVMLong idx && idx.longValue() == SQLTable.POS_ROWS) {
			return (ALattice<T>) BlockTableLattice.INSTANCE;
		}
		return null;
	}
}
