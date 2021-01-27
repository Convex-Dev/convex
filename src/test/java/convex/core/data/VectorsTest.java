package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ListIterator;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.test.Samples;

/**
 * Example based tests for vectors.
 * 
 * Also doVectorTests(...) implements generic tests for any vector.
 */
public class VectorsTest {

	@Test
	public void testEmptyVector() {
		VectorLeaf<String> lv = VectorLeaf.create(new String[0]);
		AArrayBlob d = lv.getEncoding();
		assertArrayEquals(new byte[] { Tag.VECTOR, 0 }, d.getBytes());
	}

	@Test
	public void testSubVectors() {
		AVector<Integer> v = Samples.INT_VECTOR_300;

		AVector<Integer> v1 = v.subVector(10, Vectors.CHUNK_SIZE);
		assertEquals(VectorLeaf.class, v1.getClass());
		assertEquals(10, v1.get(0));

		AVector<Integer> v2 = v.subVector(10, Vectors.CHUNK_SIZE * 2);
		assertEquals(VectorTree.class, v2.getClass());
		assertEquals(10, v2.get(0));

		AVector<Integer> v3 = v.subVector(10, Vectors.CHUNK_SIZE * 2 - 1);
		assertEquals(VectorLeaf.class, v3.getClass());
		assertEquals(10, v3.get(0));

		AVector<Integer> v4 = v3.conj(1000);
		assertEquals(VectorTree.class, v4.getClass());
		assertEquals(26, v4.get(16));
		assertEquals(v1, v4.subVector(0, Vectors.CHUNK_SIZE));
	}

	@Test
	public void testCreateSpecialCases() {
		assertSame(Vectors.empty(), VectorLeaf.create(new Object[0]));
		assertSame(Vectors.empty(), VectorLeaf.create(new Object[10], 3, 0));

		assertThrows(IllegalArgumentException.class, () -> VectorLeaf.create(new Object[0], 0, 0, null));
		assertThrows(IllegalArgumentException.class, () -> VectorLeaf.create(new Object[20], 1, 18, null));
	}

	@Test
	public void testChunks() {
		assertEquals(Samples.INT_VECTOR_16, Samples.INT_VECTOR_300.getChunk(0));
		AVector<Integer> v = Samples.INT_VECTOR_300.getChunk(0);
		assertEquals(VectorTree.class, v.getChunk(0).concat(v).getClass());
	}

	@Test
	public void testChunkConcat() {
		VectorLeaf<Integer> v = Samples.INT_VECTOR_300.getChunk(16);
		AVector<Integer> vv = v.concat(v);
		assertEquals(VectorTree.class, vv.getClass());
		assertEquals(v, vv.getChunk(16));

		assertSame(Samples.INT_VECTOR_16, Samples.INT_VECTOR_16.empty().appendChunk(Samples.INT_VECTOR_16));

		assertThrows(IndexOutOfBoundsException.class, () -> vv.getChunk(3));

		// can't append chunk unless initial size is correct
		assertThrows(IllegalArgumentException.class, () -> Samples.INT_VECTOR_10.appendChunk(Samples.INT_VECTOR_16));
		assertThrows(IllegalArgumentException.class, () -> Samples.INT_VECTOR_300.appendChunk(Samples.INT_VECTOR_16));

		// can't append wrong chunk size
		assertThrows(IllegalArgumentException.class,
				() -> Samples.INT_VECTOR_16.appendChunk((VectorLeaf<Integer>) Vectors.of(1, 2)));

	}

	@Test
	public void testIndexOf() {
		AVector<Integer> v = Samples.INT_VECTOR_300;
		assertEquals(299, v.indexOf(299));
		assertEquals(299L, v.longIndexOf(299));
		assertEquals(299, v.lastIndexOf(299));
		assertEquals(299L, v.longLastIndexOf(299));

		assertEquals(29, v.indexOf(29));
		assertEquals(29L, v.longIndexOf(29));
		assertEquals(29, v.lastIndexOf(29));
		assertEquals(29L, v.longLastIndexOf(29));

	}

