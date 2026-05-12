package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;

/**
 * Lattice for the block-packed row index of a SQL table.
 *
 * <p>Replaces {@link TableLattice} in the lattice path. The value is an
 * {@code Index<ABlob, ACell>} keyed by block prefix, where each value
 * is a {@link RowBlock}-encoded flat Blob rather than a single row entry.
 * Per-block merge is handled by {@link RowBlockLattice}.
 */
public class BlockTableLattice extends ALattice<Index<ABlob, ACell>> {

	public static final BlockTableLattice INSTANCE = new BlockTableLattice();

	private final IndexLattice<ABlob, ACell> delegate =
		IndexLattice.create(RowBlockLattice.INSTANCE);

	private BlockTableLattice() {}

	@Override
	@SuppressWarnings("unchecked")
	public Index<ABlob, ACell> zero() {
		return (Index<ABlob, ACell>) Index.EMPTY;
	}

	@Override
	public Index<ABlob, ACell> merge(Index<ABlob, ACell> a, Index<ABlob, ACell> b) {
		return delegate.merge(a, b);
	}

	@Override
	public boolean checkForeign(Index<ABlob, ACell> value) {
		return delegate.checkForeign(value);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return delegate.path(childKey);
	}
}
