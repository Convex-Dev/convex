package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.test.Samples;

public class ListsTest {

	@Test
	public void testEmptyList() throws BadFormatException {
		List<ACell> e = Lists.empty();
		
		// size is zero
		assertEquals(0, e.size());
		
		// Empty list constructor
		assertSame(e, Lists.of());
		
		// Doesn't contain the null value
		assertFalse(e.contains(null));
		
		// Equivalent to wrapping empty vector as a list
		assertEquals(e,List.wrap(Vectors.empty()));
		
		// Not equal to empty vector
		assertFalse(e.equals(Vectors.empty()));
		
		// Encoding should be list tag plus zero for VLC length count
		Blob expectedEncoding=Blob.create(new byte[] {Tag.LIST,0});
		assertEquals(expectedEncoding,e.getEncoding());
		assertSame(e,Format.read(expectedEncoding));
		
		// Reverse gets back to empty vector
		assertSame(Vectors.empty(),e.reverse());
		
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
	public void testConcat() {
		AList<ACell> l2=Lists.of(1,2);
		assertSame(l2,l2.concat(Lists.empty()));
		assertSame(l2,Lists.empty().concat(l2));
		assertSame(l2,l2.concat(Vectors.empty()));
				
		assertEquals(Lists.of(1,2,3,4,5),l2.concat(Lists.of(3,4,5)));
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
	
	@Test 
	public void testToArray() {
		CVMLong[] arr=new CVMLong[] {CVMLong.ZERO, CVMLong.ONE};
		
		CVMLong[] buf=new CVMLong[0];
		
		List<CVMLong> l=List.create(arr.clone());
		buf=l.toArray(buf);
		
		assertArrayEquals(buf,arr);
	}

	/**
	 * Generic tests for any list
	 * @param a Any List
	 */
	public static <T extends ACell> void doListTests(AList<T> a) {
		long n = a.count();

		if (n == 0) {
			assertSame(Lists.empty(), a);
			
			 // empty list shouldn't contain anything (including itself)
			assertEquals(-1,a.indexOf(a));
			assertEquals(-1,a.lastIndexOf(null));
		} else {
			T first = a.get(0);
			assertEquals(first, a.iterator().next());
			assertEquals(0,a.indexOf(first));
		}

		assertEquals(a, Lists.of(a.toArray()));

		// call inherited sequence tests
		CollectionsTest.doSequenceTests(a);
	}
}
