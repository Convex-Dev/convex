package convex.core.crypto;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import convex.core.exceptions.Panic;

public class PBE {

	/**
	 * Gets a key of the given length (in bits) from a password using key derivation
	 * function
	 * 
	 * @param password Password stored in a char array.
	 * @param salt Salt bytes
	 * @param bitLength Bit length
	 * @return Decrypted key
	 */
	public static byte[] deriveKey(char[] password, byte[] salt, int bitLength) {
		try {
			SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
			PBEKeySpec pbeKeySpec = new PBEKeySpec(password, salt, 1000, bitLength);
			SecretKey secretKey = factory.generateSecret(pbeKeySpec);
			int byteLen = bitLength / 8;
			byte[] key = new byte[byteLen];
			System.arraycopy(secretKey.getEncoded(), 0, key, 0, byteLen);
			return key;
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new Panic(e);
		}
	}
}
