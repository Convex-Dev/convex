package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.JSONLattice;

/**
 * Properties of write-interception in {@code ALatticeCursor.path}, asserted via
 * object identity and invocation counts rather than benchmarks:
 *
 * <ul>
 *   <li>ordinary deep navigation never builds a boundary cursor (no allocation
 *       on the pass-through path);</li>
 *   <li>a boundary cursor is built <b>exactly once</b> — no re-wrap on the hot
 *       write path;</li>
 *   <li>reads pass through by reference (same object, no copy);</li>
 *   <li>no-op writes preserve root identity (no structural churn).</li>
 * </ul>
 */
public class PathInterceptTest {

	static final AtomicInteger TAPS = new AtomicInteger();

	/** Identity update-override cursor; counts writes that pass through it. */
	static class IdCursor extends AUpdateCursor<ACell, ACell> {
		IdCursor(ACursor<ACell> base, ALattice<ACell> lattice, LatticeContext ctx) { super(base, lattice, ctx); }
		@Override protected ACell updateOnWrite(ACell current, ACell value) { TAPS.incrementAndGet(); return value; }
		// view inherited (identity)
		@Override public ACell merge(ACell other) {
			return base.updateAndGet(current -> lattice.merge(getContext(), current, other));
		}
	}

	/**
	 * Configurable probe lattice: intercepts iff the navigated key equals
	 * {@code boundaryKey}, counts how many boundary cursors it builds, and
	 * navigates into {@code below} (or itself, recursively, when {@code below}
	 * is null).
	 */
	static class ProbeLattice extends ALattice<ACell> {
		final ACell boundaryKey;
		final boolean consumes;
		final ALattice<ACell> below;
		int builds = 0;

		ProbeLattice(ACell boundaryKey, boolean consumes, ALattice<ACell> below) {
			this.boundaryKey = boundaryKey; this.consumes = consumes; this.below = below;
		}

		ALattice<ACell> nav() { return (below != null) ? below : this; }

		@Override public boolean isWriteBoundary(ACell key) { return Utils.equals(boundaryKey, key); }

		@SuppressWarnings("unchecked")
		@Override public AUpdateCursor<?, ?> createPathCursor(ALatticeCursor<?> base, ACell key, LatticeContext ctx) {
			builds++;
			return new IdCursor((ACursor<ACell>) base, nav(), ctx);
		}

		@Override public boolean consumesPathKey(ACell key) { return consumes; }
		@Override public ACell merge(ACell own, ACell other) { return (other != null) ? other : own; }
		@Override public ACell zero() { return Maps.empty(); }
		@Override public boolean checkForeign(ACell value) { return true; }

		@SuppressWarnings("unchecked")
		@Override public <T extends ACell> ALattice<T> path(ACell childKey) { return (ALattice<T>) nav(); }
	}

	/** Build {k0 {k1 {... {k(n-1) leaf}}}}; returns [value, keys]. */
	static Object[] nested(ACell leaf, int depth) {
		ACell[] keys = new ACell[depth];
		ACell v = leaf;
		for (int d = depth - 1; d >= 0; d--) {
			keys[d] = Strings.create("k" + d);
			v = Maps.of(keys[d], v);
		}
		return new Object[]{v, keys};
	}

	// ===== Pass-through allocates no boundary cursor, reads by reference =====

	@Test public void testPassThroughBuildsNothing() {
		AString leaf = Strings.create("leaf");
		Object[] nv = nested(leaf, 6);
		ProbeLattice lat = new ProbeLattice(Strings.create("NEVER"), true, null); // never intercepts, recursive
		RootLatticeCursor<ACell> root = Cursors.createLattice(lat, (ACell) nv[0], LatticeContext.EMPTY);

		ACell[] keys = (ACell[]) nv[1];
		ALatticeCursor<ACell> c = root.path(keys);

		assertEquals(0, lat.builds, "no boundary along the path → no boundary cursor allocated");
		assertSame(leaf, c.get(), "read is pure pass-through: same object, no copy");
	}

	// ===== Boundary cursor built exactly once: no re-wrap on the write path =====

	@Test public void testBoundaryBuiltExactlyOnce() {
		AString K0 = Strings.create("k0");
		AString BKEY = Strings.create("BOUNDARY");
		// {k0 {BOUNDARY "x"}} — boundary is crossed at depth 1, after one accumulated key
		ACell value = Maps.of(K0, Maps.of(BKEY, Strings.create("x")));
		ProbeLattice lat = new ProbeLattice(BKEY, true, null); // recursive, consumes the boundary key
		RootLatticeCursor<ACell> root = Cursors.createLattice(lat, value, LatticeContext.EMPTY);

		root.path(K0, BKEY);

		// The old probe-then-flush-then-rewrap path built this twice (one discarded).
		assertEquals(1, lat.builds, "boundary cursor built exactly once (no re-wrap allocation)");
	}

	// ===== No-op write preserves root identity (no churn) =====

	@Test public void testNoOpWritePreservesIdentity() {
		AString leaf = Strings.create("leaf");
		Object[] nv = nested(leaf, 5);
		ProbeLattice lat = new ProbeLattice(Strings.create("NEVER"), true, null);
		RootLatticeCursor<ACell> root = Cursors.createLattice(lat, (ACell) nv[0], LatticeContext.EMPTY);

		ACell before = root.get();
		ALatticeCursor<ACell> c = root.path((ACell[]) nv[1]);
		c.updateAndGet(x -> x); // identity update — should write the same leaf back

		assertSame(before, root.get(), "no-op write must not churn/reallocate the structure");
	}

	// ===== Transparent (non-consuming) boundary still intercepts writes =====

	@Test public void testTransparentBoundaryInterceptsWrites() {
		TAPS.set(0);
		AString A = Strings.create("a");
		// intercepts at "a", does NOT consume it, navigates JSON below
		ProbeLattice lat = new ProbeLattice(A, false, JSONLattice.INSTANCE);
		RootLatticeCursor<ACell> root = Cursors.createLattice(lat, Maps.empty(), LatticeContext.EMPTY);

		ALatticeCursor<ACell> c = root.path(A);
		assertNull(c.get());

		c.set(Strings.create("hello"));
		assertEquals(1, lat.builds, "transparent boundary built once");
		assertTrue(TAPS.get() > 0, "write passed through the transparent boundary cursor");
		assertEquals(Strings.create("hello"), RT.getIn(root.get(), A));
	}
}
