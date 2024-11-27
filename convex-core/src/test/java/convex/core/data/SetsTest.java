package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.test.Samples;

/**
 * Tests for general set behaviour and logic
 */
public class SetsTest {

	@Test
	public void testEmptySet() {
		ASet<ACell> e = Sets.empty();
		assertEquals(0, e.size());
		assertFalse(e.contains(null));
		assertSame(e,Sets.create(Vectors.empty()));
		assertSame(e,Sets.of(1).exclude(CVMLong.ONE));
	}

	@Test
	public void testIncludeExclude() {
		ASet<ACell> s = Sets.empty();
		assertEquals("#{}", s.toString());
		s = s.include(RT.cvm(1L));
		assertEquals("#{1}", s.toString());
		s = s.include(RT.cvm(1L));
		assertEquals("#{1}", s.toString());
		s = s.include(RT.cvm(2L));
		assertEquals("#{2,1}", s.toString());
		assertSame(s,s.exclude(null));
		
		s = s.exclude(RT.cvm(1L));
		assertEquals("#{2}", s.toString());
		s = s.exclude(RT.cvm(2L));
		assertSame(Sets.empty(),s);
		
		s = s.exclude(RT.cvm(2L));
		assertSame(Sets.empty(),s);
	}
	
	@Test 
	public void testSetEncoding() {
		// Set should be encoded as a map with different tag and extra value Ref(s)
		ASet<?> s=Sets.of(123);
		AMap<?,?> m=Maps.of(123,null);
		// compare encodings ignoring tag
		assertEquals(m.getEncoding().slice(1),s.getEncoding().append(Blob.SINGLE_ZERO).slice(1));
	}

	@Test
	public void testPrimitiveEquality() {
		// different primitive objects with same numeric value should not collide in set
		CVMDouble b=CVMDouble.create(1);
		ASet<ACell> s=Sets.of(1L).include(b);
		assertEquals(2L,s.count());
		
		assertEquals(Sets.of(b, 1L), s);
	}

	@Test
	public void testSetToArray() {
		assertEquals(3, Sets.of(1, 2, 3).toArray().length);
		assertEquals(0, Sets.empty().toArray().length);
	}

	@Test
	public void testContainsAll() {
		assertTrue(Sets.of(1, 2, 3).containsAll(Sets.of(2, 3)));
		assertFalse(Sets.of(1, 2).containsAll(Sets.of(2, 3, 4)));
	}
	
	@Test
	public void testSubsets() {
		ASet<CVMLong> EM=Sets.empty();
		assertTrue(EM.isSubset(EM));
		assertTrue(EM.isSubset(Samples.INT_SET_300));
		assertTrue(EM.isSubset(Samples.INT_SET_10));
		assertFalse(Samples.INT_SET_10.isSubset(EM));
		assertFalse(Samples.INT_SET_300.isSubset(EM));
		
		{
			ASet<CVMLong> s=Samples.createRandomSubset(Samples.INT_SET_300,0.5,1);
			assertTrue(s.isSubset(Samples.INT_SET_300));
		}
		{
			ASet<CVMLong> s=Samples.createRandomSubset(Samples.INT_SET_10,0.5,2);
			assertTrue(s.isSubset(Samples.INT_SET_10));
		}

		assertTrue(Samples.INT_SET_300.isSubset(Samples.INT_SET_300));
		assertTrue(Samples.INT_SET_10.isSubset(Samples.INT_SET_300));
		assertTrue(Samples.INT_SET_10.isSubset(Samples.INT_SET_10));
		assertFalse(Samples.INT_SET_300.isSubset(Samples.INT_SET_10));
	}

	@Test
	public void testMerging() {
		ASet<CVMLong> a = Sets.of(1, 2, 3);
		ASet<CVMLong> b = Sets.of(2, 4, 6);
		assertTrue(a.contains(RT.cvm(3L)));
		assertFalse(b.contains(RT.cvm(3L)));

		assertSame(Sets.empty(), a.disjAll(a));
		ObjectsTest.doEqualityTests(Sets.of(1, 2, 3, 4, 6), a.conjAll(b));
		ObjectsTest.doEqualityTests(Sets.of(1, 3), a.disjAll(b));
	}
	
	@Test 
	public void regressionRead() throws BadFormatException {
		ASet<CVMLong> v1=Sets.of(43);
		Blob b1 = Cells.encode(v1);
		
		ASet<CVMLong> v2=Format.read(b1);
		Blob b2 = Cells.encode(v2);
		
		assertEquals(v1, v2);
		assertEquals(b1,b2);
	}

	@Test
	public void regressionNils() throws InvalidDataException {
		AMap<ACell, ACell> m = Maps.of(null, null);
		assertEquals(1, m.size());
		assertTrue(m.containsKey(null));

		ASet<ACell> s = Sets.of(m);
		s.validate();
		s = s.include( m);
		s.validate();
	}

	@Test
	public void testMergingIdentity() {
		ASet<CVMLong> a = Sets.of(1L, 2L, 3L);
		assertSame(a, a.include(RT.cvm(2L)));
		assertSame(a, a.includeAll(Sets.of(1L, 3L)));
	}
	
