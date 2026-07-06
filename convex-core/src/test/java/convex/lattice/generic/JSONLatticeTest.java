package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.LatticeOps;

/**
 * Spike for the structural {@link JSONLattice}: merge is an error, navigation is
 * recursive, and the write path ({@link LatticeOps#assocIn}) builds containers
 * from key shape.
 */
public class JSONLatticeTest {

	static final AString USERS = Strings.create("users");
	static final AString ALICE = Strings.create("alice");
	static final AString NAME = Strings.create("name");
	static final AString BOB = Strings.create("Bob");

	// ===== Contract: merge is an error, navigation is structural & recursive =====

	@Test public void testMergeIsError() {
		assertThrows(UnsupportedOperationException.class,
			() -> JSONLattice.INSTANCE.merge(Maps.empty(), Maps.empty()));
		assertThrows(UnsupportedOperationException.class,
			() -> JSONLattice.INSTANCE.merge(null, Maps.empty(), Maps.empty()));
	}

	@Test public void testStructuralAndRecursive() {
		assertTrue(JSONLattice.INSTANCE.isStructural());
		// path() recurses: structure continues at every depth
		assertSame(JSONLattice.INSTANCE, JSONLattice.INSTANCE.path(USERS));
		assertSame(JSONLattice.INSTANCE, JSONLattice.INSTANCE.path(Keywords.FOO));
	}

	// ===== Deep structural writes: container chosen by key shape =====

	/** String keys → AHashMap (dynamic JSON object), created all the way down from null. */
	@Test public void testDeepCreateMapFromNull() {
		ACell result = LatticeOps.assocIn(null, BOB, JSONLattice.INSTANCE, USERS, ALICE, NAME);
		// {"users" {"alice" {"name" "Bob"}}}
		assertEquals(BOB, RT.getIn(result, USERS, ALICE, NAME));
		assertInstanceOf(AHashMap.class, RT.getIn(result, USERS));
		assertInstanceOf(AHashMap.class, RT.getIn(result, USERS, ALICE));
	}

	/** Keyword keys → Index (short internal keys; not JSON, but allowed). */
	@Test public void testDeepCreateIndexForKeywords() {
		ACell result = LatticeOps.assocIn(null, CVMLong.create(42),
			JSONLattice.INSTANCE, Keywords.FOO, Keywords.BAR);
		assertEquals(CVMLong.create(42), RT.getIn(result, Keywords.FOO, Keywords.BAR));
		assertInstanceOf(Index.class, RT.getIn(result, Keywords.FOO));
	}

	/** Reads below a JSON region work as plain navigation. */
	@Test public void testReadNavigation() {
		ACell tree = Maps.of(USERS, Maps.of(ALICE, Maps.of(NAME, BOB)));
		assertEquals(BOB, RT.getIn(tree, USERS, ALICE, NAME));
		assertEquals(null, RT.getIn(tree, USERS, Strings.create("nobody")));
	}

	// ===== The guard is intact: a genuinely null lattice still throws =====

	@Test public void testNullLatticeStillThrows() {
		assertThrows(IllegalStateException.class,
			() -> LatticeOps.assocIn(null, CVMLong.ONE, null, Keywords.FOO, Keywords.BAR));
	}

	// ===== Vector constraint: replace works, growth does not =====

	/** Replacing an existing vector element works (no vivification needed). */
	@Test public void testVectorReplaceExisting() {
		AString ARR = Strings.create("arr");
		ACell root = Maps.of(ARR, Vectors.of(10, 20, 30));
		ACell updated = LatticeOps.assocIn(root, CVMLong.create(99),
			JSONLattice.INSTANCE, ARR, CVMLong.create(1));
		assertEquals(CVMLong.create(99), RT.getIn(updated, ARR, CVMLong.create(1)));
		assertInstanceOf(AVector.class, RT.getIn(updated, ARR));
	}

	/**
	 * Documents the constraint: creating a NEW vector element is impossible
	 * through assoc (vectors can't be grown). containerForKey makes an empty
	 * vector for the int key, but assoc(0) on an empty vector returns null —
	 * so the write silently fails to materialise. Captured here so the
	 * behaviour is explicit, not a surprise.
	 */
	@Test public void testVectorGrowthIsBroken() {
		AString ARR = Strings.create("arr");
		// container for int key 0 is an empty vector; assoc(0,..) on it returns null
		assertEquals(Vectors.empty(), LatticeOps.containerForKey(CVMLong.ZERO));
		ACell result = LatticeOps.assocIn(null, BOB, JSONLattice.INSTANCE, ARR, CVMLong.ZERO);
		// The vector element never materialises — the "arr" entry ends up null.
		assertEquals(null, RT.getIn(result, ARR, CVMLong.ZERO));
	}
}
