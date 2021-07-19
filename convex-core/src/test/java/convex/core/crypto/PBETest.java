package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;

public class PBETest {

	@Test
	public void testKeyDerivation() {
		byte[] bs = PBE.deriveKey(new char[0], new byte[1], 128);
		assertEquals(16, bs.length);
		assertEquals("a352cdf92312599de774874ad9f3fcc5", Hex.toHexString(bs));
	}
}
