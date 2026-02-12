package convex.core.crypto;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM authenticated encryption utility.
 *
 * Encrypts with a random 12-byte nonce prepended to the ciphertext.
 * Decrypts by extracting the nonce prefix and verifying the authentication tag.
 */
public class AESGCM {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int NONCE_LENGTH = 12;
	private static final int TAG_BITS = 128;
	private static final int KEY_LENGTH = 32;

	/**
	 * Encrypts plaintext using AES-256-GCM with a random nonce.
	 * The 12-byte nonce is prepended to the output.
	 *
	 * @param key 32-byte AES-256 key
	 * @param plaintext Data to encrypt
	 * @return nonce (12 bytes) || ciphertext || GCM tag (16 bytes)
	 */
	public static byte[] encrypt(byte[] key, byte[] plaintext) {
		if (key == null || key.length != KEY_LENGTH) throw new IllegalArgumentException("Key must be 32 bytes");
		if (plaintext == null) throw new IllegalArgumentException("Plaintext must not be null");

		try {
			byte[] nonce = new byte[NONCE_LENGTH];
			new SecureRandom().nextBytes(nonce);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));

			byte[] ciphertext = cipher.doFinal(plaintext);

			// Prepend nonce
			byte[] result = new byte[NONCE_LENGTH + ciphertext.length];
			System.arraycopy(nonce, 0, result, 0, NONCE_LENGTH);
			System.arraycopy(ciphertext, 0, result, NONCE_LENGTH, ciphertext.length);
			return result;
		} catch (Exception e) {
			throw new RuntimeException("AES-GCM encryption failed", e);
		}
	}

	/**
	 * Decrypts AES-256-GCM ciphertext. Expects the 12-byte nonce prepended.
	 * Verifies the GCM authentication tag.
	 *
	 * @param key 32-byte AES-256 key
	 * @param data nonce (12 bytes) || ciphertext || GCM tag
	 * @return Decrypted plaintext
	 * @throws RuntimeException if decryption or authentication fails
	 */
	public static byte[] decrypt(byte[] key, byte[] data) {
		if (key == null || key.length != KEY_LENGTH) throw new IllegalArgumentException("Key must be 32 bytes");
		if (data == null || data.length < NONCE_LENGTH) throw new IllegalArgumentException("Data too short");

		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
					new GCMParameterSpec(TAG_BITS, data, 0, NONCE_LENGTH));

			return cipher.doFinal(data, NONCE_LENGTH, data.length - NONCE_LENGTH);
		} catch (Exception e) {
			throw new RuntimeException("AES-GCM decryption failed", e);
		}
	}
}
