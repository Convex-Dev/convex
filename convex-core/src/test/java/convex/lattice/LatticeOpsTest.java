package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;

/**
 * Tests for {@link LatticeOps#assocIn}.
 */
public class LatticeOpsTest {

	static final Keyword KEY_A = Keyword.intern("a");
	static final Keyword KEY_B = Keyword.intern("b");

	@Test
	public void testNullRootWithKeyedLattice() {
		// KeyedLattice.zero() -> Index.EMPTY
		KeyedLattice kl = KeyedLattice.create("a", LWWLattice.INSTANCE);

		ACell result = LatticeOps.assocIn(null, CVMLong.create(42), kl, KEY_A);

		assertNotNull(result);
		assertTrue(result instanceof Index, "Root should be Index, got " + result.getClass().getSimpleName());
		Index<Keyword, ACell> idx = (Index<Keyword, ACell>) result;
		assertEquals(CVMLong.create(42), idx.get(KEY_A));
	}

	@Test
	public void testNullRootWithMapLattice() {
		// MapLattice.zero() -> Maps.empty()
		MapLattice<Keyword, ACell> ml = MapLattice.create(LWWLattice.INSTANCE);

		ACell result = LatticeOps.assocIn(null, CVMLong.create(7), ml, KEY_A);

		assertNotNull(result);
		assertTrue(result instanceof AHashMap, "Root should be AHashMap, got " + result.getClass().getSimpleName());
		AHashMap<Keyword, ACell> map = (AHashMap<Keyword, ACell>) result;
		assertEquals(CVMLong.create(7), map.get(KEY_A));
	}

	@Test
	public void testDeepPathWithMultipleNullIntermediates() {
		// KeyedLattice(:a -> KeyedLattice(:b -> LWW))
		KeyedLattice inner = KeyedLattice.create("b", LWWLattice.INSTANCE);
		KeyedLattice outer = KeyedLattice.create("a", inner);

		ACell result = LatticeOps.assocIn(null, CVMLong.create(99), outer, KEY_A, KEY_B);

		assertTrue(result instanceof Index);
		Index<Keyword, ACell> outerIdx = (Index<Keyword, ACell>) result;
		ACell innerVal = outerIdx.get(KEY_A);
		assertTrue(innerVal instanceof Index, "Inner should be Index, got " + (innerVal == null ? "null" : innerVal.getClass().getSimpleName()));
		Index<Keyword, ACell> innerIdx = (Index<Keyword, ACell>) innerVal;
		assertEquals(CVMLong.create(99), innerIdx.get(KEY_B));
	}

	@Test
	public void testNullLatticeAndNullDataThrows() {
		// No lattice + null root -> should throw
		assertThrows(IllegalStateException.class, () -> {
			LatticeOps.assocIn(null, CVMLong.create(1), null, KEY_A);
		});
	}

	@Test
	public void testNullLatticeWithNonNullDataSucceeds() {
		// Existing non-null structure -> no lattice needed
		Index<Keyword, ACell> existing = Index.<Keyword, ACell>none().assoc(KEY_A, CVMLong.create(1));

		ACell result = LatticeOps.assocIn(existing, CVMLong.create(2), null, KEY_A);

		assertTrue(result instanceof Index);
		assertEquals(CVMLong.create(2), ((Index<Keyword, ACell>) result).get(KEY_A));
	}

	@Test
	public void testExistingDataNotChangedByLattice() {
		// Even with a MapLattice, existing Index data stays Index
		Index<Keyword, ACell> existing = Index.<Keyword, ACell>none().assoc(KEY_A, CVMLong.create(1));
		MapLattice<Keyword, ACell> ml = MapLattice.create(LWWLattice.INSTANCE);

		ACell result = LatticeOps.assocIn(existing, CVMLong.create(2), ml, KEY_A);

		assertTrue(result instanceof Index, "Existing Index should be preserved, not replaced by HashMap");
		assertEquals(CVMLong.create(2), ((Index<Keyword, ACell>) result).get(KEY_A));
	}

	@Test
	public void testLeafLatticeZeroNullThrows() {
		// LWWLattice.zero() returns null -> can't create container through it
		assertThrows(IllegalStateException.class, () -> {
			LatticeOps.assocIn(null, CVMLong.create(1), LWWLattice.INSTANCE, KEY_A);
		});
	}

	@Test
	public void testNullLatticeAtMidDepthWithNullDataThrows() {
		// KeyedLattice with unknown key returns null from path()
		// So at depth 1 we have lat=null and data=null -> throw
		KeyedLattice kl = KeyedLattice.create("a", LWWLattice.INSTANCE);

		assertThrows(IllegalStateException.class, () -> {
			LatticeOps.assocIn(null, CVMLong.create(1), kl, KEY_A, KEY_B);
		});
	}

	@Test
	public void testEmptyPathReturnsValue() {
		// Zero-length path just returns the value
		ACell result = LatticeOps.assocIn(CVMLong.create(1), CVMLong.create(2), null);
		assertEquals(CVMLong.create(2), result);
	}

	@Test
	public void testIndexLatticeCreatesIndex() {
		IndexLattice<Keyword, ACell> il = IndexLattice.create(LWWLattice.INSTANCE);

		ACell result = LatticeOps.assocIn(null, CVMLong.create(5), il, KEY_A);

		assertTrue(result instanceof Index);
		assertEquals(CVMLong.create(5), ((Index<Keyword, ACell>) result).get(KEY_A));
	}
}
