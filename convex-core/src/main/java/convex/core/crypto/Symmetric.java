package convex.core.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import convex.core.exceptions.Panic;
import convex.core.util.Utils;

/**
 * Experimental symmetric authenticated encryption using AES-GCM.
 *
 * @apiNote This API and its ciphertext representation are experimental. The
 *          representation may change without compatibility or migration support
 *          between releases. Do not use it as a persistent storage or interchange
 *          format.
 * @implNote The current representation is
 *           {@code CVXAESG1 || nonce || ciphertext || tag}. This layout is an
 *           implementation detail, not a stable Convex format. Its identifying
 *           prefix prevents ciphertext produced by the former unauthenticated
 *           AES-CBC implementation from being silently reinterpreted.
 */
public class Symmetric {
	private static final String SYMMETRIC_ENCRYPTION_ALGO = "AES/GCM/NoPadding";
	private static final String SYMMETRIC_KEY_ALGORITHM = "AES";
	private static final byte[] FORMAT_HEADER = "CVXAESG1".getBytes(StandardCharsets.US_ASCII);
	private static final int NONCE_LENGTH = 12;
	private static final int TAG_LENGTH = 16;
	private static final int TAG_BITS = TAG_LENGTH * Byte.SIZE;
	private static final int KEY_LENGTH = 128;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	/**
	 * Encrypts a String with a given AES secret key, using standard UTF-8 encoding
	 * 
	 * @param key  AES secret key
	 * @param data String to encrypt
	 * @return Encrypted representation of the given string in the experimental
	 *         ciphertext format
	 */
	public static byte[] encrypt(SecretKey key, String data) {
		if (data==null) throw new IllegalArgumentException("Data cannot be null");
		return encrypt(key, data.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Encrypt bytes with a given AES secret key. Prepends a versioned format header
	 * and random nonce to the authenticated ciphertext.
	 * 
	 * @param key Secret encryption key
	 * @param data Data to encrypt
	 * @return Encrypted representation of the given byte array data in the
	 *         experimental ciphertext format
	 */
	public static byte[] encrypt(SecretKey key, byte[] data) {
		validateKey(key);
		if (data==null) throw new IllegalArgumentException("Data cannot be null");
		byte[] nonce = new byte[NONCE_LENGTH];
		SECURE_RANDOM.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(SYMMETRIC_ENCRYPTION_ALGO);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(FORMAT_HEADER);
			byte[] ciphertext = cipher.doFinal(data);
			byte[] result = new byte[FORMAT_HEADER.length+NONCE_LENGTH+ciphertext.length];
			System.arraycopy(FORMAT_HEADER,0,result,0,FORMAT_HEADER.length);
			System.arraycopy(nonce,0,result,FORMAT_HEADER.length,NONCE_LENGTH);
			System.arraycopy(ciphertext,0,result,FORMAT_HEADER.length+NONCE_LENGTH,ciphertext.length);
			return result;
		} catch (GeneralSecurityException e) {
			throw new Panic("Failed to encrypt authenticated data", e);
		}
	}

	/**
	 * Decrypts a string from ciphertext, assuming UTF-8 format data
	 * 
	 * @param key           AES Secret Key
	 * @param encryptedData encrypted byte[] data to decrypt (ciphertext)
	 * @return The decrypted String
	 */
	public static String decryptString(SecretKey key, byte[] encryptedData) {
		return new String(decrypt(key, encryptedData), StandardCharsets.UTF_8);
	}

	/**
	 * Decrypts authenticated AES-GCM ciphertext with a given secret key.
	 * 
	 * @param key Secret encryption key
	 * @param encryptedData Encrypted data to decrypt
	 * @return A new byte array containing the decrypted data
	 */
	public static byte[] decrypt(SecretKey key, byte[] encryptedData) {
		return decryptBytes(key,encryptedData);
	}

	/**
	 * Decrypts authenticated AES-GCM ciphertext read from an input stream.
	 * 
	 * @param key Secret encryption key
	 * @param input InputStream of data to decrypt
	 * @return A new byte array containing the decrypted data
	 * @throws IOException If an IO error occurs
	 */
	public static byte[] decrypt(SecretKey key, InputStream input) throws IOException {
		if (input==null) throw new IllegalArgumentException("Input cannot be null");
		return decryptBytes(key,Utils.readBytes(input));
	}

	private static byte[] decryptBytes(SecretKey key, byte[] encryptedData) {
		validateKey(key);
		if (encryptedData==null) throw new IllegalArgumentException("Encrypted data cannot be null");
		int payloadOffset=FORMAT_HEADER.length+NONCE_LENGTH;
		if ((encryptedData.length<payloadOffset+TAG_LENGTH)
				||!startsWith(encryptedData,FORMAT_HEADER)) {
			throw new IllegalArgumentException(
					"Unsupported symmetric ciphertext format; legacy AES-CBC data is not accepted");
		}
		try {
			Cipher cipher=Cipher.getInstance(SYMMETRIC_ENCRYPTION_ALGO);
			cipher.init(Cipher.DECRYPT_MODE,key,
					new GCMParameterSpec(TAG_BITS,encryptedData,FORMAT_HEADER.length,NONCE_LENGTH));
			cipher.updateAAD(FORMAT_HEADER);
			return cipher.doFinal(encryptedData,payloadOffset,encryptedData.length-payloadOffset);
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("Ciphertext authentication failed",e);
		}
	}

	private static boolean startsWith(byte[] data, byte[] prefix) {
		return Arrays.equals(data,0,prefix.length,prefix,0,prefix.length);
	}

	private static void validateKey(SecretKey key) {
		if ((key==null)||!SYMMETRIC_KEY_ALGORITHM.equalsIgnoreCase(key.getAlgorithm())) {
			throw new IllegalArgumentException("An AES secret key is required");
		}
	}

	/**
	 * Creates an AES secret key
	 * 
	 * @return The generated SecretKey
	 */
	public static SecretKey createSecretKey() {
		KeyGenerator kgen;

		try {
			kgen = KeyGenerator.getInstance(SYMMETRIC_KEY_ALGORITHM);
			kgen.init(KEY_LENGTH);
		} catch (NoSuchAlgorithmException e) {
			throw new Panic("Key generator not initialised successfully", e);
		}
		SecretKey key = kgen.generateKey();
		return key;
	}

}
