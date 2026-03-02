package convex.db.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;

/**
 * Lattice implementation for a SQL database's table store.
 *
 * <p>The value is an Index mapping table name (AString) to table state (AVector).
 * Each table entry uses SQLTable.merge() for conflict resolution.
 *
 * <p>Lattice structure:
 * <pre>
 * Index&lt;AString, AVector&lt;ACell&gt;&gt;
 *   table-name → [schema, rows, utime]
 * </pre>
 *
 * <p>This sits at the database level in the lattice path:
 * <pre>
 * :sql → OwnerLattice → SignedLattice → MapLattice → TableStoreLattice
 *          owner-key → signed(db-name → table-store)
 * </pre>
 */
public class TableStoreLattice extends ALattice<Index<AString, AVector<ACell>>> {

	public static final TableStoreLattice INSTANCE = new TableStoreLattice();

	private final IndexLattice<AString, AVector<ACell>> delegate;

	private TableStoreLattice() {
		this.delegate = IndexLattice.create(SQLTableLattice.INSTANCE);
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

	/**
	 * Creates a new TableStoreLattice instance.
	 *
	 * @return TableStoreLattice singleton
	 */
	public static TableStoreLattice create() {
		return INSTANCE;
	}
}
