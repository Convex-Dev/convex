package convex.core.data;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.core.util.Bits;
import convex.test.Samples;

/**
 * Tests for CVM Map data structures.
 */
public class MapsTest {

	@Test
	public void testMapBuilding() throws InvalidDataException, ValidationException {
		int SIZE = 1000;

		AMap<CVMLong, CVMLong> m = Maps.empty();
		for (long i = 0; i < SIZE; i++) {
			CVMLong ci=RT.cvm(i);
			assertFalse(m.containsKey(ci));
			m = m.assoc(ci, ci);
			// Log.debug(i+ ": "+m);
			if ((i < 10) || (i % 23 == 0)) m.validate(); // PERF: only check some steps
			assertEquals(i + 1, m.size());
			assertEquals(ci, m.get(ci));
			assertTrue(m.containsKey(ci));
		}

		long C = 1000000;
		assertEquals(SIZE * (SIZE - 1) / 2 + C, (long) m.reduceValues((acc, a) -> acc + a.longValue(), C));
		assertEquals(SIZE * (SIZE - 1) + C, (long) m.reduceEntries((acc, e) -> acc + e.getKey().longValue() + e.getValue().longValue(), C));

		for (long i = 0; i < SIZE; i++) {
			CVMLong ci=RT.cvm(i);
			assertTrue(m.containsKey(ci));
			m = m.dissoc(ci);
			assertEquals(SIZE - i - 1, m.size());
			assertNull(m.get(ci));
			if ((i < 10) || (i % 31 == 0)) m.validate(); // PERF: only check some steps
		}

		assertTrue(m.isEmpty());
	}

	@Test
	public void testDiabolicalMaps() {
		// test that we can at least get hashes without nasty recursion
		// shouldn't create maps without hashes in key values
		assertNotNull(Samples.DIABOLICAL_MAP_2_10000.getHash());
		assertNotNull(Samples.DIABOLICAL_MAP_30_30.getHash());

		// TestCollections.doMapTests(Samples.DIABOLICAL_MAP_2_10000);
		// TestCollections.doMapTests(Samples.DIABOLICAL_MAP_30_30);
	}

	@Test
	public void testTreeIndexesForDigits() {
		assertEquals(0, Bits.indexForDigit(0, (short) 0x111));
		assertEquals(-1, Bits.indexForDigit(2, (short) 0x111));
		assertEquals(1, Bits.indexForDigit(4, (short) 0x111));
		assertEquals(2, Bits.indexForDigit(8, (short) 0x111));
		assertEquals(-1, Bits.indexForDigit(15, (short) 0x111));

		assertEquals(0, Bits.indexForDigit(4, (short) 0x010));
		assertEquals(-1, Bits.indexForDigit(3, (short) 0x010));
		assertEquals(-1, Bits.indexForDigit(5, (short) 0x010));
	}

	@Test
	public void testTreePositionsForDigits() {
		assertEquals(0, Bits.positionForDigit(0, (short) 0x111));
		assertEquals(1, Bits.positionForDigit(2, (short) 0x111));
		assertEquals(1, Bits.positionForDigit(4, (short) 0x111));
		assertEquals(2, Bits.positionForDigit(8, (short) 0x111));
		assertEquals(3, Bits.positionForDigit(15, (short) 0x111));

		assertEquals(0, Bits.positionForDigit(4, (short) 0x010));
		assertEquals(0, Bits.positionForDigit(3, (short) 0x010));
		assertEquals(1, Bits.positionForDigit(5, (short) 0x010));
	}

	@Test
	public void testContains() {
		assertTrue(Samples.LONG_MAP_10.containsValue(RT.cvm(3L)));
		assertFalse(Samples.LONG_MAP_10.containsValue(RT.cvm(12L)));
		assertTrue(Samples.LONG_MAP_100.containsValue(RT.cvm(12L)));
		assertFalse(Samples.LONG_MAP_100.containsValue(RT.cvm(100L)));
	}

	@Test
	public void testContainsRef() {
		assertTrue(Samples.LONG_MAP_10.containsKeyRef(Ref.get(RT.cvm(1L))));
		assertFalse(Samples.LONG_MAP_10.containsKeyRef(Ref.get(RT.cvm(12L))));
		assertTrue(Samples.LONG_MAP_100.containsKeyRef(Ref.get(RT.cvm(12L))));
		assertFalse(Samples.LONG_MAP_100.containsKeyRef(Ref.get(RT.cvm(100L))));
	}

