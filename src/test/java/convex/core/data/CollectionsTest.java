package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/**
 * Tests for general collection types
 */
public class CollectionsTest {

	/**
	 * Generic tests for any sequence
	 */
	public static <T> void doSequenceTests(ASequence<T> a) {
		long n = a.count();

		if (n > 0) {
			T last = a.get(n - 1);
			T first = a.get(0);
			assertEquals(n - 1, a.longLastIndexOf(last));
			assertEquals(0L, a.longIndexOf(first));

			assertSame(a, a.assoc(0, first));
			assertSame(a, a.assoc(n - 1, last));
		}

		ListIterator<T> it = a.listIterator();
		assertThrows(UnsupportedOperationException.class, () -> it.set(null));
		assertThrows(UnsupportedOperationException.class, () -> it.add(null));

		assertThrows(NoSuchElementException.class, () -> a.listIterator(-1));
		assertThrows(NoSuchElementException.class, () -> a.listIterator(n + 1));

		assertThrows(IndexOutOfBoundsException.class, () -> a.assoc(-2, null));
		assertThrows(IndexOutOfBoundsException.class, () -> a.assoc(n + 2, null));
		assertThrows(IndexOutOfBoundsException.class, () -> a.getElementRef(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.getElementRef(n));

		doCollectionTests(a);
	}

	/**
	 * Generic tests for any data structure
	 */
	public static <T> void doDataStructureTests(ADataStructure<T> a) {
		long n = a.count();
		if (n == 0) {
			assertSame(a.empty(), a);
		}

		ObjectsTest.doCellTests(a);
	}

	/**
	 * Generic tests for any collection
	 */
	public static <T> void doCollectionTests(ACollection<T> a) {
		Iterator<T> it = a.iterator();
		assertThrows(Throwable.class, () -> it.remove());

		doDataStructureTests(a);
	}

	/**
	 * Generic tests for any map
	 */
	public static <K, V> void doMapTests(AMap<K, V> a) {
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
		assertThrows(IndexOutOfBoundsException.class, () -> a.entryAt(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> a.entryAt(n));

		doDataStructureTests(a);
	}

	/**
	 * Generic tests for any set
	 */
	public static <V> void doSetTests(ASet<V> a) {
		doCollectionTests(a);
	}
}
