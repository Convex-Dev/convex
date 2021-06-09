package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Iterator;
import java.util.ListIterator;
import java.util.Map;

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

		if (n > 0) {
			T last = a.get(n - 1);
			T first = a.get(0);
			assertEquals(n - 1, a.longLastIndexOf(last));
			assertEquals(0L, a.longIndexOf(first));

			assertSame(a, a.assoc(0, first));
			assertSame(a, a.assoc(n - 1, last));
		}
		
		// Out of range assocs should return null
		assertNull( a.assoc(-2, null));
		assertNull( a.assoc(n + 2, null));

		ListIterator<T> it = a.listIterator();
		assertThrows(UnsupportedOperationException.class, () -> it.set(null));
		assertThrows(UnsupportedOperationException.class, () -> it.add(null));

		assertThrows(IndexOutOfBoundsException.class, () -> a.listIterator(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.listIterator(n + 1));

		assertThrows(IndexOutOfBoundsException.class, () -> a.getElementRef(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.getElementRef(n));

		doCollectionTests(a);
	}

	/**
	 * Generic tests for any data structure
	 * @param a Any Data Structure
	 */
	public static <T extends ACell> void doDataStructureTests(ADataStructure<T> a) {
		long n = a.count();
		if (n == 0) {
			assertSame(a.empty(), a);
		}

		ObjectsTest.doAnyValueTests(a);
	}

	/**
	 * Generic tests for any collection
	 * @param a Any Collection
	 */
	public static <T extends ACell> void doCollectionTests(ACollection<T> a) {
		Iterator<T> it = a.iterator();
		assertThrows(Throwable.class, () -> it.remove());

		doDataStructureTests(a);
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

			// remove and add back entry
			AMap<K, V> da = a.dissoc(key);
			assertEquals(n - 1, da.count());
			assertNull(da.getEntry(key));
			assertEquals(a, da.assocEntry(me));
		}
		
		{ // test that entrySet works properly
			java.util.Set<Map.Entry<K, V>> es=a.entrySet();
			assertEquals(es.size(),a.size());
			AMap<K, V> t=a;
			for (Map.Entry<K, V> me: es) {
				t=t.dissoc(me.getKey());
			}
			assertSame(a.empty(),t);
		}
		
		assertThrows(IndexOutOfBoundsException.class, () -> a.entryAt(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.entryAt(n));

		doDataStructureTests(a);
	}

	/**
	 * Generic tests for any set
	 * @param a Any Set
	 */
	public static <V extends ACell> void doSetTests(ASet<V> a) {
		doCollectionTests(a);
	}
}