	@Test
	public void testMapToString() {
		AMap<CVMLong, CVMLong> m = Maps.empty();
		assertEquals("{}", m.toString());
		m = m.assoc(RT.cvm(1L), RT.cvm(2L));
		assertEquals("{1 2}", m.toString());
	}

	@Test
	public void testTruncateHexDigits() throws InvalidDataException, BadFormatException {
		AHashMap<CVMLong, CVMLong> m = Samples.LONG_MAP_100;
		assertEquals(100, m.count());
		AHashMap<CVMLong, CVMLong> m1 = m.mapEntries(e -> (e.getKeyHash().getHexDigit(0) < 8) ? e : null);
		AHashMap<CVMLong, CVMLong> m2 = m.mapEntries(e -> (e.getKeyHash().getHexDigit(0) >= 8) ? e : null);
		assertEquals(100, m1.count() + m2.count());
		m1.validate();
		m2.validate();
		// should merge back to original map. Useful also for testing empty children
		// merges.
		assertEquals(m, m1.mergeDifferences(m2, (a, b) -> (a == null) ? b : a));
	}

	@Test
	public void regressionEmbeddedTransfer() throws BadFormatException {
		ATransaction trans=Transfer.create(InitTest.HERO,0, InitTest.HERO, 58);
		CVMLong key=CVMLong.create(23771L);
		AMap<CVMLong,ATransaction> m=Maps.create(key,trans);
		MapEntry<CVMLong,ATransaction> me=m.entryAt(0);
		assertEquals(key,me.getKey());
		assertEquals(trans,me.getValue());

		// transaction should never be embedded
		assertEquals(trans.isEmbedded(),me.getValueRef().isEmbedded());

		Blob b=m.getEncoding();
		AMap<CVMLong,ATransaction> m2=Format.read(b);

		assertEquals(m,m2);

		Blob b2=m2.getEncoding();
		assertEquals(b,b2);
	}

	@Test
	public void testMapToNull() throws InvalidDataException, BadFormatException {
		// check that we obtain the singleton instance, using a map to null to remove
		// keys
		assertSame(Maps.empty(), Samples.LONG_MAP_100.mapEntries(e -> null));
		assertSame(Maps.empty(), Samples.LONG_MAP_10.mapEntries(e -> null));
	}

	@Test
	public void testTreeDigitForIndex() {
		assertEquals(5, MapTree.digitForIndex(0, (short) 0x020));

		assertEquals(0, MapTree.digitForIndex(0, (short) 0x111));
		assertEquals(4, MapTree.digitForIndex(1, (short) 0x111));
		assertEquals(8, MapTree.digitForIndex(2, (short) 0x111));
	}

	@Test
	public void testBadDigitNegative() {
		assertThrows(IllegalArgumentException.class, () -> MapTree.digitForIndex(-1, (short) 0x111));
		assertThrows(IllegalArgumentException.class, () -> MapTree.digitForIndex(3, (short) 0x111));
	}

	@Test
	public void testSmallMergeIndentity() {
		AHashMap<ACell, ACell> m0 = Maps.empty();
		AHashMap<ACell, ACell> m1 = Maps.of(1, 2, 3, 4);
		AHashMap<ACell, ACell> m2 = Maps.of(3, 4, 5, 6);
		AHashMap<ACell, ACell> m3 = Maps.of(1, 2, 3, 4, 5, 6);

		assertSame(m0, m1.mergeWith(m3, (a, b) -> null));
		assertSame(m3, m3.mergeWith(m3, (a, b) -> a));
		assertSame(m2, m2.mergeWith(m3, (a, b) -> a));
		assertSame(m2, m2.mergeWith(m1, (a, b) -> a));
		assertSame(m0, m3.mergeWith(m3, (a, b) -> null));
		assertTrue(m3.equals(m2.mergeWith(m3, (a, b) -> b)));

	}

	@Test
	public void regressionCreateWithDuplicateEntries() {
		MapEntry<CVMLong, CVMLong> e = MapEntry.of(1L, 2L);
		AMap<CVMLong, CVMLong> m = Maps.create(Vectors.of(e, e));
		assertEquals(1, m.size());
	}

