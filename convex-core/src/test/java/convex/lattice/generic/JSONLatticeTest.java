package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ProjectionCursor;
import convex.lattice.cursor.Root;
import convex.lattice.LatticeOps;

/** JSON navigation requires explicit component construction and an enclosing merge. */
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

	@Test public void testRecursiveNavigation() {
		// path() recurses: structure continues at every depth
		assertSame(JSONLattice.INSTANCE, JSONLattice.INSTANCE.path(USERS));
		assertSame(JSONLattice.INSTANCE, JSONLattice.INSTANCE.path(Keywords.FOO));
	}

	/** Example component writer: choose a map explicitly, regardless of key type. */
	private static AForkableCursor<ACell> mapEntry(AForkableCursor<ACell> source, ACell key) {
		return ProjectionCursor.create(source,
			value -> RT.getIn(value, key),
			(value, child) -> {
				if (Utils.equals(RT.getIn(value, key), child)) return value;
				AHashMap<ACell, ACell> map = value == null ? Maps.empty() : RT.ensureMap(value);
				return map.assoc(key, child);
			});
	}

	@Test public void testComponentCreatesMapsOnWrite() {
		Root<ACell> root = Root.create(null);
		var name = mapEntry(mapEntry(mapEntry(root, USERS), ALICE), NAME);
		assertNull(name.get());
		name.set(null);
		assertNull(root.get(), "A no-op must not materialise ancestors");
		name.set(BOB);
		assertEquals(BOB, RT.getIn(root.get(), USERS, ALICE, NAME));
		assertInstanceOf(AHashMap.class, RT.getIn(root.get(), USERS));
		assertInstanceOf(AHashMap.class, RT.getIn(root.get(), USERS, ALICE));
	}

	@Test public void testKeyDoesNotChooseContainerType() {
		Root<ACell> root = Root.create(null);
		mapEntry(mapEntry(root, Keywords.FOO), CVMLong.ZERO).set(BOB);
		assertInstanceOf(AHashMap.class, root.get());
		assertInstanceOf(AHashMap.class, RT.getIn(root.get(), Keywords.FOO));
		assertEquals(BOB, RT.getIn(root.get(), Keywords.FOO, CVMLong.ZERO));
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

	/** Missing vector storage must be constructed explicitly before navigation. */
	@Test public void testMissingVectorRejectedWithoutChangingRoot() {
		AString arr = Strings.create("arr");
		Root<ACell> root = Root.create(Maps.empty());
		assertThrows(IllegalStateException.class,
			() -> root.updateAndGet(value -> LatticeOps.assocIn(value, BOB,
				JSONLattice.INSTANCE, arr, CVMLong.ZERO)));
		assertEquals(Maps.empty(), root.get());
		mapEntry(root, arr).set(Vectors.of(BOB));
		assertInstanceOf(AVector.class, RT.getIn(root.get(), arr));
		assertEquals(BOB, RT.getIn(root.get(), arr, CVMLong.ZERO));
	}
}
