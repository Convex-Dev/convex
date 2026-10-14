package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;

/**
 * Tests for {@link LatticeOps#assocIn}: the write engine creates an absent base from
 * the {@link Shape} it is given and never invents a container below it, except for
 * structural (JSON-like) shapes. Typed intermediates come from the component that
 * owns each level, which declares them with {@code child(key, shape)}.
 */
public class LatticeOpsTest {

	static final Keyword KEY_A = Keyword.intern("a");
	static final Keyword KEY_B = Keyword.intern("b");

	@SuppressWarnings("unchecked")
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

	@SuppressWarnings("unchecked")
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

	/**
	 * The engine knows only the shape of the base. An absent intermediate below it has
	 * no declared type here, so a deep write through it fails rather than guessing;
	 * the owning component declares each level with {@code child(key, shape)} and the
	 * same write then creates every container with its correct type.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testDeepPathWithMissingIntermediate() {
		// KeyedLattice(:a -> KeyedLattice(:b -> LWW))
		KeyedLattice inner = KeyedLattice.create("b", LWWLattice.INSTANCE);
		KeyedLattice outer = KeyedLattice.create("a", inner);

		assertThrows(IllegalStateException.class,
			() -> LatticeOps.assocIn(null, CVMLong.create(99), outer, KEY_A, KEY_B));

		Root<Index<Keyword, ACell>> root = Cursors.root((Index<Keyword, ACell>) null, outer, null);
		root.child(KEY_A, inner).child(KEY_B, LWWLattice.INSTANCE).set(CVMLong.create(99));

		Index<Keyword, ACell> outerIdx = root.get();
		assertNotNull(outerIdx);
		ACell innerVal = outerIdx.get(KEY_A);
		assertTrue(innerVal instanceof Index, ()->"Inner should be Index, got " + Utils.getClassName(innerVal));
		Index<Keyword, ACell> innerIdx = (Index<Keyword, ACell>) innerVal;
		assertEquals(CVMLong.create(99), innerIdx.get(KEY_B));
	}

	/** A structural shape builds each missing container from the key written through it. */
	@Test
	public void testStructuralShapeCreatesIntermediatesByKey() {
		ACell result = LatticeOps.assocIn(null, CVMLong.create(3), JSONLattice.INSTANCE,
			Strings.create("a"), Strings.create("b"));

		assertTrue(result instanceof AHashMap, "String keys create hash maps");
		assertEquals(CVMLong.create(3), RT.getIn(result, Strings.create("a"), Strings.create("b")));
	}

	@Test
	public void testNullShapeAndNullDataThrows() {
		// No shape + null root -> should throw
		assertThrows(IllegalStateException.class, () -> {
			LatticeOps.assocIn(null, CVMLong.create(1), null, KEY_A);
		});
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testNullShapeWithNonNullDataSucceeds() {
		// Existing non-null structure -> no shape needed
		Index<Keyword, ACell> existing = Index.<Keyword, ACell>none().assoc(KEY_A, CVMLong.create(1));

		ACell result = LatticeOps.assocIn(existing, CVMLong.create(2), null, KEY_A);

		assertTrue(result instanceof Index);
		assertEquals(CVMLong.create(2), ((Index<Keyword, ACell>) result).get(KEY_A));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testExistingDataNotChangedByShape() {
		// Even with a MapLattice, existing Index data stays Index
		Index<Keyword, ACell> existing = Index.<Keyword, ACell>none().assoc(KEY_A, CVMLong.create(1));
		MapLattice<Keyword, ACell> ml = MapLattice.create(LWWLattice.INSTANCE);

		ACell result = LatticeOps.assocIn(existing, CVMLong.create(2), ml, KEY_A);

		assertTrue(result instanceof Index, "Existing Index should be preserved, not replaced by HashMap");
		assertEquals(CVMLong.create(2), ((Index<Keyword, ACell>) result).get(KEY_A));
	}

	@Test
	public void testLeafShapeZeroNullThrows() {
		// LWWLattice.zero() returns null -> can't create container through it
		assertThrows(IllegalStateException.class, () -> {
			LatticeOps.assocIn(null, CVMLong.create(1), LWWLattice.INSTANCE, KEY_A);
		});
	}

	@Test
	public void testMissingIntermediateWithNullDataThrows() {
		// The base is created from the shape, but :a's shape is not known to the engine
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

	@SuppressWarnings("unchecked")
	@Test
	public void testIndexLatticeCreatesIndex() {
		IndexLattice<Keyword, ACell> il = IndexLattice.create(LWWLattice.INSTANCE);

		ACell result = LatticeOps.assocIn(null, CVMLong.create(5), il, KEY_A);

		assertTrue(result instanceof Index);
		assertEquals(CVMLong.create(5), ((Index<Keyword, ACell>) result).get(KEY_A));
	}
}
