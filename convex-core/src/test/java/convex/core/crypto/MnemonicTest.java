package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;

public class MnemonicTest {
	@Test
	public void testZero() {
		byte[] bs = new byte[16]; // zero
		assertEquals("a a a a a a a a a a a a", Mnemonic.encode(bs));
		Arrays.fill(bs, (byte) -1); // 0xffff....
		assertEquals("yoke yoke yoke yoke yoke yoke yoke yoke yoke yoke yoke worn", Mnemonic.encode(bs));
	}

	@Test
	public void test11Bytes() {
		byte[] bs=new byte[] {0,1,2,3,4,5,6,7,8,9,10};
		String s=Mnemonic.encode(bs);
		assertEquals("a bog fire bog baby art jig jut",s);
		doRoundTripTest(bs);
	}
	
	@Test
	public void testRoundTrips() {
		Random r = new Random(78976976);
		for (int i = 0; i < 100; i++) {
			int n = r.nextInt(100) + 1;
			byte[] bs = Blob.createRandom(r, n).getBytes();
			doRoundTripTest(bs);
		}
	}
	
	public void doRoundTripTest(byte[] bs) {
		int n=bs.length;
		String m = Mnemonic.encode(bs);
		byte[] bs2 = Mnemonic.decode(m, 8 * n);
		assertEquals(Blob.wrap(bs), Blob.wrap(bs2));
	}

	@Test
	public void testRandom() {
		String mnem = Mnemonic.createSecureRandom();
		byte[] bs = Mnemonic.decode(mnem, 128);
		assertEquals(16, bs.length);
		doRoundTripTest(bs);
		
		// A new secure random mnemonic should be unique 
		assertNotEquals(mnem,Mnemonic.createSecureRandom());
	}
	
	@Test
	public void testRFC1751() {
		// Modified from RFC1751 (high order 8 bytes the same)
		String mnem = "TROD MUTE TAIL WARM CHAR KONG HAAG CITY BORE O TEAL AWL";
		byte[] bs=Mnemonic.decode(mnem,128);
		Blob b=Blob.wrap(bs);
		assertEquals(Blob.fromHex("eff81f9bfbc65350a483375d05b7a002"),b);
	}
	
	@Test 
	public void testKeyPair() {
		String mnem = "TROD MUTE TAIL WARM CHAR KONG HAAG CITY BORE O TEAL AWL";
		AKeyPair kp=Mnemonic.decodeKeyPair(mnem);
		assertEquals(kp,Mnemonic.decodeKeyPair(mnem, null));
		assertEquals(kp,Mnemonic.decodeKeyPair(mnem, ""));
		
		assertEquals("a76c88c207db1c0d7f58cfdbcc6e706f0fd304c4e27443ae35719d9d76ba7b57",kp.getSeed().toHexString());
	
		AKeyPair kpfoo=Mnemonic.decodeKeyPair(mnem,"foo");
		assertNotEquals(kp,kpfoo);
		assertEquals("9f3af5e0f93dfb236b6aac71933f0b8b1993fd327610b692dbb3a4a212d97fbc",kpfoo.getSeed().toHexString());
	}
	
}
