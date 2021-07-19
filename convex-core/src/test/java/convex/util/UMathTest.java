package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.util.UMath;

public class UMathTest {

	@Test 
	public void testMultiplyHigh() {
		assertEquals(1L,UMath.multiplyHigh(0x100000000L, 0x100000000L));
	}
}