	@Test 
	public void testNilMembership() {
		ASet<CVMLong> a = Sets.of(1, 2, 3, null);
		assertTrue(a.containsKey(null));
		a=a.exclude(null);
		assertEquals(3,a.size());
	}
	
	@Test
	public void testIntersection() {
		ASet<CVMLong> a = Sets.of(1, 2, 3);
		
		// (intersect a a) => a
		assertSame(a,a.intersectAll(a));
		
		// (intersect a #{}) => #{}
		assertSame(Sets.empty(),a.intersectAll(Sets.of(5,6)));

		// (intersect a b) => a if (subset? a b)
		assertEquals(a,a.intersectAll(Samples.INT_SET_10));
		assertEquals(a,a.intersectAll(Samples.INT_SET_300));
		
		// regular intersection
		assertEquals(Sets.of(2,3),a.intersectAll(Sets.of(2,3,4)));

		assertThrows(Throwable.class,()->a.intersectAll(null));
	}
	
	@Test
	public void testBigSlice() {
		ASet<CVMLong> s = Sets.create(Samples.INT_VECTOR_300);
		assertEquals(300,s.count());
		assertSame(s,s.slice(0));
		assertSame(s,s.slice(0,300));
		assertSame(Sets.empty(),s.slice(19,19));
		
		ASet<CVMLong> s1=s.slice(157);
		assertEquals(300-157,s1.count());
		doSetTests(s1);
		
		ASet<CVMLong> s2=s.slice(0,13);
		assertEquals(13,s2.count());
		assertTrue(s2 instanceof SetLeaf);
		doSetTests(s2);
		
		ASet<CVMLong> s3=s.slice(13,157);
		assertEquals(157-13,s3.count());
		assertTrue(s3 instanceof SetTree);
		doSetTests(s3);
		
		ObjectsTest.doEqualityTests(s,s1.includeAll(s2).includeAll(s3));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testBigMerging() {
		ASet<CVMLong> s = Sets.create(Samples.INT_VECTOR_300);
		assertEquals(0,((SetTree<CVMLong>)s).shift);
		SetsTest.doSetTests(s);
		
		SetTree<CVMLong> child=(SetTree<CVMLong>)(s.getRef(1).getValue());
		assertEquals(1,child.shift);
		SetsTest.doSetTests(child);

		ASet<CVMLong> s2 = s.includeAll(Sets.of(1, 2, 3, 100));
		assertEquals(s, s2);
		assertSame(s, s2);

		ASet<CVMLong> s3 = s.disjAll(Samples.INT_VECTOR_300);
		assertSame(s3, Sets.empty());

		ASet<CVMLong> s4 = s.excludeAll(Sets.of(-1000));
		assertSame(s, s4);

		ASet<CVMLong> s5a = Sets.of(1, 3, 7, -1000);
		ASet<CVMLong> s5 = s5a.disjAll(s);
		ObjectsTest.doEqualityTests(Sets.of(-1000), s5);
	}
	
	@Test
	public void testIncrementalBuilding() {
		ASet<CVMLong> set=Sets.empty();
		for (int i=0; i<320; i++) {
			assertEquals(i,set.size());
			
			// extend set with one new element
			CVMLong v=CVMLong.create(i);
			ASet<CVMLong> newSet=set.conj(v);
			
			// new Set contains previous set
			assertTrue(newSet.containsAll(set));
			
			assertNotEquals(set,newSet);
			assertTrue(newSet.contains(v));
			assertFalse(set.contains(v));
			
			// removing element should get back to original set
			assertEquals(set,newSet.exclude(v));
			
			// removing original set should leave one element
			assertEquals(Sets.of(v),newSet.excludeAll(set));
			
			set=newSet;
		}
		
		doSetTests(set);

		// now build the same set in hash order
		ASet<CVMLong> set2=Sets.empty();
		for (int i=0; i<320; i++) {
			assertEquals(i,set2.size());
			
			// extend set with one new element
			CVMLong v=set.get(i);
			set2=set2.conj(v);
		}
		assertEquals(set2,set);
		doSetTests(set); // check nothing is odd
		
		// now deconstruct the set in hash order
		ASet<CVMLong> set3=set2;
		for (int i=0; i<320; i++) {
			assertEquals(320-i,set3.size());
			
			// extend set with one new element
			CVMLong v=set.get(i);
			set3=set3.exclude(v);
		}
		assertSame(Sets.EMPTY,set3);
	}
	
	/**
	 * Generic tests for any CVM set
	 * @param <T> Type of set element
	 * @param a Set to test
	 */
	public static <T extends ACell> void doSetTests(ASet<T> a) {
		assertSame(a.empty(),a.disjAll(a));
		
		if (a.isEmpty()) {
			assertSame(a.empty(),a);
		} else {
			long n=a.count();
			T first=a.get(0);
			ASet<T> butfirst=a.exclude(first);
			assertEquals(n-1,butfirst.count);
			
			ASet<T> onlyfirst=a.empty().conj(first);
			assertEquals(a,butfirst.includeAll(onlyfirst));
			assertEquals(a,onlyfirst.includeAll(butfirst));
			
			assertEquals(onlyfirst,a.excludeAll(butfirst));
		}
		
		// Fall back to generic tests for any collection
		CollectionsTest.doCollectionTests(a);
	}
}
