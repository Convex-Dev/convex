package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

public class SymmetricTest {
	@Test
	public void testRoundTrip() {
		String plainText = "Hello World!!!";

		SecretKey key1 = Symmetric.createSecretKey();
		byte[] message = Symmetric.encrypt(key1, plainText);
		String decrypted = Symmetric.decryptString(key1, message);

		assertEquals(plainText, decrypted);

		SecretKey key2 = Symmetric.createSecretKey();
		byte[] message2 = Symmetric.encrypt(key2, plainText);
		assertFalse(Arrays.equals(message, message2));

	}

	@Test
	public void testSecretKeyVariance() {
		assertNotEquals(Symmetric.createSecretKey(), Symmetric.createSecretKey());
	}

	@Test
	public void testEncoded() {
		SecretKey k = Symmetric.createSecretKey();
		byte[] encoded = k.getEncoded();
		assertEquals(16, encoded.length);
	}
}