	@Test
	public void testAppending() {
		int SIZE = 300;
		@SuppressWarnings("unchecked")
		AVector<Integer> lv = (VectorLeaf<Integer>) VectorLeaf.EMPTY;

		for (int i = 0; i < SIZE; i++) {
			lv = lv.append(i);
			assertEquals(i + 1L, lv.count());
			assertEquals(i, (int) lv.get(i));
		}
		assertEquals(300L, lv.count());
	}

	@Test
	public void testBigMatch() {
		AVector<Integer> v = Samples.INT_VECTOR_300;
		assertTrue(v.anyMatch(i -> i == 3));
		assertTrue(v.anyMatch(i -> i == 299));
		assertFalse(v.anyMatch(i -> i == -1));

		assertFalse(v.allMatch(i -> i == 3));
		assertTrue(v.allMatch(i -> i instanceof Integer));
	}

	@Test
	public void testAnyMatch() {
		AVector<Integer> v = Vectors.of(1, 2, 3, 4);
		assertTrue(v.anyMatch(i -> i == 3));
		assertFalse(v.anyMatch(i -> i == 5));
	}

	@Test
	public void testAllMatch() {
		AVector<Integer> v = Vectors.of(1, 2, 3, 4);
		assertTrue(v.allMatch(i -> i instanceof Integer));
		assertFalse(v.allMatch(i -> i < 3));
	}

	@Test
	public void testMap() {
		AVector<Integer> v = Vectors.of(1, 2, 3, 4);
		AVector<Integer> exp = Vectors.of(2, 3, 4, 5);
		assertEquals(exp, v.map(i -> i + 1));
	}

	@Test
	public void testSmallAssoc() {
		AVector<Integer> v = Vectors.of(1, 2, 3, 4);
		AVector<Integer> nv = v.assoc(2, 10);
		assertEquals(Vectors.of(1, 2, 10, 4), nv);
	}

	@Test
	public void testBigAssoc() {
		AVector<Integer> v = Samples.INT_VECTOR_300;
		AVector<Integer> nv = v.assoc(100, 17);
		assertEquals(17, (int) nv.get(100));
	}

	@Test
	public void testReduce() {
		AVector<Integer> vec = Vectors.of(1, 2, 3, 4);
		assertEquals(110, (int) vec.reduce((s, v) -> s + v, 100));
	}

	@Test
	public void testMapEntry() {
		AVector<?> v1 = Vectors.of(1, 2);
		assertNotEquals(v1, MapEntry.create(1, 2));
		assertEquals(v1, MapEntry.create(1, 2).toVector());
	}

	@Test
	public void testLastIndex() {
		// regression test
		AVector<Integer> v = Samples.INT_VECTOR_300.concat(Vectors.of(1, null, 3, null));
		assertEquals(303, v.longLastIndexOf(null));
	}

	@Test
	public void testReduceBig() {
		AVector<Integer> vec = Samples.INT_VECTOR_300;
		assertEquals(100 + (299 * 300) / 2, (int) vec.reduce((s, v) -> s + v, 100));
	}
	
	// TODO: more sensible tests on embedded vector sizes
	@Test 
	public void testEmbedding() {
		// should embed, little values
		AVector<Integer> vec = Vectors.of(1, 2, 3, 4);
		assertTrue(vec.isEmbedded());
		assertEquals(10L,vec.getEncoding().length());
		
		// should embed, small enough
		AVector<Object> vec2=Vectors.of(vec,vec);
		assertTrue(vec2.isEmbedded());
		assertEquals(22L,vec2.getEncoding().length());

		AVector<Object> vec3=Vectors.of(vec2,vec2,vec2,vec2,vec2,vec2,vec2,vec2);
		assertFalse(vec3.isEmbedded());
	}

	@Test
	public void testUpdateRefs() {
		AVector<Integer> vec = Vectors.of(1, 2, 3, 4);
		AVector<Integer> vec2 = vec.updateRefs(r -> {
			return Ref.get((Integer) (r.getValue()) + 1);
		});
		assertEquals(Vectors.of(2, 3, 4, 5), vec2);
	}

	@Test
	public void testNext() {
		AVector<Integer> v1 = Samples.INT_VECTOR_256;
		AVector<Integer> v2 = v1.next();
		assertEquals(v1.get(1), v2.get(0));
		assertEquals(v1.get(255), v2.get(254));
		assertEquals(1L, v1.count() - v2.count());
	}

