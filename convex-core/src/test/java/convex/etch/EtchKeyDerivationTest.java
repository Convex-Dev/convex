package convex.etch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

/** Tests the exact Etch v3 key-derivation format contract. */
public class EtchKeyDerivationTest {
	/*
	 * Fixed Etch v3 vector:
	 *
	 * secret = 000102...1f
	 * salt   = a0a1a2...bf
	 *
	 * HKDF-Extract uses HMAC-SHA-256(salt, secret), producing:
	 * 417e7502c38837c356dc6d3f1c84cfac0efea0e929628cb89ce52f74716620ad
	 *
	 * Since each requested key is exactly 32 bytes, HKDF-Expand uses one block:
	 * HMAC-SHA-256(PRK, ASCII context || 0x01). The 0x01 is the RFC 5869
	 * block counter, not part of the Etch context string.
	 */
	@Test
	public void testEtchV3KnownVector() {
		byte[] secret=sequence(0x00,EtchConstants.V3_FILE_SALT_SIZE);
		byte[] salt=sequence(0xa0,EtchConstants.V3_FILE_SALT_SIZE);

		assertEquals("8c87a0e18c82d24d61d83ae05e1b9ee2fdf163d138abc71f593ad9a96360c279",
				Utils.toHexString(EtchKeyDerivation.deriveFileCipherKey(secret,salt)));
		assertEquals("a34f914626e441accdb2af818ff6c470c8477abf3695e474448511653f58f479",
				Utils.toHexString(EtchKeyDerivation.deriveHeaderMacKey(secret,salt)));
	}

	@Test
	public void testRejectsInvalidInputs() {
		byte[] validSecret=sequence(0x00,32);
		byte[] validSalt=sequence(0xa0,EtchConstants.V3_FILE_SALT_SIZE);
		assertThrows(IllegalArgumentException.class,
				()->EtchKeyDerivation.deriveFileCipherKey(null,validSalt));
		assertThrows(IllegalArgumentException.class,
				()->EtchKeyDerivation.deriveFileCipherKey(new byte[0],validSalt));
		assertThrows(IllegalArgumentException.class,
				()->EtchKeyDerivation.deriveFileCipherKey(validSecret,null));
		assertThrows(IllegalArgumentException.class,
				()->EtchKeyDerivation.deriveFileCipherKey(validSecret,new byte[31]));
		assertThrows(IllegalArgumentException.class,
				()->EtchKeyDerivation.deriveFileCipherKey(validSecret,new byte[32]));
	}

	private static byte[] sequence(int first, int length) {
		byte[] result=new byte[length];
		for (int i=0;i<length;i++) result[i]=(byte)(first+i);
		return result;
	}
}