	@Test
	public void regressionTestMerge() throws InvalidDataException {
		AHashMap<ACell, ACell> m = Maps.of(Blob.fromHex("798b809c"), null);
		m.validate();
		// this should remove the entry, since mergeWith removes null values
		AHashMap<ACell, ACell> m1 = m.mergeWith(m, (a, b) -> a);
		assertSame(Maps.empty(), m1);

		CollectionsTest.doMapTests(m);
	}

	@Test
	public void testDuplicateEntryCreate() {
		AMap<CVMLong, CVMLong> m = Maps.of(10, 2, 10, 3);
		assertEquals(1, m.size());
		assertEquals(RT.cvm(10L), m.entryAt(0).getKey());
	}

	@Test
	public void testFilterHex() {
		MapLeaf<CVMLong, ACell> m = Maps.of(1, true, 2, true, 3, true, -1000, true);
		assertEquals(4L,m.count());

		// TODO: selective filter
		//assertEquals(Maps.of(3L, true), m.filterHexDigits(0, 64)); // hex digit 0 = 6 only

		assertSame(m, m.filterHexDigits(0, 0xFFFF)); // all digits selected
		assertSame(Maps.empty(), m.filterHexDigits(0, 0)); // all digits selected
	}

	private static final Predicate<CVMLong> EVEN_PRED = a -> {
		return (a.longValue() & 1L) == 0L;
	};

	@Test
	public void testFilterValues10() {
		AHashMap<CVMLong, CVMLong> m = Samples.LONG_MAP_10;
		AHashMap<CVMLong, CVMLong> m2 = m.filterValues(EVEN_PRED);
		assertEquals(5, m2.size());
	}

	@Test
	public void testFilterValues100() {
		AHashMap<CVMLong, CVMLong> m = Samples.LONG_MAP_100;
		AHashMap<CVMLong, CVMLong> m2 = m.filterValues(EVEN_PRED);
		assertEquals(50, m2.size());

	}

	@Test
	public void testEmpty() {
		AMap<Keyword,Keyword> m=Maps.empty();
		assertEquals(0L,m.count());
		assertSame(m,Maps.empty());
		assertSame(Vectors.empty(),m.getKeys());

		assertEquals(2L,m.getEncoding().count());
	}
	
	@Test 
	public void testSlice() {
		AMap<CVMLong, CVMLong> m = Samples.LONG_MAP_5;
		assertSame(m,m.slice(0));
		assertSame(m.empty(),m.slice(5));
		assertSame(m.empty(),m.slice(3,3));
		
		assertNull(m.slice(-1));
		assertNull(m.slice(12));
		assertNull(m.slice(4,3));
	}
	
	@SuppressWarnings("unchecked")
	@Test 
	public void testBigMapChild() {
		MapTree<CVMLong,CVMLong> bm=(MapTree<CVMLong,CVMLong>)Samples.LONG_MAP_100;
		AHashMap<CVMLong,CVMLong> cm=(AHashMap<CVMLong, CVMLong>) bm.getRef(0).getValue();
		doHashMapTest(cm);
	}

	
	@Test 
	public void testBigMapSlice() {
		AHashMap<CVMLong,CVMLong> bm=Samples.LONG_MAP_100;
		AHashMap<CVMLong,CVMLong> bm1=bm.slice(0,18);
		assertEquals(18,bm1.count());
		doHashMapTest(bm1);
		AHashMap<CVMLong,CVMLong> bm2=bm.slice(18,67);
		assertEquals(67-18,bm2.count());
		doHashMapTest(bm2);
		AHashMap<CVMLong,CVMLong> bm3=bm.slice(67);
		assertEquals(100-67,bm3.count());
		doHashMapTest(bm3);
		
		AHashMap<CVMLong,CVMLong> merged=bm2.merge(bm3.merge(bm1));
		assertEquals(bm,merged);
		doHashMapTest(merged);
	}

	@Test
	public void testEquals() {
		AMap<CVMLong, CVMLong> m = Samples.LONG_MAP_100;
		assertNotEquals(m, m.assoc(null, null));
		assertNotEquals(m, m.assoc(RT.cvm(2L), RT.cvm(3L)));

		CollectionsTest.doMapTests(m);
	}

