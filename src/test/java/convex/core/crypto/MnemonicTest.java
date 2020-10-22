package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;

public class MnemonicTest {
	@Test
	public void testZero() {
		byte[] bs = new byte[16];
		assertEquals("a a a a a a a a a a a a", Mnemonic.encode(bs));
		Arrays.fill(bs, (byte) -1);
		assertEquals("yoke yoke yoke yoke yoke yoke yoke yoke yoke yoke yoke yoke", Mnemonic.encode(bs));
	}

	@Test
	public void testRoundTrips() {
		Random r = new Random(78976976);
		for (int i = 0; i < 100; i++) {
			int n = r.nextInt(100) + 1;
			byte[] bs = Blob.createRandom(r, n).getBytes();

			// round trip byte array
			String m = Mnemonic.encode(bs);
			byte[] bs2 = Mnemonic.decode(m, 8 * n);
			assertTrue(Arrays.equals(bs, bs2));
			assertEquals(n, bs2.length);
		}
	}

	@Test
	public void testRandom() {
		String mnem = Mnemonic.createSecureRandom();
		byte[] bs = Mnemonic.decode(mnem, 128);
		assertEquals(16, bs.length);
	}
}
