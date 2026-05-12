package convex.db.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;

/**
 * Top-level lattice for a versioned table store.
 *
 * <p>Mirrors {@link TableStoreLattice} but uses {@link VersionedSQLTableLattice}
 * for per-table merge, so the history slot is union-merged on replication.
 */
public class VersionedTableStoreLattice extends ALattice<Index<AString, AVector<ACell>>> {

	public static final VersionedTableStoreLattice INSTANCE = new VersionedTableStoreLattice();

	private final IndexLattice<AString, AVector<ACell>> delegate;

	private VersionedTableStoreLattice() {
		this.delegate = IndexLattice.create(VersionedSQLTableLattice.INSTANCE);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Index<AString, AVector<ACell>> zero() {
		return (Index<AString, AVector<ACell>>) Index.EMPTY;
	}

	@Override
	public Index<AString, AVector<ACell>> merge(
			Index<AString, AVector<ACell>> a,
			Index<AString, AVector<ACell>> b) {
		return delegate.merge(a, b);
	}

	@Override
	public boolean checkForeign(Index<AString, AVector<ACell>> value) {
		return delegate.checkForeign(value);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return delegate.path(childKey);
	}
}