	@Test
	public void testMapEntry() {
		AMap<CVMLong, CVMLong> m = Maps.of(1L, 2L);
		MapEntry<CVMLong, CVMLong> me = m.getEntry(RT.cvm(1L));
		CVMLong k = me.getKey();
		CVMLong v = me.getValue();
		assertCVMEquals(1L, k);
		assertCVMEquals(2L, v);
		
		// a Map Entry should be functionally equal to a Vector
		ObjectsTest.doEqualityTests(me, Vectors.of(k,v));

		// out of range assocs
		assertNull( me.assoc(2, RT.cvm(3L)));
		assertNull( me.assoc(-1, RT.cvm(0L)));

		assertThrows(UnsupportedOperationException.class, () -> me.setValue(RT.cvm(6L)));

		assertEquals(me, me.assoc(0, RT.cvm(1L)));
		assertEquals(me, me.assoc(1, RT.cvm(2L)));


		assertTrue(me.contains(RT.cvm(1L)));
		assertTrue(me.contains(RT.cvm(2L)));
		assertFalse(me.contains(CVMBool.TRUE));
		assertFalse(me.contains(null));

		// generic tests for MapEntry treated as a vector
		VectorsTest.doVectorTests(me);
	}

	@Test
	public void testAssocs() {
		AHashMap<CVMLong, CVMLong> m = Maps.of(1L, 2L);
		assertSame(m, m.assoc(RT.cvm(1L), RT.cvm(2L)));

		doHashMapTest(m);
	}

	@Test
	public void testConj() {
		AHashMap<CVMLong, CVMLong> m = Maps.of(1L, 2L);
		AHashMap<CVMLong, CVMLong> me = Maps.of(1L, 2L, 3L, 4L);
		assertEquals(m, m.conj(Vectors.of(1L, 2L)));
		assertEquals(me, m.conj(Vectors.of(3L, 4L)));
		assertEquals(me, m.conj(MapEntry.of(3L, 4L)));

		// failures with conj'ing things that aren't valid map entries
		assertNull(m.conj(Vectors.empty()));
		assertNull(m.conj(Vectors.of(1L)));
		assertNull(m.conj(Vectors.of(1L, 2L, 3L)));
		assertNull(m.conj(null));

		doHashMapTest(me);
	}
	
	@Test
	public void testDissoc() {
		AHashMap<CVMLong, CVMLong> m=Samples.LONG_MAP_100;
		long n=m.count();
		assertTrue(n>0);
		
		// Shouldn't be in map
		assertSame(m,m.dissoc(CVMLong.MINUS_ONE));
		
		ACell v=m.entryAt(n/2).getKey();
		assertTrue(m.containsKey(v));
		
		AHashMap<CVMLong, CVMLong> dm=m.dissoc(v);
		assertNotEquals(m,dm);
		
		assertEquals(n-1,dm.count());
		assertFalse(dm.containsKey(v));
	}

	@Test
	public void testMergeWith() {
		AHashMap<CVMLong, CVMLong> m = Maps.of(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L, 7L, 8L, 8L);
		AHashMap<CVMLong, CVMLong> m2 = m.mergeWith(m, (a, b) -> ((a.longValue() & 1L) == 0L) ? a : null);
		assertEquals(4, m2.size());

		AHashMap<ACell, ACell> bm = Maps.coerce(Samples.LONG_MAP_100);
		AHashMap<ACell, ACell> sm = Maps.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

		// change values in big map using small map
		AHashMap<ACell, ACell> bm2 = bm.mergeWith(sm, (a, b) -> {
			return (a == null) ? b : a;
		});
		assertEquals(100, bm2.count());
		assertEquals(bm2, sm.mergeWith(bm, (a, b) -> {
			return (b == null) ? a : b;
		}));

		doHashMapTest(m);
		doHashMapTest(m2);
	}
	
	protected static <K extends ACell, V extends ACell> void doHashMapTest(AHashMap<K, V> m) {
		if (m.isEmpty()) {
			assertSame(m,m.empty());
		} else {
			long n=m.count();
			MapEntry<K, V> firstEntry = m.entryAt(0);
			K firstKey=firstEntry.getKey();
			assertEquals(firstEntry,m.getEntryByHash(Hash.get(firstKey)));
			assertEquals(m,m.assocEntry(firstEntry));
			
			// Test a smaller version of this map
			AHashMap<K, V> smaller = m.dissoc(firstKey);
			assertEquals(n-1,smaller.count());
			assertTrue(m.containsAllKeys(m));
			assertFalse(smaller.containsAllKeys(m));
		}
		
		CollectionsTest.doMapTests(m);
	}
}
