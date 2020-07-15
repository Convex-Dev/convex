package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
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

	@Test
	public void testRoundTripKeyPair() {
		Random r = new Random(78976976);
		for (int i = 0; i < 100; i++) {
			byte[] bs = Blob.createRandom(r, 32).getBytes();
			String m = Mnemonic.encode(bs);

			// round trip to keypair
			ECDSAKeyPair kp = ECDSAKeyPair.create(bs);
			String rm = Mnemonic.encode(kp.getPrivateKey(), 256);
			assertEquals(m, rm);
		}
	}

	@Test
	public void testExample() {
		if (Constants.USE_ED25519) {
			assertEquals("a2cafeeaf8bce9d285669621a0be25327a94c6147ea0772e52cf2df490367423", Mnemonic
					.decodeKeyPair("soft hulk kim led cog java wad bess tune army tube wast").getAddress().toHexString());
		} else {
			assertEquals("a0be25327a94c6147ea0772e52cf2df490367423", Mnemonic
				.decodeKeyPair("soft hulk kim led cog java wad bess tune army tube wast").getAddress().toHexString());
		}
	}
}
