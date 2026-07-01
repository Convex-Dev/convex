package convex.lattice.generic;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.AUpdateCursor;
import convex.lattice.cursor.StampedCursor;

/**
 * Stamp-on-write lattice layer.
 *
 * <p>Adds a single concern — <b>write interception</b> — over any inner lattice:
 * navigating below it inserts a transparent {@link StampedCursor} that re-stamps
 * values on write (via a caller-supplied stamp function, the write-side dual of a
 * timestamp extractor). Merge and navigation are delegated entirely to the inner
 * lattice, so this layer knows nothing about how values merge or what they contain.</p>
 *
 * <p>Compose with a merge layer and a structure layer to build a durable,
 * navigable region, e.g.
 * {@code StampingLattice.create(LWWLattice.create(JSONLattice.INSTANCE, ts), stamp)}:
 * whole-value LWW merge (deletions durable) + JSON navigation + stamp-on-write.</p>
 *
 * @param <V> Type of lattice values
 */
public class StampingLattice<V extends ACell> extends ADelegatingLattice<V> {

	private final UnaryOperator<V> stampFn;

	private StampingLattice(ALattice<V> inner, UnaryOperator<V> stampFn) {
		super(inner);
		this.stampFn = stampFn;
	}

	/**
	 * Creates a stamp-on-write layer over an inner lattice.
	 *
	 * @param <V> Value type
	 * @param inner Lattice providing merge and navigation
	 * @param stampFn Injects a fresh timestamp into a value on write
	 * @return New StampingLattice instance
	 */
	public static <V extends ACell> StampingLattice<V> create(ALattice<V> inner, UnaryOperator<V> stampFn) {
		return new StampingLattice<>(inner, stampFn);
	}

	@Override
	public boolean isWriteBoundary(ACell key) { return true; }

	@Override
	public boolean consumesPathKey(ACell key) { return false; } // transparent — key navigates below the stamp

	@SuppressWarnings("unchecked")
	@Override
	public AUpdateCursor<?, ?> createPathCursor(ALatticeCursor<?> base, ACell key, LatticeContext context) {
		// Present the same value, stamp it on write, navigate below via the inner lattice
		// (so a merge at the stamped cursor uses the inner merge, e.g. whole-value LWW).
		return StampedCursor.create((ACursor<V>) base, inner, context, stampFn);
	}
}
