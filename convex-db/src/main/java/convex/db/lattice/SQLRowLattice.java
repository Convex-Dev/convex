package convex.db.lattice;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.lattice.ALattice;

/**
 * Lattice for individual SQL row entries.
 *
 * <p>Merges row entries using LWW (last-write-wins) semantics.
 * Equal timestamps favor deletions (tombstones).
 */
public class SQLRowLattice extends ALattice<AVector<ACell>> {

	public static final SQLRowLattice INSTANCE = new SQLRowLattice();

	private SQLRowLattice() {}

	@Override
	public AVector<ACell> zero() {
		return null;
	}

	@Override
	public AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		return SQLRow.merge(a, b);
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		return value instanceof AVector && value.count() >= 3;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// Row entries are leaf nodes
		return null;
	}
}
