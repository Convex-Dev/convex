package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class EconomicsTest {

	@Test
	public void testPoolRate() {

		assertEquals(1.0, Economics.swapRate(100, 100));
		assertEquals(1.0, Economics.swapRate(Long.MAX_VALUE, Long.MAX_VALUE));
		assertEquals(2.0, Economics.swapRate(1, 2));

		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(0, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(1000, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(0, 1000));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(-10, 10));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(10, -10));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapRate(Long.MIN_VALUE, Long.MIN_VALUE));
	}

	@Test
	public void testPoolPrice() {

		assertEquals(100, Economics.swapPrice(50, 100, 100));
		assertEquals(0, Economics.swapPrice(0, 100, 100));
		assertEquals(-33, Economics.swapPrice(-50, 100, 100));
		assertEquals(0, Economics.swapPrice(0, 1675, 117));
		assertEquals(0, Economics.swapPrice(0, 12, 1454517));
		assertEquals(999999, Economics.swapPrice(999999, 1000000, 1));

		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 100, 100));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 0, 100));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 100, 0));
		assertThrows(IllegalArgumentException.class, () -> Economics.swapPrice(100, 50, 200));
	}
}
