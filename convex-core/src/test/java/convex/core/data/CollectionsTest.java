package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.lang.RT;

/**
 * Tests for general collection types
 */
public class CollectionsTest {
	
	/**
	 * Generic tests for any sequence
	 * @param a Any Sequence Value
	 */
	public static <T extends ACell> void doSequenceTests(ASequence<T> a) {
		long n = a.count();

		ACell[] cells=a.toCellArray();
		assertEquals(n,cells.length);
		assertEquals(a.toVector(),RT.vec(cells));

		if (n > 0) {
			T last = a.get(n - 1);
			T first = a.get(0);
			assertEquals(n - 1, a.longLastIndexOf(last));
			assertEquals(0L, a.longIndexOf(first));
			
			assertEquals(n - 1, a.lastIndexOf(last));
			assertEquals(0L, a.indexOf(first));

			assertEquals(a, a.assoc(0, first));
			assertEquals(a, a.assoc(n - 1, last));
			
			assertSame(first,cells[0]);
			assertSame(last,cells[(int) (n-1)]);
			
			assertEquals(Ref.get(last),a.getElementRef(n-1));
			
			assertEquals(a,a.reversed().reversed());
		}
		
		ASequence<T> empty=a.empty();
		if (a.isCanonical()) {
			assertSame(a,a.concat(empty));
		} else {
			assertEquals(a,a.concat(empty));		
		}
		
		// Out of range assocs should return null
		assertNull( a.assoc(-2, null));
		assertNull( a.assoc(n + 2, null));
		
		// TODO: should always be invalid slice = null??
 		// assertNull(a.slice(-1,n));

		ListIterator<T> it = a.listIterator();
		assertThrows(UnsupportedOperationException.class, () -> it.set(null));
		assertThrows(UnsupportedOperationException.class, () -> it.add(null));

		assertThrows(IndexOutOfBoundsException.class, () -> a.listIterator(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.listIterator(n + 1));

		assertThrows(IndexOutOfBoundsException.class, () -> a.getElementRef(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.getElementRef(n));

		doCollectionTests(a);
	}

	static final Keyword UNLIKELY_KEYWORD=Keyword.create("this-is-not-likely-to-happen-at-random");
	
	public static <T extends ACell> void doCountableTests(ACountable<T> a) {
		long n = a.count();
		
		ACountable<T> empty=a.empty();
		if (empty==null) {
			// doesn't support emptying, so must be a symbol, Address  or keyword
			assertTrue((a instanceof Keyword)||(a instanceof Symbol)||(a instanceof Address));
		} else {
			assertEquals(0,empty.count());
			
			// Canonical version of any empty structure should be internal
			RefTest.checkInternal(empty.toCanonical());
		}
		
 		if (n == 0) {
			assertEquals(empty, a);
		} else {
			assertFalse(a.isEmpty());
			T first =a.get(0);
			assertEquals(first,a.getElementRef(0).getValue());
			assertNotSame(empty,a);
		}
 		
		ObjectsTest.doAnyValueTests(a);
	}
	
	/**
	 * Generic tests for any data structure
	 * @param a Any Data Structure
	 */
	public static <T extends ACell> void doDataStructureTests(ADataStructure<T> a) {
		long n = a.count();
		
		assertFalse(RT.bool(a.get(UNLIKELY_KEYWORD))); 
		assertSame(Keywords.FOO,a.get(UNLIKELY_KEYWORD,Keywords.FOO));
		
		assertEquals(n,a.size());

		doCountableTests(a);
	}

	/**
	 * Generic tests for any collection
	 * @param a Any Collection
	 */
	public static <T extends ACell> void doCollectionTests(ACollection<T> a) {
		doCollIteratorTests(a);

		doDataStructureTests(a);
	}

	public static <T extends ACell> void doCollIteratorTests(ACollection<T> a) {
		Iterator<T> it = a.iterator();
		
		// Can't remove
		assertThrows(Throwable.class, () -> it.remove());
		
		long n=a.count();
		assertEquals(n>0,it.hasNext());
		for (int i=0; i<n; i++) {
			ACell e = a.get(i);
			ACell ie=it.next();
			assertEquals(e,ie);
		}
		assertFalse(it.hasNext());
	}

	/**
	 * Generic tests for any map
	 * @param a Any Map
	 */
	public static <K extends ACell, V extends ACell> void doMapTests(AMap<K, V> a) {
		long n = a.count();
		if (n == 0) {
			assertThrows(IndexOutOfBoundsException.class, () -> a.entryAt(0));
		} else {
			MapEntry<K, V> me = a.entryAt(n / 2);
			assertNotNull(me);
			assertSame(a, a.assocEntry(me));

			K key = me.getKey();
			V value = me.getValue();

			assertEquals(a.get(key), value);
			assertEquals(a.getIn(key), value);

			// remove and add back entry
			AMap<K, V> da = a.dissoc(key);
			assertEquals(n - 1, da.count());
			assertNull(da.getEntry(key));
			assertEquals(a, da.assocEntry(me));
		}
		
		doMapEntrySetTests(a);
		
		{ // test that keySet works properly
			java.util.Set<K> ks=a.keySet();
			assertEquals(ks.size(),a.size());
			AMap<K, V> t=a;
			for (K k: ks) {
				t=t.dissoc(k);
			}
			assertSame(a.empty(),t);
			
		}
		
		assertThrows(IndexOutOfBoundsException.class, () -> a.entryAt(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.entryAt(n));

		doDataStructureTests(a);
	}

	/**
	 * Generic tests for the Java {@link Map#entrySet()} view of any Convex map.
	 *
	 * @param a Any Convex map
	 */
	public static <K extends ACell, V extends ACell> void doMapEntrySetTests(AMap<K, V> a) {
		long n = a.count();
		Set<Map.Entry<K, V>> entries = a.entrySet();

		assertEquals(a.size(), entries.size());
		assertEquals(a.isEmpty(), entries.isEmpty());
		assertFalse(entries.contains("not an entry"));

		Iterator<Map.Entry<K, V>> iterator = entries.iterator();
		AMap<K, V> remaining = a;
		long seen = 0L;
		while (iterator.hasNext()) {
			Map.Entry<K, V> entry = iterator.next();
			MapEntry<K, V> expected = a.getEntry(entry.getKey());

			assertNotNull(expected);
			assertEquals(expected.getKey(), entry.getKey());
			assertEquals(expected.getValue(), entry.getValue());
			assertTrue(entries.contains(entry));

			remaining = remaining.dissoc(entry.getKey());
			seen++;
		}

		assertEquals(n, seen);
		assertFalse(iterator.hasNext());
		assertThrows(NoSuchElementException.class, iterator::next);
		assertSame(a.empty(), remaining);

		if (n > 0L) {
			MapEntry<K, V> entry = a.entryAt(n / 2L);
			assertTrue(entries.contains(entry));
			assertThrows(UnsupportedOperationException.class, () -> entry.setValue(entry.getValue()));
		}
	}
}
