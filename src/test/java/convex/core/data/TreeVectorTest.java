package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NoSuchElementException;
import java.util.Spliterator;

import org.junit.jupiter.api.Test;

import convex.test.Samples;

public class TreeVectorTest {

	@Test
	public void testComputeShift() {
		assertEquals(0, TreeVector.computeShift(0));
		assertEquals(0, TreeVector.computeShift(1));
		assertEquals(0, TreeVector.computeShift(16));

		assertEquals(4, TreeVector.computeShift(17)); // overflow to next shift level
		assertEquals(4, TreeVector.computeShift(32));
		assertEquals(4, TreeVector.computeShift(107));
		assertEquals(4, TreeVector.computeShift(256));

		assertEquals(8, TreeVector.computeShift(257)); // overflow to next shift level

		assertEquals(8, TreeVector.computeShift(4096));
		assertEquals(12, TreeVector.computeShift(4097)); // overflow to next shift level
	}

	@Test
	public void testArraySize() {
		assertEquals(2, TreeVector.computeArraySize(32)); // two tree chunks
		assertEquals(3, TreeVector.computeArraySize(33)); // needs 3 chunks
		assertEquals(16, TreeVector.computeArraySize(256)); // full 16 chunks
		assertEquals(2, TreeVector.computeArraySize(257)); // needs 17 chunks, two children at next level

		assertEquals(16, TreeVector.computeArraySize(4096)); // full 16 children
		assertEquals(2, TreeVector.computeArraySize(4097)); // two children at next level

		assertTrue(16 >= TreeVector.computeArraySize(967827895416073414L));
	}

	@Test
	public void testIterator() {
		AVector<Integer> v = Samples.INT_VECTOR_256;
		assertEquals(v.get(0), v.iterator().next());
		assertEquals(v.get(255), v.listIterator(256).previous());

		assertThrows(NoSuchElementException.class, () -> v.listIterator().previous());
		assertThrows(NoSuchElementException.class, () -> v.listIterator(256).next());
	}

	@Test
	public void testMap() {
		AVector<Integer> orig = Samples.INT_VECTOR_300;
		AVector<Integer> inc = orig.map(i -> i + 5);
		assertEquals(orig.count(), inc.count());
		assertNotEquals(orig, inc);
		AVector<Integer> dec = inc.map(i -> i - 5);
		assertEquals(orig, dec);
	}

	@Test
	public void testSpliterator() {
		AVector<Integer> a = Samples.INT_VECTOR_300.subVector(0, 256);
		assertEquals(TreeVector.class, a.getClass());
		Spliterator<Integer> spliterator = a.spliterator();
		assertEquals(256, spliterator.estimateSize());

		int[] sum = new int[1];
		spliterator.forEachRemaining(i -> sum[0] += i);
		assertEquals((255 * 256) / 2, sum[0]);

	}
}
