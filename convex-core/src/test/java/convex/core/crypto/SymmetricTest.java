package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.KeyGenerator;

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
	public void testInputStreamRoundTrip() throws Exception {
		SecretKey key=Symmetric.createSecretKey();
		byte[] plain="streamed data".getBytes(StandardCharsets.UTF_8);
		byte[] encrypted=Symmetric.encrypt(key,plain);
		assertArrayEquals(plain,Symmetric.decrypt(key,new ByteArrayInputStream(encrypted)));
	}

	@Test
	public void testWrongKeyAndTamperingRejected() {
		SecretKey key=Symmetric.createSecretKey();
		byte[] encrypted=Symmetric.encrypt(key,"authenticated");
		assertFalse(Arrays.equals(encrypted,Symmetric.encrypt(key,"authenticated")));
		assertThrows(IllegalArgumentException.class,
				() -> Symmetric.decrypt(Symmetric.createSecretKey(),encrypted));

		for (int index : new int[] { 0, 8, 20, encrypted.length-1 }) {
			byte[] tampered=encrypted.clone();
			tampered[index]^=1;
			assertThrows(IllegalArgumentException.class,() -> Symmetric.decrypt(key,tampered));
		}
	}

	@Test
	public void testLegacyCiphertextRejected() {
		SecretKey key=Symmetric.createSecretKey();
		IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
				() -> Symmetric.decrypt(key,new byte[32]));
		assertTrue(error.getMessage().contains("legacy AES-CBC"));
	}

	@Test
	public void testSecretKeyVariance() {
		assertNotEquals(Symmetric.createSecretKey(), Symmetric.createSecretKey());
	}

	@Test
	public void testEncoded() {
		SecretKey k = Symmetric.createSecretKey();
		byte[] encoded = k.getEncoded();
		assertEquals(32, encoded.length); // AES-256
	}

	@Test
	public void testShorterKeyStillWorks() throws Exception {
		// createSecretKey() now generates 256-bit keys, but any valid AES key length is
		// accepted, so data encrypted under an older 128-bit key still decrypts
		KeyGenerator kgen=KeyGenerator.getInstance("AES");
		kgen.init(128);
		SecretKey key=kgen.generateKey();

		assertEquals(16,key.getEncoded().length);
		assertEquals("legacy",Symmetric.decryptString(key,Symmetric.encrypt(key,"legacy")));
	}

}
