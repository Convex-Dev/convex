package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;

/**
 * Lattice implementation for a single SQL table's row data.
 *
 * <p>The value is an Index mapping primary key (ABlob) to row entry (AVector).
 * Row entries use LWW (last-write-wins) merge semantics via SQLRow.merge().
 *
 * <p>Lattice structure:
 * <pre>
 * Index&lt;ABlob, AVector&lt;ACell&gt;&gt;
 *   primary-key → [values, utime, deleted]
 * </pre>
 */
public class TableLattice extends ALattice<Index<ABlob, AVector<ACell>>> {

	public static final TableLattice INSTANCE = new TableLattice();

	private final IndexLattice<ABlob, AVector<ACell>> delegate;

	private TableLattice() {
		this.delegate = IndexLattice.create(SQLRowLattice.INSTANCE);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> zero() {
		return (Index<ABlob, AVector<ACell>>) Index.EMPTY;
	}

	@Override
	public Index<ABlob, AVector<ACell>> merge(
			Index<ABlob, AVector<ACell>> a,
			Index<ABlob, AVector<ACell>> b) {
		return delegate.merge(a, b);
	}

	@Override
	public boolean checkForeign(Index<ABlob, AVector<ACell>> value) {
		return delegate.checkForeign(value);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return delegate.path(childKey);
	}

	/**
	 * Creates a new TableLattice instance.
	 *
	 * @return TableLattice singleton
	 */
	public static TableLattice create() {
		return INSTANCE;
	}
}
