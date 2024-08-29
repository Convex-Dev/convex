package convex.core.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import convex.core.exceptions.Panic;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import convex.core.util.Utils;

/**
 * Class providing symmetric encryption functionality using AES
 */
public class Symmetric {
	// Bouncy castle provider
	static final BouncyCastleProvider PROVIDER = new org.bouncycastle.jce.provider.BouncyCastleProvider();

	private static final String SYMMETRIC_ENCRYPTION_ALGO = "AES/CBC/PKCS5Padding";
	private static final int IV_LENGTH = 16;
	private static final String SYMMETRIC_KEY_ALGORITHM = "AES";

	private static final int KEY_LENGTH = 128;

	/**
	 * Encrypts a String with a given AES secret key, using standard UTF-8 encoding
	 * 
	 * @param key  AES secret key
	 * @param data String to encrypt
	 * @return Encrypted representation of the given string
	 */
	public static byte[] encrypt(SecretKey key, String data) {
		return encrypt(key, data.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Encrypt bytes with a given AES SecretKey Prepends the IV to the ciphertext.
	 * 
	 * @param key Secret encryption key
	 * @param data Data to encrypt
	 * @return Encrypted representation of the given byte array data
	 */
	public static byte[] encrypt(SecretKey key, byte[] data) {
		Cipher cipher = null;
		byte[] iv = null;
		try {
			cipher = Cipher.getInstance(SYMMETRIC_ENCRYPTION_ALGO);
			cipher.init(Cipher.ENCRYPT_MODE, key);
			iv = cipher.getIV();
		} catch (GeneralSecurityException e) {
			throw new Error("Failed to initialise encryption cipher", e);
		}

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			bos.write(iv);
		} catch (IOException e) {
			throw new Error("Problem writing IV with value " + Utils.toHexString(iv), e);
		}

		CipherOutputStream cos = new CipherOutputStream(bos, cipher);
		try {
			cos.write(data);
			cos.flush();
			cos.close();
		} catch (IOException e) {
			throw new Error("Error encrypting data,e");
		}
		return bos.toByteArray();
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
	 * Decrypts AES ciphertext with a given secret key. IV is assumed to be
	 * prepended to the cipherText
	 * 
	 * @param key Secret encryption key
	 * @param encryptedData Encrypted data to decrypt
	 * @return A new byte array containing the decrypted data
	 */
	public static byte[] decrypt(SecretKey key, byte[] encryptedData) {
		ByteArrayInputStream bis = new ByteArrayInputStream(encryptedData);
		try {
			return decrypt(key, bis);
		} catch (IOException e) {
			throw new Error("Unexpected IO exception", e);
		}
	}

	/**
	 * Decrypts AES ciphertext with a given secret key. IV is assumed to be
	 * prepended to the cipherText
	 * 
	 * @param key Secret encryption key
	 * @param bis InputStream of data to decrypt
	 * @return A new byte array containing the decrypted data
	 * @throws IOException If an IO error occurs
	 */
	public static byte[] decrypt(SecretKey key, InputStream bis) throws IOException {
		byte[] iv = new byte[IV_LENGTH];
		int x = bis.read(iv, 0, IV_LENGTH);
		if (x != IV_LENGTH) throw new Error("IV not read correctly, " + x + " byes read");

		// Create a Cipher using the provided key
		Cipher cipher = null;
		try {
			cipher = Cipher.getInstance(SYMMETRIC_ENCRYPTION_ALGO);
			IvParameterSpec ivParamSpec = new IvParameterSpec(iv);
			cipher.init(Cipher.DECRYPT_MODE, key, ivParamSpec);
		} catch (GeneralSecurityException e) {
			throw new Error("Failed to initialise decryption cipher", e);
		}

		// Read unencrypted bytes using CipherInputStream
		CipherInputStream cis = new CipherInputStream(bis, cipher);
		try {
			return Utils.readBytes(cis);
		} catch (IOException e) {
			throw new Error("Failed to decrypt from input stream", e);
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
			throw new Panic("Key generator not initialised sucessfully", e);
		}
		SecretKey key = kgen.generateKey();
		return key;
	}

}
