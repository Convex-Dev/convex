package convex.lattice.generic;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * A structural lattice for arbitrary-depth, navigable JSON-like values.
 *
 * <p>This lattice describes <em>structure only</em>. It is recursive
 * ({@link #path(ACell)} returns itself, so navigation continues at every depth)
 * and marks itself {@link #isStructural() structural}, so the cursor write path
 * ({@code LatticeOps.assocIn}) builds missing intermediates by key shape
 * (integer → vector, string → map, keyword/blob → index) rather than from a
 * fixed {@link #zero()}.</p>
 *
 * <p>It deliberately defines <strong>no merge</strong>: {@link #merge} throws.
 * A JSON region is an opaque whole value for convergence purposes — merge must
 * be performed by the enclosing lattice (e.g. a whole-value LWW leaf), never
 * per-key here (which would be additive and resurrect deletions). Attempting to
 * merge at this level is a modelling error and fails loudly.</p>
 */
public class JSONLattice extends ALattice<ACell> {

	/** Singleton structural JSON lattice. */
	public static final JSONLattice INSTANCE = new JSONLattice();

	private JSONLattice() {}

	@Override
	public ACell merge(ACell own, ACell other) {
		throw new UnsupportedOperationException(
			"JSONLattice defines no merge — merge must happen at the enclosing lattice");
	}

	@Override
	public ACell merge(LatticeContext context, ACell own, ACell other) {
		throw new UnsupportedOperationException(
			"JSONLattice defines no merge — merge must happen at the enclosing lattice");
	}

	@Override
	public boolean isStructural() {
		return true;
	}

	@Override
	public ACell zero() {
		// No fixed container — the write path builds containers from the key shape.
		return null;
	}

	@Override
	public boolean checkForeign(ACell value) {
		return true; // permissive; never merged at this level anyway
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return (ALattice<T>) this; // recursive — structure continues at every depth
	}
}