	@Test
	public void testIterator() {
		int SIZE = 100;
		@SuppressWarnings("unchecked")
		AVector<Integer> lv = (VectorLeaf<Integer>) VectorLeaf.EMPTY;

		for (int i = 0; i < SIZE; i++) {
			lv = lv.append(i);
			assertTrue(lv.isCanonical());
		}
		assertEquals(4950, (int) lv.reduce((s, v) -> s + v, 0));

		// forward iteration
		ListIterator<Integer> it = lv.listIterator();
		Spliterator<Integer> split = lv.spliterator();
		AtomicInteger splitAcc = new AtomicInteger(0);
		for (int i = 0; i < SIZE; i++) {
			assertTrue(it.hasNext());
			assertTrue(split.tryAdvance(a -> splitAcc.addAndGet(a)));
			assertEquals(i, it.nextIndex());
			assertEquals((Integer) i, it.next());
		}
		assertEquals(100, it.nextIndex());
		assertEquals(4950, splitAcc.get());
		assertFalse(it.hasNext());

		// backward iteration
		ListIterator<Integer> li = lv.listIterator(SIZE);
		for (int i = SIZE - 1; i >= 0; i--) {
			assertTrue(li.hasPrevious());
			assertEquals(i, li.previousIndex());
			assertEquals((Integer) i, li.previous());
		}
		assertEquals(-1, li.previousIndex());
		assertFalse(li.hasPrevious());
	}

	@Test
	public void testEmptyVectorHash() {
		AVector<?> e = Vectors.empty();

		// test the byte layout of the empty vector
		assertEquals(e.getEncoding(), Blob.fromHex("8000"));
		assertEquals(e.getHash(), Vectors.of(new VectorLeaf<?>[0]).getHash());
	}

	@Test
	public void testSmallVectorSerialisation() {
		// test the byte layout of the vector
		// value should be an int VLC encoded to two bytes (0x0701)
		assertEquals(Blob.fromHex("80010701"), Vectors.of(1).getEncoding());

		// value should be a negative int VLC encoded to two bytes (0x077F)
		assertEquals(Blob.fromHex("8001077F"), Vectors.of(-1).getEncoding());
	}

	@Test
	public void testPrefixLength() throws BadFormatException {
		assertEquals(2, Vectors.of(1, 2, 3).commonPrefixLength(Vectors.of(1, 2)));
		assertEquals(2, Vectors.of(1, 2).commonPrefixLength(Vectors.of(1, 2, 8)));
		assertEquals(0, Vectors.of(1, 2, 3).commonPrefixLength(Vectors.of(2, 2, 3)));

		AVector<Integer> v1 = Vectors.of(0, 1, 2, 3, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
				1, 1, 1);
		assertEquals(5, v1.commonPrefixLength(Samples.INT_VECTOR_300));
		assertEquals(5, Samples.INT_VECTOR_300.commonPrefixLength(v1));
		assertEquals(v1.count(), v1.commonPrefixLength(v1));

		assertEquals(10, Samples.INT_VECTOR_10.commonPrefixLength(Samples.INT_VECTOR_23));
		assertEquals(256, Samples.INT_VECTOR_300.commonPrefixLength(Samples.INT_VECTOR_256));
		assertEquals(256, Samples.INT_VECTOR_300.commonPrefixLength(Samples.INT_VECTOR_256.append(17)));
	}

	/**
	 * Generic tests for any vector
	 */
	public static <T> void doVectorTests(AVector<T> v) {
		long n = v.count();

		if (n == 0) {
			assertSame(Vectors.empty(), v);
		} else {
			T last = v.get(n - 1);
			T first = v.get(0);
			assertEquals(n - 1, v.longLastIndexOf(last));
			assertEquals(0L, v.longIndexOf(first));

			AVector<T> v2 = v.append(first);
			assertEquals(first, v2.get(n));
		}

		assertEquals(v.toVector(), Vectors.of(v.toArray()));

		CollectionsTest.doSequenceTests(v);
	}

}
