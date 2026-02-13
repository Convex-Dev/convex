package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * Tests for LWWLattice — Last-Write-Wins register with timestamp-based merge.
 *
 * Verifies all three lattice laws (commutativity, associativity, idempotency)
 * and correct timestamp comparison behaviour.
 */
public class LWWLatticeTest {

	private static final LWWLattice LWW = LWWLattice.INSTANCE;
	private static final Keyword KEY_TS = LWWLattice.KEY_TIMESTAMP;
	private static final Keyword KEY_DATA = Keyword.intern("data");

	private static AHashMap<Keyword, ACell> value(long timestamp, String data) {
		return Maps.of(KEY_TS, CVMLong.create(timestamp), KEY_DATA, Strings.create(data));
	}

	// ===== Basic Merge =====

	@Test
	public void testHigherTimestampWins() {
		ACell old = value(100, "old");
		ACell newer = value(200, "new");

		assertSame(newer, LWW.merge(old, newer));
		assertSame(newer, LWW.merge(newer, old));
	}

	@Test
	public void testEqualTimestampSameValue() {
		ACell a = value(100, "same");
		ACell b = value(100, "same");

		assertEquals(a, LWW.merge(a, b));
		assertEquals(b, LWW.merge(b, a));
	}

	@Test
	public void testNullHandling() {
		ACell v = value(100, "data");

		assertSame(v, LWW.merge(v, null));
		assertSame(v, LWW.merge(null, v));
		assertNull(LWW.merge(null, null));
	}

	@Test
	public void testNoTimestampTreatedAsZero() {
		ACell withTS = value(1, "timestamped");
		ACell noTS = Maps.of(KEY_DATA, Strings.create("no-timestamp"));

		// Value with timestamp > 0 should win over value without timestamp (= 0)
		assertSame(withTS, LWW.merge(withTS, noTS));
		assertSame(withTS, LWW.merge(noTS, withTS));
	}

	// ===== Lattice Law: Commutativity =====

	@Test
	public void testCommutativityDifferentTimestamps() {
		ACell a = value(100, "a");
		ACell b = value(200, "b");

		assertEquals(LWW.merge(a, b), LWW.merge(b, a));
	}

	@Test
	public void testCommutativityEqualTimestampsDifferentValues() {
		// This is the tricky case — hash-based tiebreaker must be deterministic
		ACell a = value(100, "alpha");
		ACell b = value(100, "beta");

		ACell mergeAB = LWW.merge(a, b);
		ACell mergeBA = LWW.merge(b, a);
		assertEquals(mergeAB, mergeBA, "Merge must be commutative even with equal timestamps");
	}

	@Test
	public void testCommutativityWithNull() {
		ACell v = value(100, "data");

		assertEquals(LWW.merge(v, null), LWW.merge(null, v));
	}

	// ===== Lattice Law: Associativity =====

	@Test
	public void testAssociativity() {
		ACell a = value(100, "a");
		ACell b = value(200, "b");
		ACell c = value(150, "c");

		ACell left = LWW.merge(LWW.merge(a, b), c);
		ACell right = LWW.merge(a, LWW.merge(b, c));
		assertEquals(left, right, "Merge must be associative");
	}

	@Test
	public void testAssociativityAllSameTimestamp() {
		ACell a = value(100, "a");
		ACell b = value(100, "b");
		ACell c = value(100, "c");

		ACell left = LWW.merge(LWW.merge(a, b), c);
		ACell right = LWW.merge(a, LWW.merge(b, c));
		assertEquals(left, right, "Merge must be associative even with equal timestamps");
	}

	@Test
	public void testAssociativityWithNulls() {
		ACell a = value(100, "a");
		ACell b = value(200, "b");

		ACell left = LWW.merge(LWW.merge(null, a), b);
		ACell right = LWW.merge(null, LWW.merge(a, b));
		assertEquals(left, right);
	}

	// ===== Lattice Law: Idempotency =====

	@Test
	public void testIdempotency() {
		ACell v = value(100, "data");
		assertSame(v, LWW.merge(v, v));
	}

	@Test
	public void testIdempotencyNull() {
		assertNull(LWW.merge(null, null));
	}

	// ===== zero() and checkForeign() =====

	@Test
	public void testZero() {
		assertNull(LWW.zero());
	}

	@Test
	public void testCheckForeign() {
		assertTrue(LWW.checkForeign(value(1, "x")));
		assertTrue(LWW.checkForeign(null));
	}
}
