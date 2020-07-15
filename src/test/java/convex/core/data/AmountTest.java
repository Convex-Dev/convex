package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;

public class AmountTest {

	@Test
	public void testParsing() {
		assertEquals(1L, Amount.parse("0.000001").getValue());
		assertEquals(2000001L, Amount.parse("2.000001").getValue());
		assertEquals(2000000L, Amount.parse("£2.0").getValue());
	}

	@Test
	public void testRepresentations() {
		assertEquals("1000", Amount.create(0).getEncoding().toHexString());
		assertEquals("1307", Amount.create(7000).getEncoding().toHexString());
		assertEquals("1601", Amount.create(1000000).getEncoding().toHexString());
		assertEquals("1a02", Amount.create(20000000000L).getEncoding().toHexString());
		assertEquals("1f01", Amount.create(Amount.MAX_AMOUNT / 1000).getEncoding().toHexString());
		assertEquals("1f8768", Amount.create(Amount.MAX_AMOUNT).getEncoding().toHexString());

		assertEquals("10bf7f", Amount.create(8191).getEncoding().toHexString()); // max in 14 bits
		assertEquals("1080c000", Amount.create(8192).getEncoding().toHexString()); // overflow from above
		assertEquals("12bf7f", Amount.create(819100).getEncoding().toHexString()); // max in 14 bits with scale
	}

	@Test
	public void testBadRepresentations() throws BadFormatException {
		assertThrows(BadFormatException.class, () -> Format.read(Blob.fromHex("100a")));
	}

	@Test
	public void testBadPowersOf10() {
		assertThrows(IllegalArgumentException.class, () -> Amount.powerOf10(-1));
	}

	@Test
	public void testPowersOf10() {
		for (int i = 0; i <= 18; i++) {
			long pot = Amount.powerOf10(i);
			assertEquals(i, Amount.trailingZeros(pot));
			assertEquals(pot, Amount.create(pot).getValue());
		}

		assertEquals(0, Amount.trailingZeros(0));
		assertEquals(0, Amount.trailingZeros(3));
		assertEquals(1, Amount.trailingZeros(10));
		assertEquals(10, Amount.trailingZeros(30000000000L));
		assertEquals(18, Amount.trailingZeros(Amount.MAX_AMOUNT));
	}
}
