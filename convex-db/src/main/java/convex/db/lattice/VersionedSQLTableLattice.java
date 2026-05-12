package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Lattice for a single versioned SQL table entry.
 *
 * <p>Extends the behaviour of {@link SQLTableLattice} by:
 * <ul>
 *   <li>Delegating merge to {@link VersionedSQLTable#merge} (which union-merges the history slot)</li>
 *   <li>Exposing {@link VersionedSQLTable#HISTORY_LATTICE} at path position {@link VersionedSQLTable#POS_HISTORY}</li>
 * </ul>
 */
public class VersionedSQLTableLattice extends ALattice<AVector<ACell>> {

	public static final VersionedSQLTableLattice INSTANCE = new VersionedSQLTableLattice();

	private VersionedSQLTableLattice() {}

	@Override
	public AVector<ACell> zero() { return null; }

	@Override
	public AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		return VersionedSQLTable.merge(a, b);
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		return value instanceof AVector && value.count() >= 5;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (childKey instanceof CVMLong idx) {
			long pos = idx.longValue();
			if (pos == SQLTable.POS_ROWS)           return (ALattice<T>) BlockTableLattice.INSTANCE;
			if (pos == VersionedSQLTable.POS_HISTORY) return (ALattice<T>) VersionedSQLTable.HISTORY_LATTICE;
		}
		return null;
	}
}
