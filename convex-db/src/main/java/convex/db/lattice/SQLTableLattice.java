package convex.db.lattice;

import convex.core.data.ACell;
import convex.core.data.AVector;
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
		return value instanceof AVector;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// Table entries have rows as children
		return (ALattice<T>) TableLattice.INSTANCE;
	}
}
