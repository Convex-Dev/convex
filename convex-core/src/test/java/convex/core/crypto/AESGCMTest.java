package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Tests for AES-256-GCM authenticated encryption.
 */
public class AESGCMTest {

	private static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	@Test
	public void testRoundTrip() {
		byte[] key = randomKey();
		byte[] plaintext = "Hello, AES-GCM!".getBytes();

		byte[] encrypted = AESGCM.encrypt(key, plaintext);
		byte[] decrypted = AESGCM.decrypt(key, encrypted);

		assertArrayEquals(plaintext, decrypted);
	}

	@Test
	public void testRoundTripEmptyPlaintext() {
		byte[] key = randomKey();
		byte[] plaintext = new byte[0];

		byte[] encrypted = AESGCM.encrypt(key, plaintext);
		byte[] decrypted = AESGCM.decrypt(key, encrypted);

		assertArrayEquals(plaintext, decrypted);
	}

	@Test
	public void testRoundTripLargeData() {
		byte[] key = randomKey();
		byte[] plaintext = new byte[100_000];
		new SecureRandom().nextBytes(plaintext);

		byte[] encrypted = AESGCM.encrypt(key, plaintext);
		byte[] decrypted = AESGCM.decrypt(key, encrypted);

		assertArrayEquals(plaintext, decrypted);
	}

	@Test
	public void testWrongKeyFails() {
		byte[] key1 = randomKey();
		byte[] key2 = randomKey();
		byte[] plaintext = "secret data".getBytes();

		byte[] encrypted = AESGCM.encrypt(key1, plaintext);

		assertThrows(RuntimeException.class, () -> AESGCM.decrypt(key2, encrypted));
	}

	@Test
	public void testTamperedCiphertextFails() {
		byte[] key = randomKey();
		byte[] plaintext = "do not tamper".getBytes();

		byte[] encrypted = AESGCM.encrypt(key, plaintext);

		// Flip a bit in the ciphertext (after nonce)
		encrypted[14] ^= 0x01;

		assertThrows(RuntimeException.class, () -> AESGCM.decrypt(key, encrypted));
	}

	@Test
	public void testTamperedNonceFails() {
		byte[] key = randomKey();
		byte[] plaintext = "nonce matters".getBytes();

		byte[] encrypted = AESGCM.encrypt(key, plaintext);

		// Flip a bit in the nonce
		encrypted[0] ^= 0x01;

		assertThrows(RuntimeException.class, () -> AESGCM.decrypt(key, encrypted));
	}

	@Test
	public void testDifferentEncryptionsProduceDifferentCiphertext() {
		byte[] key = randomKey();
		byte[] plaintext = "same plaintext".getBytes();

		byte[] enc1 = AESGCM.encrypt(key, plaintext);
		byte[] enc2 = AESGCM.encrypt(key, plaintext);

		// Different random nonces → different ciphertext
		assertFalse(Arrays.equals(enc1, enc2));

		// Both decrypt to same plaintext
		assertArrayEquals(plaintext, AESGCM.decrypt(key, enc1));
		assertArrayEquals(plaintext, AESGCM.decrypt(key, enc2));
	}

	@Test
	public void testOutputLengthIncludesNonceAndTag() {
		byte[] key = randomKey();
		byte[] plaintext = new byte[100];

		byte[] encrypted = AESGCM.encrypt(key, plaintext);

		// 12 (nonce) + 100 (plaintext) + 16 (GCM tag) = 128
		assertEquals(128, encrypted.length);
	}

	@Test
	public void testNullKeyThrows() {
		assertThrows(IllegalArgumentException.class, () -> AESGCM.encrypt(null, new byte[10]));
		assertThrows(IllegalArgumentException.class, () -> AESGCM.decrypt(null, new byte[20]));
	}

	@Test
	public void testWrongKeyLengthThrows() {
		byte[] shortKey = new byte[16]; // AES-128, not 256
		assertThrows(IllegalArgumentException.class, () -> AESGCM.encrypt(shortKey, new byte[10]));
	}

	@Test
	public void testNullPlaintextThrows() {
		assertThrows(IllegalArgumentException.class, () -> AESGCM.encrypt(randomKey(), null));
	}

	@Test
	public void testDataTooShortThrows() {
		byte[] key = randomKey();
		assertThrows(IllegalArgumentException.class, () -> AESGCM.decrypt(key, new byte[5]));
		assertThrows(IllegalArgumentException.class, () -> AESGCM.decrypt(key, null));
	}
}
