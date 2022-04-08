package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NoSuchElementException;
import java.util.Spliterator;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.test.Samples;

public class TreeVectorTest {

	@Test
	public void testComputeShift() {
		assertEquals(0, VectorTree.computeShift(0));
		assertEquals(0, VectorTree.computeShift(1));
		assertEquals(0, VectorTree.computeShift(16));

		assertEquals(4, VectorTree.computeShift(17)); // overflow to next shift level
		assertEquals(4, VectorTree.computeShift(32));
		assertEquals(4, VectorTree.computeShift(107));
		assertEquals(4, VectorTree.computeShift(256));

		assertEquals(8, VectorTree.computeShift(257)); // overflow to next shift level

		assertEquals(8, VectorTree.computeShift(4096));
		assertEquals(12, VectorTree.computeShift(4097)); // overflow to next shift level
	}

	@Test
	public void testArraySize() {
		assertEquals(2, VectorTree.computeArraySize(32)); // two tree chunks
		assertEquals(3, VectorTree.computeArraySize(33)); // needs 3 chunks
		assertEquals(16, VectorTree.computeArraySize(256)); // full 16 chunks
		assertEquals(2, VectorTree.computeArraySize(257)); // needs 17 chunks, two children at next level

		assertEquals(16, VectorTree.computeArraySize(4096)); // full 16 children
		assertEquals(2, VectorTree.computeArraySize(4097)); // two children at next level

		assertTrue(16 >= VectorTree.computeArraySize(967827895416073414L));
	}

	@Test
	public void testIterator() {
		AVector<CVMLong> v = Samples.INT_VECTOR_256;
		assertEquals(v.get(0), v.iterator().next());
		assertEquals(v.get(255), v.listIterator(256).previous());

		assertThrows(NoSuchElementException.class, () -> v.listIterator().previous());
		assertThrows(NoSuchElementException.class, () -> v.listIterator(256).next());
	}

	@Test
	public void testMap() {
		AVector<CVMLong> orig = Samples.INT_VECTOR_300;
		AVector<CVMLong> inc = orig.map(i -> RT.cvm(i.longValue() + 5));
		assertEquals(orig.count(), inc.count());
		assertNotEquals(orig, inc);
		AVector<CVMLong> dec = inc.map(i -> RT.cvm(i.longValue() - 5));
		assertEquals(orig, dec);
	}

	@Test
	public void testSpliterator() {
		AVector<CVMLong> a = Samples.INT_VECTOR_300.slice(0, 256);
		assertEquals(VectorTree.class, a.getClass());
		Spliterator<CVMLong> spliterator = a.spliterator();
		assertEquals(256, spliterator.estimateSize());

		long[] sum = new long[1];
		spliterator.forEachRemaining(i -> sum[0] += i.longValue());
		assertEquals((255 * 256) / 2, sum[0]);

	}
}
