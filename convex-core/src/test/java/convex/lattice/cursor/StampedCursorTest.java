package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.StampingLattice;

/**
 * End-to-end test of the Covia {@code :state} composition: a whole-value-LWW leaf
 * ({@code LWWLattice.create(JSONLattice.INSTANCE, ts, stamp)}) that
 *
 * <ul>
 *   <li>merges whole-value by timestamp so deletions survive merge-back;</li>
 *   <li>navigates as JSON below the leaf (structural deep writes);</li>
 *   <li>re-stamps the whole leaf on every deep write via an inserted
 *       {@link StampedCursor}.</li>
 * </ul>
 */
public class StampedCursorTest {

	static final AString USERS = Strings.create("users");
	static final AString ALICE = Strings.create("alice");
	static final AString A = Strings.create("a");
	static final AString B = Strings.create("b");

	@SuppressWarnings("unchecked")
	static long tsOf(ACell v) {
		if (v instanceof AHashMap<?, ?> m) {
			ACell t = ((AHashMap<Keyword, ACell>) m).get(LWWLattice.KEY_TIMESTAMP);
			if (t instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	@SuppressWarnings("unchecked")
	static ACell stampWith(ACell v, long ts) {
		if (v instanceof AHashMap<?, ?> m) {
			return ((AHashMap<Keyword, ACell>) m).assoc(LWWLattice.KEY_TIMESTAMP, CVMLong.create(ts));
		}
		return v;
	}

	// The Covia :state stack, composed from three single-concern layers:
	//   stamping (write)  ->  whole-value LWW (merge)  ->  JSON (structure/nav)
	static ALattice<ACell> stateLattice(UnaryOperator<ACell> stamp) {
		return StampingLattice.create(
			LWWLattice.create(JSONLattice.INSTANCE, StampedCursorTest::tsOf),
			stamp);
	}

	// ===== Deep write below the leaf: structural + re-stamped =====

	@Test public void testDeepWriteStampsWholeLeaf() {
		AtomicLong clock = new AtomicLong(100);
		ALattice<ACell> state = stateLattice(v -> stampWith(v, clock.getAndIncrement()));
		ACell initial = Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(5));
		RootLatticeCursor<ACell> root = Cursors.createLattice(state, initial, LatticeContext.EMPTY);

		// navigate below the leaf — a StampedCursor is inserted transparently
		ALatticeCursor<ACell> c = root.path(USERS, ALICE);
		c.set(Strings.create("bob"));

		// deep value landed, structural intermediate ("users") auto-created
		assertEquals(Strings.create("bob"), RT.getIn(root.get(), USERS, ALICE));
		// the whole :state leaf was re-stamped (>= the first clock value)
		assertTrue(tsOf(root.get()) >= 100, "deep write must re-stamp the whole :state leaf");
		// the timestamp keyword still coexists with the data at the top level
		assertNull(RT.getIn(root.get(), Strings.create("nope")));
	}

	// ===== Whole-value LWW merge: deletions are durable =====

	@Test public void testWholeValueMergeDeletionDurable() {
		ALattice<ACell> state = stateLattice(UnaryOperator.identity());
		ACell older = Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(10), A, CVMLong.ONE, B, CVMLong.create(2));
		ACell newer = Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(20), A, CVMLong.ONE); // "b" deleted

		// newer wins whole-value regardless of argument order — no per-key union
		assertSame(newer, state.merge(older, newer));
		assertSame(newer, state.merge(newer, older));
		// so the deleted key does NOT resurrect
		assertNull(RT.getIn(state.merge(older, newer), B));
	}

	// ===== Merge must NOT re-stamp the LWW winner =====

	@Test public void testCursorMergeDoesNotRestampWinner() {
		ACell older = Maps.of(A, CVMLong.ONE, LWWLattice.KEY_TIMESTAMP, CVMLong.create(10));
		ACell newer = Maps.of(B, CVMLong.create(2), LWWLattice.KEY_TIMESTAMP, CVMLong.create(20));
		Root<ACell> base = Root.create(older);
		ALattice<ACell> lww = LWWLattice.create(JSONLattice.INSTANCE, StampedCursorTest::tsOf);
		// stampFn would bump the timestamp to 999 if it were (wrongly) applied to a merge result
		StampedCursor<ACell> sc = StampedCursor.create(base, lww, LatticeContext.EMPTY, v -> stampWith(v, 999));

		sc.merge(newer); // newer (ts 20) is the LWW winner

		assertEquals(20L, tsOf(sc.get()), "merge must not re-stamp the LWW winner");
		assertSame(newer, base.get());   // the exact winner value, stored unstamped
	}

	// ===== Navigation delegates to the inner JSON lattice =====

	@Test public void testNavigationDelegatesToInner() {
		ALattice<ACell> state = stateLattice(UnaryOperator.identity());
		assertTrue(state.isStructural());
		assertSame(JSONLattice.INSTANCE, state.path(USERS));
		assertSame(JSONLattice.INSTANCE.zero(), state.zero()); // both null (structural)
	}
}
