package convex.core.data;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertEqualityCachesNothing;
import static convex.test.Assertions.assertEqualityHashesRoots;
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
import convex.core.util.MergeFunction;
import convex.test.Samples;

/**
 * Tests for CVM Map data structures.
 */
public class MapsTest {

	@Test
	public void testTreeEquality() {
		// An embedded tree compares its children: nothing is encoded or hashed
		AMap<CVMLong, CVMLong> a = Maps.empty(), b = Maps.empty(), c = Maps.empty();
		for (long i = 0; i < 17; i++) {
			CVMLong k=CVMLong.create(i);
			a = a.assoc(k, k);
			b = b.assoc(k, k);
			c = c.assoc(k, (i==16)?CVMLong.create(-1):k);
		}
		assertTrue(a instanceof MapTree);
		assertTrue(a.isEmbedded());
		assertEqualityCachesNothing(a,b,c);

		// A branch tree compares by hash, which is kept
		a = Maps.empty(); b = Maps.empty(); c = Maps.empty();
		for (long i = 0; i < 300; i++) {
			CVMLong k=CVMLong.create(1000000+i);
			a = a.assoc(k, k);
			b = b.assoc(k, k);
			c = c.assoc(k, (i==299)?CVMLong.create(-1):k);
		}
		assertFalse(a.isEmbedded());
		assertEqualityHashesRoots(a,b,c);

		// Same shape, one embedded and one branch: unequal whichever side decides the path
		AMap<CVMLong, ACell> small = Maps.empty(), big = Maps.empty(), big2 = Maps.empty();
		for (long i = 0; i < 17; i++) {
			CVMLong k=CVMLong.create(i);
			small = small.assoc(k, k);
			big = big.assoc(k, Strings.create("value number "+i+" padded to be long enough to make the tree a branch"));
			big2 = big2.assoc(k, Strings.create("value number "+i+" padded to be long enough to make the tree a branch"));
		}
		assertTrue(small.isEmbedded()); // small knows, big does not
		assertFalse(small.equals(big));
		assertFalse(big.equals(small));
		assertFalse(big2.isEmbedded()); // big2 knows
		assertFalse(big2.equals(small));
		assertFalse(small.equals(big2));
		assertEquals(big,big2);
	}

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
	public void testGetIn() {
		assertEquals(CVMLong.ONE,Maps.of(2,1).getIn(2));
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

	/**
	 * Removals that leave more than a leaf's entries, all under one hash prefix, give the
	 * map of those entries: a tree has at least two children (CAD003)
	 */
	@Test
	public void testRemovalsLeavingOneHashPrefix() {
		// Prefix of one digit collapses the root; of two digits, the level below it too
		for (int digits=1; digits<=2; digits++) {
			AHashMap<CVMLong, CVMLong> kept = Maps.empty();
			AHashMap<CVMLong, CVMLong> m = Maps.empty();
			AHashMap<CVMLong, CVMLong> changed = Maps.empty(); // every key, another value
			for (long i=0; kept.count()<=MapLeaf.MAX_ENTRIES+3; i++) {
				CVMLong k = CVMLong.create(i);
				Hash h = k.getHash();
				boolean keep = (h.getHexDigit(0)==0) && ((digits==1)||(h.getHexDigit(1)==0));
				if (keep) kept = kept.assoc(k, CVMLong.ONE);
				m = m.assoc(k, keep ? CVMLong.ONE : CVMLong.ZERO);
				changed = changed.assoc(k, CVMLong.create(2));
			}
			MergeFunction<CVMLong> keepOnes = (a, b) -> ((a!=null)&&(a.longValue()==1)) ? a : null;
			for (AHashMap<CVMLong, CVMLong> r : java.util.List.of(
					m.filterValues(v -> v.longValue()==1),
					m.mapEntries(e -> (e.getValue().longValue()==1) ? e : null),
					m.mergeDifferences(changed, keepOnes),
					m.mergeWith(changed, keepOnes))) {
				assertEquals(kept, r);
				ObjectsTest.doAnyValueTests(r);
			}
		}
	}

	/**
	 * Merging hash maps whose trees branch at different hex digits or under different prefixes,
	 * at the root or below it, gives the map built by assoc (CAD003)
	 */
	@Test
	public void testMergesAcrossTreeShapes() throws InvalidDataException {
		java.util.List<java.util.List<CVMLong>> shapes=TreeShapes.SHAPES;
		MergeFunction<CVMLong> preferFirst=(a,b)->(a!=null)?a:b;
		MergeFunction<CVMLong> both=(a,b)->((a!=null)&&(b!=null))?a:null;
		MergeFunction<CVMLong> sum=(a,b)->(a==null)?b:((b==null)?a:CVMLong.create(a.longValue()+b.longValue()));
		for (int i=0; i<shapes.size(); i++) {
			for (int j=0; j<shapes.size(); j++) {
				// Shapes of the same parity give a shared key the same value
				java.util.Map<CVMLong,CVMLong> xs=shapeValues(shapes.get(i),i), ys=shapeValues(shapes.get(j),j);
				AHashMap<CVMLong,CVMLong> x=assocAll(xs), y=assocAll(ys);
				for (MergeFunction<CVMLong> f: java.util.List.of(preferFirst,both,sum)) {
					assertMergeResult(expectedMerge(xs,ys,f,false),x.mergeWith(y,f));
				}
				for (MergeFunction<CVMLong> f: java.util.List.of(preferFirst,both)) {
					assertMergeResult(expectedMerge(xs,ys,f,true),x.mergeDifferences(y,f));
				}
			}
		}

		// An unchanged result is the receiver: a submap under one child, and a map sharing no prefix
		AHashMap<CVMLong,CVMLong> holding=assocAll(shapeValues(shapes.get(8),8));
		AHashMap<CVMLong,CVMLong> sub=assocAll(shapeValues(shapes.get(1),1));
		assertSame(holding,holding.mergeWith(sub,preferFirst));
		assertSame(holding,holding.mergeDifferences(sub,preferFirst));
		MergeFunction<CVMLong> firstOnly=(a,b)->(b==null)?a:null;
		AHashMap<CVMLong,CVMLong> other=assocAll(shapeValues(shapes.get(2),2));
		assertSame(sub,sub.mergeWith(other,firstOnly));
		assertSame(sub,sub.mergeDifferences(other,firstOnly));
	}

	private static java.util.Map<CVMLong,CVMLong> shapeValues(java.util.List<CVMLong> keys, int shape) {
		java.util.Map<CVMLong,CVMLong> m=new java.util.LinkedHashMap<>();
		for (CVMLong k: keys) m.put(k,CVMLong.create(k.longValue()+(shape%2)));
		return m;
	}

	@Test
	public void testOrdinaryMergeAcrossTreeShapes() throws Exception {
		var shapes = TreeShapes.SHAPES;
		for (int i = 0; i < shapes.size(); i++) {
			for (int j = 0; j < shapes.size(); j++) {
				var xs = shapeValues(shapes.get(i), i);
				var ys = shapeValues(shapes.get(j), j);
				// Presence must be distinguished from nil, on either side and at any depth.
				xs.replaceAll((k, v) -> (k.longValue() % 3 == 0) ? null : v);
				ys.replaceAll((k, v) -> (k.longValue() % 2 == 0) ? null : v);
				AHashMap<CVMLong, CVMLong> x = assocAll(xs), y = assocAll(ys);
				xs.putAll(ys);
				assertOrdinaryMerge(assocAll(xs), x, y);
			}
		}
	}

	@Test
	public void testOrdinaryMergeLeafBoundary() throws Exception {
		for (int a : new int[] {0, 1, 7, 14, 15, 16, 17, 100}) {
			for (int b : new int[] {0, 1, 7, 14, 15, 16, 17, 100}) {
				for (int offset : new int[] {0, 5, 100}) {
					AHashMap<ACell, ACell> x = Maps.empty(), y = Maps.empty();
					for (int i = 0; i < a; i++) x = x.assoc(CVMLong.create(i), CVMLong.ONE);
					for (int i = 0; i < b; i++) y = y.assoc(CVMLong.create(i + offset), (i % 2 == 0) ? null : CVMLong.ZERO);
					AHashMap<ACell, ACell> expected = x;
					for (long i = 0; i < y.count(); i++) expected = expected.assocEntry(y.entryAt(i));
					assertOrdinaryMerge(expected, x, y);
					// Nil keys are values too; exercise them in both leaves and trees.
					assertOrdinaryMerge(expected.assoc(null, null), x.assoc(null, CVMLong.ONE), y.assoc(null, null));
					assertSame(x, x.merge(x));
					assertSame(x, x.merge(Maps.empty()));
					assertSame(x, Maps.<ACell, ACell, AHashMap<ACell, ACell>>empty().merge(x));
				}
			}
		}
	}

	private static <K extends ACell, V extends ACell> void assertOrdinaryMerge(
			AHashMap<K, V> expected, AHashMap<K, V> x, AHashMap<K, V> y) throws Exception {
		// Exercise dispatch through the AMap overload used by CVM merge.
		AHashMap<K, V> result = x.merge((AMap<K, V>) y);
		assertEquals(expected, result);
		assertEquals(expected.getEncoding(), result.getEncoding());
		assertEquals(expected.getHash(), result.getHash());
		result.validate();
		assertEquals(result, Samples.TEST_STORE.decodeMultiCell(Format.encodeMultiCell(result, true)));
		if (expected.equals(x)) assertSame(x, result);
	}

	@Test
	public void testOrdinaryMergeReusesUnchangedBranches() {
		AHashMap<CVMLong, CVMLong> original = Maps.empty();
		for (int i = 0; i < 1024; i++) original = original.assoc(CVMLong.create(i), CVMLong.ONE);
		AHashMap<CVMLong, CVMLong> changed = original.assoc(CVMLong.ZERO, null);
		AHashMap<CVMLong, CVMLong> merged = original.merge(changed);
		assertEquals(changed, merged);
		int reused = 0;
		for (int i = 0; i < original.getRefCount(); i++) {
			if (original.getRef(i) == changed.getRef(i)) {
				assertSame(original.getRef(i), merged.getRef(i));
				reused++;
			}
		}
		assertEquals(original.getRefCount() - 1, reused);
	}

	private static AHashMap<CVMLong,CVMLong> assocAll(java.util.Map<CVMLong,CVMLong> m) {
		AHashMap<CVMLong,CVMLong> result=Maps.empty();
		for (java.util.Map.Entry<CVMLong,CVMLong> e: m.entrySet()) result=result.assoc(e.getKey(),e.getValue());
		return result;
	}

	/** The merge by its contract: the function for each key, or with differences only, each key whose values differ */
	private static AHashMap<CVMLong,CVMLong> expectedMerge(java.util.Map<CVMLong,CVMLong> xs, java.util.Map<CVMLong,CVMLong> ys, MergeFunction<CVMLong> f, boolean differences) {
		java.util.Set<CVMLong> keys=new java.util.LinkedHashSet<>(xs.keySet());
		keys.addAll(ys.keySet());
		java.util.Map<CVMLong,CVMLong> merged=new java.util.LinkedHashMap<>();
		for (CVMLong k: keys) {
			CVMLong a=xs.get(k), b=ys.get(k);
			CVMLong v=(differences&&java.util.Objects.equals(a,b))?a:f.merge(a,b);
			if (v!=null) merged.put(k,v);
		}
		return assocAll(merged);
	}

	private static void assertMergeResult(AHashMap<CVMLong,CVMLong> expected, AHashMap<CVMLong,CVMLong> result) throws InvalidDataException {
		assertEquals(expected,result);
		result.validate();
	}

	/**
	 * mergeDifferences of a leaf with a tree, at the root or as children below it, calls the
	 * function only for keys whose values differ, and gives the receiver itself when the result
	 * equals it
	 */
	@Test
	public void testLeafMergeDifferencesWithTree() {
		AHashMap<CVMLong,CVMLong> leaf=Maps.empty(), small=Maps.empty(), big=Maps.empty();
		for (long i=0; i<300; i++) {
			CVMLong k=CVMLong.create(i);
			if (i<5) leaf=leaf.assoc(k,k);
			if (i<40) small=small.assoc(k,k);
			big=big.assoc(k,k);
		}
		assertTrue(leaf instanceof MapLeaf);
		// The small tree's children are leaves, the big tree's are trees
		assertTrue(small instanceof MapTree);
		assertTrue(big instanceof MapTree);

		java.util.List<CVMLong> calledOnBoth=new java.util.ArrayList<>();
		MergeFunction<CVMLong> firstOnly=new MergeFunction<>() {
			@Override public CVMLong merge(CVMLong a, CVMLong b) {
				if ((a!=null)&&(b!=null)) calledOnBoth.add(a);
				return (b==null)?a:null;
			}
		};
		// Every shared key has the same value, so only the receiver's keys remain
		assertSame(leaf,leaf.mergeDifferences(big,firstOnly));
		assertSame(small,small.mergeDifferences(big,firstOnly));
		assertTrue(calledOnBoth.isEmpty());

		// mergeWith too gives the receiver itself when the result equals it
		MergeFunction<CVMLong> both=(a,b)->((a!=null)&&(b!=null))?a:null;
		assertSame(leaf,leaf.mergeWith(big,both));
		assertSame(small,small.mergeWith(big,both));
	}

	/**
	 * Key subset tests between hash maps whose trees branch at different hex digits or under
	 * different prefixes, at the root or below it, answer by the keys
	 */
	@Test
	public void testContainsAllKeysAcrossTreeShapes() {
		java.util.List<java.util.List<CVMLong>> shapes=TreeShapes.SHAPES;
		for (int i=0; i<shapes.size(); i++) {
			for (int j=0; j<shapes.size(); j++) {
				AHashMap<CVMLong,CVMLong> x=assocAll(shapeValues(shapes.get(i),i)), y=assocAll(shapeValues(shapes.get(j),j));
				assertEquals(shapes.get(i).containsAll(shapes.get(j)),x.containsAllKeys(y));
			}
		}
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
		AMap<CVMLong,ATransaction> m2=Samples.TEST_STORE.decode(b);

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
			AHashMap<K, V> updated=m.update(firstKey,v -> null);
			assertEquals(m.assoc(firstKey,null),updated);


			// Test a smaller version of this map
			AHashMap<K, V> smaller = m.dissoc(firstKey);
			assertEquals(n-1,smaller.count());
			assertTrue(m.containsAllKeys(m));
			assertFalse(smaller.containsAllKeys(m));
		}
		
		CollectionsTest.doMapTests(m);
	}
}
