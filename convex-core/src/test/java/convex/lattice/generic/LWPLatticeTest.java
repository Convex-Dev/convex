package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeTest;

/**
 * Tests for LWPLattice — Last-Write-Preferred lattice wrapper.
 */
public class LWPLatticeTest {

	private static final Keyword KEY_TS = Keyword.intern("timestamp");
	private static final Keyword KEY_A = Keyword.intern("a");
	private static final Keyword KEY_B = Keyword.intern("b");
	private static final Keyword KEY_C = Keyword.intern("c");

	@SuppressWarnings("unchecked")
	private static long extractTS(ACell v) {
		if (v instanceof AHashMap<?,?>) {
			ACell ts = ((AHashMap<Keyword, ACell>) v).get(KEY_TS);
			if (ts instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	private static final LWPLattice<ACell> LWP =
		LWPLattice.create(JSONValueLattice.INSTANCE, LWPLatticeTest::extractTS);

	private static AHashMap<Keyword, ACell> rec(long ts, Keyword key, String val) {
		return Maps.of(KEY_TS, CVMLong.create(ts), key, Strings.create(val));
	}

	// ===== Basic merge — newer wins =====

	@Test
	public void testNewerWins() {
		ACell old = rec(100, KEY_A, "old");
		ACell newer = rec(200, KEY_A, "new");

		assertSame(newer, LWP.merge(old, newer), "Newer should win when other is newer");
		assertSame(newer, LWP.merge(newer, old), "Newer should win when own is newer");
	}

	@Test
	public void testEqualTimestampPrefersOwn() {
		ACell a = rec(100, KEY_A, "alpha");
		ACell b = rec(100, KEY_A, "beta");

		assertSame(a, LWP.merge(a, b));
		assertSame(b, LWP.merge(b, a));
	}

	// ===== Structural merge — disjoint keys preserved =====

	@Test
	public void testDisjointKeysPreserved() {
		ACell a = rec(100, KEY_A, "alpha");
		ACell b = rec(200, KEY_B, "beta");

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> merged = (AHashMap<Keyword, ACell>) LWP.merge(a, b);

		// Both keys present (recursive map merge)
		assertEquals(Strings.create("alpha"), merged.get(KEY_A));
		assertEquals(Strings.create("beta"), merged.get(KEY_B));
		// Newer timestamp becomes "own", so its timestamp wins the leaf conflict
		assertEquals(CVMLong.create(200), merged.get(KEY_TS));
	}

	@Test
	public void testConflictingKeysNewerWins() {
		ACell old = rec(100, KEY_A, "old-val");
		ACell newer = rec(200, KEY_A, "new-val");

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> merged = (AHashMap<Keyword, ACell>) LWP.merge(old, newer);

		assertEquals(Strings.create("new-val"), merged.get(KEY_A));
	}

	@Test
	public void testMixedKeysPreserveAndResolve() {
		AHashMap<Keyword, ACell> a = Maps.of(
			KEY_TS, CVMLong.create(100),
			KEY_A, Strings.create("a"),
			KEY_B, Strings.create("b"));
		AHashMap<Keyword, ACell> b = Maps.of(
			KEY_TS, CVMLong.create(200),
			KEY_A, Strings.create("a2"),
			KEY_C, Strings.create("c"));

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> merged = (AHashMap<Keyword, ACell>) LWP.merge(a, b);

		assertEquals(Strings.create("a2"), merged.get(KEY_A), "Conflict: newer wins");
		assertEquals(Strings.create("b"), merged.get(KEY_B), "Unique to old: preserved");
		assertEquals(Strings.create("c"), merged.get(KEY_C), "Unique to new: preserved");
	}

	// ===== Null handling =====

	@Test
	public void testNullHandling() {
		ACell v = rec(100, KEY_A, "value");

		assertSame(v, LWP.merge(v, null));
		assertSame(v, LWP.merge(null, v));
		assertNull(LWP.merge(null, null));
	}

	// ===== Idempotency =====

	@Test
	public void testIdempotency() {
		ACell v = rec(100, KEY_A, "value");
		assertSame(v, LWP.merge(v, v));
	}

	// ===== Associativity =====

	@Test
	public void testAssociativity() {
		ACell a = rec(100, KEY_A, "a");
		ACell b = rec(200, KEY_A, "b");
		ACell c = rec(150, KEY_A, "c");

		ACell left = LWP.merge(LWP.merge(a, b), c);
		ACell right = LWP.merge(a, LWP.merge(b, c));
		assertEquals(left, right, "Merge must be associative");
	}

	// ===== Delegation =====

	@Test
	public void testZeroDelegates() {
		assertEquals(JSONValueLattice.INSTANCE.zero(), LWP.zero());
	}

	@Test
	public void testPathDelegates() {
		assertNotNull(LWP.path(KEY_A), "path() should delegate to inner lattice");
	}

	@Test
	public void testCheckForeignDelegates() {
		assertTrue(LWP.checkForeign(Maps.empty()));
	}

	// ===== Generic lattice property tests =====

	@Test
	public void testGenericProperties() {
		ACell v1 = rec(100, KEY_A, "v1");
		ACell v2 = rec(200, KEY_A, "v2");
		LatticeTest.doLatticeTest(LWP, v1, v2);
	}
}
