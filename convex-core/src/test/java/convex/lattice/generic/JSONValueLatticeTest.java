package convex.lattice.generic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;

/**
 * Tests for {@link JSONValueLattice}.
 */
public class JSONValueLatticeTest {

	static final Keyword KEY_A = Keyword.intern("a");
	static final Keyword KEY_B = Keyword.intern("b");
	static final Keyword KEY_C = Keyword.intern("c");

	@Test
	public void testZeroIndexInstance() {
		ACell zero = JSONValueLattice.INDEX_INSTANCE.zero();
		assertNotNull(zero);
		assertTrue(zero instanceof Index, "INDEX_INSTANCE.zero() should return Index");
	}

	@Test
	public void testZeroMapInstance() {
		ACell zero = JSONValueLattice.INSTANCE.zero();
		assertNotNull(zero);
		assertTrue(zero instanceof AHashMap, "MAP_INSTANCE.zero() should return AHashMap");
	}

	@Test
	public void testPathReturnsSelf() {
		assertSame(JSONValueLattice.INDEX_INSTANCE, JSONValueLattice.INDEX_INSTANCE.path(KEY_A));
		assertSame(JSONValueLattice.INSTANCE, JSONValueLattice.INSTANCE.path(KEY_B));
	}

	@Test
	public void testMergeDisjointMaps() {
		AHashMap<Keyword, ACell> a = Maps.of(KEY_A, CVMLong.create(1));
		AHashMap<Keyword, ACell> b = Maps.of(KEY_B, CVMLong.create(2));

		ACell merged = JSONValueLattice.INSTANCE.merge(a, b);
		assertTrue(merged instanceof AHashMap);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> result = (AHashMap<Keyword, ACell>) merged;
		assertEquals(CVMLong.create(1), result.get(KEY_A));
		assertEquals(CVMLong.create(2), result.get(KEY_B));
	}

	@Test
	public void testMergeOverlappingMaps() {
		AHashMap<Keyword, ACell> a = Maps.of(KEY_A, CVMLong.create(1), KEY_B, CVMLong.create(10));
		AHashMap<Keyword, ACell> b = Maps.of(KEY_A, CVMLong.create(2), KEY_C, CVMLong.create(30));

		ACell merged = JSONValueLattice.INSTANCE.merge(a, b);
		assertTrue(merged instanceof AHashMap);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> result = (AHashMap<Keyword, ACell>) merged;
		// :a has a conflict — resolved by preferring own value
		assertNotNull(result.get(KEY_A));
		// :b from a, :c from b
		assertEquals(CVMLong.create(10), result.get(KEY_B));
		assertEquals(CVMLong.create(30), result.get(KEY_C));
	}

	@Test
	public void testMergeNullOwn() {
		ACell value = CVMLong.create(42);
		assertEquals(value, JSONValueLattice.INDEX_INSTANCE.merge(null, value));
	}

	@Test
	public void testMergeNullOther() {
		ACell value = CVMLong.create(42);
		assertEquals(value, JSONValueLattice.INDEX_INSTANCE.merge(value, null));
	}

	@Test
	public void testMergeBothNull() {
		assertNull(JSONValueLattice.INDEX_INSTANCE.merge(null, null));
	}

	@Test
	public void testLeafPrefersOwn() {
		ACell a = CVMLong.create(1);
		ACell b = CVMLong.create(2);

		// Non-map leaves: prefer own value
		assertSame(a, JSONValueLattice.INSTANCE.merge(a, b));
		assertSame(b, JSONValueLattice.INSTANCE.merge(b, a));
	}

	@Test
	public void testMapMergeCommutativity() {
		// Disjoint keys — commutative because there's no leaf conflict
		AHashMap<Keyword, ACell> a = Maps.of(KEY_A, CVMLong.create(1));
		AHashMap<Keyword, ACell> b = Maps.of(KEY_B, CVMLong.create(2));

		ACell ab = JSONValueLattice.INSTANCE.merge(a, b);
		ACell ba = JSONValueLattice.INSTANCE.merge(b, a);
		assertEquals(ab, ba, "Disjoint map merge should be commutative");
	}

	@Test
	public void testNestedMapMerge() {
		// Nested maps should merge recursively
		AHashMap<Keyword, ACell> innerA = Maps.of(KEY_A, CVMLong.create(1));
		AHashMap<Keyword, ACell> innerB = Maps.of(KEY_B, CVMLong.create(2));
		AHashMap<Keyword, ACell> outerA = Maps.of(KEY_C, innerA);
		AHashMap<Keyword, ACell> outerB = Maps.of(KEY_C, innerB);

		ACell merged = JSONValueLattice.INSTANCE.merge(outerA, outerB);
		assertTrue(merged instanceof AHashMap);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> result = (AHashMap<Keyword, ACell>) merged;
		ACell inner = result.get(KEY_C);
		assertTrue(inner instanceof AHashMap);

		@SuppressWarnings("unchecked")
		AHashMap<Keyword, ACell> innerResult = (AHashMap<Keyword, ACell>) inner;
		assertEquals(CVMLong.create(1), innerResult.get(KEY_A));
		assertEquals(CVMLong.create(2), innerResult.get(KEY_B));
	}

	@Test
	public void testCheckForeign() {
		assertTrue(JSONValueLattice.INDEX_INSTANCE.checkForeign(CVMLong.create(1)));
		assertTrue(JSONValueLattice.INSTANCE.checkForeign(null));
	}
}
