package convex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.util.Bits;

public class BitsTest {

	@Test public void testLeadingZeros() {
		assertEquals(16,Bits.leadingZeros(0x00FFFF));
		assertEquals(15,Bits.leadingZeros(0x010000));

		assertEquals(18,Bits.leadingZeros(16383));
		assertEquals(17,Bits.leadingZeros(16384));
	}
}
