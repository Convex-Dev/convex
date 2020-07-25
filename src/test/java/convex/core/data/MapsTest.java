package convex.core.data;

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

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.ValidationException;
import convex.core.util.Bits;
import convex.test.Samples;

public class MapsTest {

	@Test
	public void testMapBuilding() throws InvalidDataException, ValidationException {
		int SIZE = 1000;

		AMap<Integer, Integer> m = Maps.empty();
		for (int i = 0; i < SIZE; i++) {
			assertFalse(m.containsKey(i));
			m = m.assoc(i, i);
			// Log.debug(i+ ": "+m);
			if ((i < 10) || (i % 23 == 0)) m.validate(); // PERF: only check some steps
			assertEquals(i + 1, m.size());
			assertEquals((Integer) i, m.get(i));
			assertTrue(m.containsKey(i));
		}

		int C = 1000000;
		assertEquals(SIZE * (SIZE - 1) / 2 + C, (int) m.reduceValues((acc, a) -> acc + a, C));
		assertEquals(SIZE * (SIZE - 1) + C, (int) m.reduceEntries((acc, e) -> acc + e.getKey() + e.getValue(), C));

		for (int i = 0; i < SIZE; i++) {
			assertTrue(m.containsKey(i));
			m = m.dissoc(i);
			assertEquals(SIZE - i - 1, m.size());
			assertNull(m.get(i));
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
		assertTrue(Samples.LONG_MAP_10.containsValue(3L));
		assertFalse(Samples.LONG_MAP_10.containsValue(12L));
		assertTrue(Samples.LONG_MAP_100.containsValue(12L));
		assertFalse(Samples.LONG_MAP_100.containsValue(100L));
	}

	@Test
	public void testContainsRef() {
		assertTrue(Samples.LONG_MAP_10.containsKeyRef(Ref.create(1L)));
		assertFalse(Samples.LONG_MAP_10.containsKeyRef(Ref.create(12L)));
		assertTrue(Samples.LONG_MAP_100.containsKeyRef(Ref.create(12L)));
		assertFalse(Samples.LONG_MAP_100.containsKeyRef(Ref.create(100L)));
	}

	@Test
	public void testMapToString() {
		AMap<Long, Long> m = Maps.empty();
		assertEquals("{}", m.toString());
		m = m.assoc(1L, 2L);
		assertEquals("{1 2}", m.toString());
	}

	@Test
	public void testTruncateHexDigits() throws InvalidDataException, BadFormatException {
		AHashMap<Long, Long> m = Samples.LONG_MAP_100;
		assertEquals(100, m.count());
		AHashMap<Long, Long> m1 = m.mapEntries(e -> (e.getKeyHash().getHexDigit(0) < 8) ? e : null);
		AHashMap<Long, Long> m2 = m.mapEntries(e -> (e.getKeyHash().getHexDigit(0) >= 8) ? e : null);
		assertEquals(100, m1.count() + m2.count());
		m1.validate();
		m2.validate();
		// should merge back to original map. Useful also for testing empty children
		// merges.
		assertEquals(m, m1.mergeDifferences(m2, (a, b) -> (a == null) ? b : a));
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
		MapLeaf<Object, Object> m0 = (MapLeaf<Object, Object>) Maps.empty();
		MapLeaf<Object, Object> m1 = Maps.of(1, 2, 3, 4);
		MapLeaf<Object, Object> m2 = Maps.of(3, 4, 5, 6);
		MapLeaf<Object, Object> m3 = Maps.of(1, 2, 3, 4, 5, 6);

		assertSame(m0, m1.mergeWith(m3, (a, b) -> null));
		assertSame(m3, m3.mergeWith(m3, (a, b) -> a));
		assertSame(m2, m2.mergeWith(m3, (a, b) -> a));
		assertSame(m2, m2.mergeWith(m1, (a, b) -> a));
		assertSame(m0, m3.mergeWith(m3, (a, b) -> null));
		assertTrue(m3.equals(m2.mergeWith(m3, (a, b) -> b)));

	}

	@Test
	public void regressionCreateWithDuplicateEntries() {
		MapEntry<Long, Long> e = MapEntry.create(1L, 2L);
		AMap<Long, Long> m = Maps.create(Vectors.of(e, e));
		assertEquals(1, m.size());
	}

	@Test
	public void regressionTestMerge() throws InvalidDataException {
		AHashMap<Object, Object> m = Maps.of(Blob.fromHex("798b809c"), null);
		m.validate();
		// this should remove the entry, since mergeWith removes null values
		AHashMap<Object, Object> m1 = m.mergeWith(m, (a, b) -> a);
		assertSame(Maps.empty(), m1);

		CollectionsTest.doMapTests(m);
	}

	@Test
	public void testDuplicateEntryCreate() {
		AMap<Integer, Integer> m = Maps.of(10, 2, 10, 3);
		assertEquals(1, m.size());
		assertEquals(10, (int) (m.entryAt(0).getKey()));
	}

	@Test
	public void testFilterHex() {
		MapLeaf<Object, Object> m = Maps.of(1, true, 2, true, 3, true, -1000, true);
		assertEquals(Maps.of(3, true), m.filterHexDigits(0, 64)); // hex digit 0 = 6 only
		assertSame(m, m.filterHexDigits(0, 0xFFFF)); // all digits selected
		assertSame(Maps.empty(), m.filterHexDigits(0, 0)); // all digits selected
	}

	private static final Predicate<Long> EVEN_PRED = a -> {
		return (a & 1L) == 0L;
	};

	@Test
	public void testFilterValues10() {
		AHashMap<Long, Long> m = Samples.LONG_MAP_10;
		AHashMap<Long, Long> m2 = m.filterValues(EVEN_PRED);
		assertEquals(5, m2.size());
	}

	@Test
	public void testFilterValues100() {
		AHashMap<Long, Long> m = Samples.LONG_MAP_100;
		AHashMap<Long, Long> m2 = m.filterValues(EVEN_PRED);
		assertEquals(50, m2.size());

	}

	@Test
	public void testEquals() {
		AMap<Long, Long> m = Samples.LONG_MAP_100;
		assertNotEquals(m, m.assoc(null, null));
		assertNotEquals(m, m.assoc(2L, 3L));

		CollectionsTest.doMapTests(m);
	}

	@Test
	public void testEqualsKeys() {
		assertTrue(Maps.empty().equalsKeys(Maps.of()));
		assertFalse(Maps.of(1, 2, 3, 4).equalsKeys(Maps.of(1, 2, 4, 5)));
		assertTrue(Maps.of(1, 2, 3, 4).equalsKeys(Maps.of(1, 4, 3, 2)));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testTreeMapBuilding() {
		assertThrows(Throwable.class, () -> MapTree.create(new MapEntry[] { MapEntry.create(1, 2) }, 0));
	}

	@Test
	public void testMapEntry() {
		AMap<Long, Long> m = Maps.of(1L, 2L);
		MapEntry<Long, Long> me = m.getEntry(1L);
		assertEquals(1L, me.getKey());
		assertEquals(2L, me.getValue());

		assertThrows(UnsupportedOperationException.class, () -> me.setValue(6L));

		assertEquals(me, me.assoc(0, 1L));
		assertEquals(me, me.assoc(1, 2L));
		assertThrows(IndexOutOfBoundsException.class, () -> me.assoc(-1, 0L));
		assertThrows(IndexOutOfBoundsException.class, () -> me.assoc(2, 3L));

		assertTrue(me.contains(1L));
		assertTrue(me.contains(2L));
		assertFalse(me.contains(true));
		assertFalse(me.contains(null));

		// generic tests for MapEntry treated as a vector
		VectorsTest.doVectorTests(me);
	}

	@Test
	public void testAssocs() {
		AMap<Long, Long> m = Maps.of(1L, 2L);
		assertSame(m, m.assoc(1L, 2L));

		CollectionsTest.doMapTests(m);
	}

	@Test
	public void testConj() {
		AMap<Long, Long> m = Maps.of(1L, 2L);
		AMap<Long, Long> me = Maps.of(1L, 2L, 3L, 4L);
		assertEquals(m, m.conj(Vectors.of(1L, 2L)));
		assertEquals(me, m.conj(Vectors.of(3L, 4L)));
		assertEquals(me, m.conj(MapEntry.create(3L, 4L)));

		// failures with conj'ing things that aren't valid map entries
		assertNull(m.conj(Vectors.empty()));
		assertNull(m.conj(Vectors.of(1L)));
		assertNull(m.conj(Vectors.of(1L, 2L, 3L)));
		assertNull(m.conj(null));

		CollectionsTest.doMapTests(me);

	}

	@Test
	public void testMergeWith() {
		AHashMap<Long, Long> m = Maps.of(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L, 7L, 8L, 8L);
		AHashMap<Long, Long> m2 = m.mergeWith(m, (a, b) -> ((a & 1L) == 0L) ? a : null);
		assertEquals(4, m2.size());

		AHashMap<Object, Object> bm = Maps.coerce(Samples.LONG_MAP_100);
		AHashMap<Object, Object> sm = Maps.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		AHashMap<Object, Object> bm2 = bm.mergeWith(sm, (a, b) -> {
			return (a == null) ? b : a;
		});
		assertEquals(105, bm2.count());
		assertEquals(bm2, sm.mergeWith(bm, (a, b) -> {
			return (a == null) ? b : a;
		}));

		CollectionsTest.doMapTests(m);
		CollectionsTest.doMapTests(m2);
	}
}
