package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.test.Samples;

public class ListsTest {
	@Test
	public void testEdn() {
		assertEquals("(1 2)", Lists.of(1L, 2L).toString());
	}

	@Test
	public void testEmptyList() {
		AList<ACell> e = Lists.empty();
		assertEquals(0, e.size());
		assertSame(e, Lists.of());
		assertFalse(e.contains(null));
		doListTests(e);
	}

	@Test
	public void testPrimitiveEquality() {
		assertEquals(Lists.of(1L), Lists.of(1L));
	}

	@Test
	public void testListToArray() {
		assertEquals(3, Lists.of(1, 2, 3).toArray().length);
		assertEquals(0, Lists.empty().toArray().length);
	}
	
	@Test public void testDrop() {
		assertSame(Lists.empty(), Lists.empty().drop(0));
		assertNull(Lists.empty().drop(1));
		
		AList<CVMLong> ll=Lists.of(1L, 2L, 3L);
		
		assertSame(ll,ll.drop(0));
		assertSame(Lists.empty(),ll.drop(3));
		
		assertEquals(Lists.of(2L,3L),ll.drop(1));
		assertEquals(Lists.of(3L),ll.drop(2));
		assertNull(ll.drop(5));
		
		assertEquals(Lists.of(299),Samples.INT_LIST_300.drop(299));
		assertNull(Samples.INT_LIST_300.drop(400));
	}
	
	@Test
	public void testToString() {
		assertEquals("(1 2 3)",Lists.of(1L, 2L, 3L).toString());
	}

	@Test
	public void testContainsAll() {
		assertTrue(Lists.of(1, 2, 3).containsAll(Sets.of(2, 3)));
		assertFalse(Lists.of(1, 2).containsAll(Sets.of(2, 3, 4)));
	}

	@Test
	public void testGenericListSamples() {
		doListTests(Lists.of(1, 2L, Vectors.empty()));
		doListTests(Samples.INT_LIST_10);
		doListTests(Samples.INT_LIST_300);
	}

	/**
	 * Generic tests for any list
	 * @param a Any List
	 */
	public static <T extends ACell> void doListTests(AList<T> a) {
		long n = a.count();

		if (n == 0) {
			assertSame(Lists.empty(), a);
		} else {
			T first = a.get(0);
			assertEquals(first, a.iterator().next());
		}

		assertEquals(a, Lists.of(a.toArray()));

		// call inherited sequence tests
		CollectionsTest.doSequenceTests(a);
	}
}
