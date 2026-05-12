package convex.db.lattice;

import convex.core.data.ACell;
import convex.lattice.ALattice;

/**
 * Lattice for individual row block entries.
 * Merges two blocks covering the same prefix key using {@link RowBlock#merge}.
 */
public class RowBlockLattice extends ALattice<ACell> {

	public static final RowBlockLattice INSTANCE = new RowBlockLattice();

	private RowBlockLattice() {}

	@Override
	public ACell zero() { return null; }

	@Override
	public ACell merge(ACell a, ACell b) {
		if (a == null) return b;
		if (b == null) return a;
		return RowBlock.merge(a, b);
	}

	@Override
	public boolean checkForeign(ACell value) {
		return RowBlock.isBlock(value);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null; // blocks are leaf nodes
	}
}
