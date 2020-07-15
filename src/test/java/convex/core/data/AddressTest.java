package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.util.Utils;

public class AddressTest {

	@Test
	public void testAccountGen1() {
		if (Constants.USE_ED25519) {
			// TODO
		} else {		
			// test account
			String publicKey = "6cb84859e85b1d9a27e060fdede38bb818c93850fb6e42d9c7e4bd879f8b9153fd94ed48e1f63312dce58f4d778ff45a2e5abb08a39c1bc0241139f5e54de7df";
			assertEquals(128, publicKey.length());

			Address a = Address.fromPublicKey(Utils.hexToBytes(publicKey));
			assertEquals("AFDEFC1937AE294C3BD55386A8B9775539D81653", a.toHexString().toUpperCase());

			BlobsTest.doBlobTests(a);			
		}
	}
	
	@Test
	public void testAccountGen2() {
		if (Constants.USE_ED25519) {
			// TODO
		} else {
			// testcase from:
			// https://kobl.one/blog/create-full-ethereum-keypair-and-address/
			String publicKey = "836b35a026743e823a90a0ee3b91bf615c6a757e2b60b9e1dc1826fd0dd16106f7bc1e8179f665015f43c6c81f39062fc2086ed849625c06e04697698b21855e";
			assertEquals(128, publicKey.length());
	
			Address a = Address.fromPublicKey(Utils.hexToBytes(publicKey));
			assertEquals("0bed7abd61247635c1973eb38474a2516ed1d884", a.toHexString());
		}
	}

	@Test
	public void testChecksumRoundTrip() {
		Random r = new Random(1585875);
		for (int i = 0; i < 10; i++) {
			Blob ba = Blob.createRandom(r, Address.LENGTH);
			Address a = Address.wrap(ba.getBytes());

			String s = a.toChecksumHex();
			assertEquals(a, Address.fromHex(s));
			assertEquals(a, Address.fromChecksumHex(s));

			String sl = s.toLowerCase();

			assertEquals(a, Address.fromHex(sl));
			assertThrows(IllegalArgumentException.class, () -> Address.fromChecksumHex(sl));
		}
	}


	@Test
	public void testEquality() {
		String aString = Blob.createRandom(new Random(), Address.LENGTH).toHexString();
		Address a = Address.fromHex(aString);
		assertEquals(a, Address.fromHex(aString));

		// Address should not be equal to Blob with same byte content
		Blob b = a.toBlob();
		assertNotEquals(a, b);

		assertEquals(0, a.compareTo(b));
		
		BlobsTest.doBlobTests(a);			
	}
}
