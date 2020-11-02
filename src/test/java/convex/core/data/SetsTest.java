package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.test.Samples;

public class SetsTest {
	@Test
	public void testEdn() {
		assertEquals("#{1,2}", Sets.of(1L, 2L).toString());
	}

	@Test
	public void testEmptySet() {
		ASet<Object> e = Sets.empty();
		assertEquals(0, e.size());
		assertFalse(e.contains(null));
	}

	@Test
	public void testIncludeExclude() {
		ASet<Object> s = Sets.empty();
		assertEquals("#{}", s.toString());
		s = s.include(1L);
		assertEquals("#{1}", s.toString());
		s = s.include(1L);
		assertEquals("#{1}", s.toString());
		s = s.include(2L);
		assertEquals("#{1,2}", s.toString());
		s = s.exclude(1L);
		assertEquals("#{2}", s.toString());
		s = s.exclude(2L);
		assertTrue(s.isEmpty());
		assertSame(s, Sets.empty());
	}

	@Test
	public void testPrimitiveEquality() {
		assertEquals(Sets.of(1, 1L), Sets.of((Object) 1).include(1L));
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
		Set<Integer> EM=Sets.empty();
		assertTrue(EM.isSubset(EM));
		assertTrue(EM.isSubset(Samples.INT_SET_300));
		assertTrue(EM.isSubset(Samples.INT_SET_10));
		assertFalse(Samples.INT_SET_10.isSubset(EM));
		assertFalse(Samples.INT_SET_300.isSubset(EM));

		assertTrue(Samples.INT_SET_300.isSubset(Samples.INT_SET_300));
		assertTrue(Samples.INT_SET_10.isSubset(Samples.INT_SET_300));
		assertTrue(Samples.INT_SET_10.isSubset(Samples.INT_SET_10));
		assertFalse(Samples.INT_SET_300.isSubset(Samples.INT_SET_10));
	}

	@Test
	public void testMerging() {
		ASet<Integer> a = Sets.of(1, 2, 3);
		ASet<Integer> b = Sets.of(2, 4, 6);
		assertTrue(a.contains(3));
		assertFalse(b.contains(3));

		assertSame(Sets.empty(), a.disjAll(a));
		assertEquals(Sets.of(1, 2, 3, 4, 6), a.conjAll(b));
		assertEquals(Sets.of(1, 3), a.disjAll(b));
	}
	
	@Test 
	public void regressionRead() throws BadFormatException {
		ASet<Integer> v1=Sets.of(43);
		Blob b1 = Format.encodedBlob(v1);
		
		ASet<Integer> v2=Format.read(b1);
		Blob b2 = Format.encodedBlob(v2);
		
		assertEquals(v1, v2);
		assertEquals(b1,b2);
	}

	@Test
	public void regressionNils() throws InvalidDataException {
		AMap<Object, Object> m = Maps.of(null, null);
		assertEquals(1, m.size());
		assertTrue(m.containsKey(null));

		ASet<Object> s = Sets.of(m);
		s.validate();
		s = s.include((Object) m);
		s.validate();
	}

	@Test
	public void testMergingIdentity() {
		ASet<Integer> a = Sets.of(1, 2, 3);
		assertTrue(a == a.include(2));
		assertTrue(a == a.includeAll(Sets.of(1, 3)));
	}
	
	@Test
	public void testIntersection() {
		ASet<Integer> a = Sets.of(1, 2, 3);
		
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
	public void testBigMerging() {
		ASet<Integer> s = Sets.create(Samples.INT_VECTOR_300);
		CollectionsTest.doSetTests(s);

		ASet<Integer> s2 = s.includeAll(Sets.of(1, 2, 3, 100));
		assertEquals(s, s2);
		assertSame(s, s2);

		ASet<Integer> s3 = s.disjAll(Samples.INT_VECTOR_300);
		assertSame(s3, Sets.empty());

		ASet<Integer> s4 = s.excludeAll(Sets.of(-1000));
		assertSame(s, s4);

		ASet<Integer> s5a = Sets.of(1, 3, 7, -1000);
		ASet<Integer> s5 = s5a.disjAll(s);
		assertEquals(Sets.of(-1000), s5);
	}

	@Test
	public void testBadStructure() {
		AHashMap<Long, Object> m = Maps.of(1L, true, 3L, false);
		Set<Long> s = Set.wrap(m);

		// should not be identical, different hashes
		assertNotEquals(m, Sets.of(1L, 3L));

		// should fail validation
		assertThrows(InvalidDataException.class, () -> s.validate());
	}
}
