package convex.core.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/**
 * HKDF (HMAC-based Extract-and-Expand Key Derivation Function) as per RFC 5869.
 *
 * Uses SHA-256 as the underlying hash function via BouncyCastle.
 */
public class HKDF {

	/**
	 * Derives key material using HKDF with SHA-256.
	 *
	 * @param ikm  Input keying material
	 * @param salt Optional salt (can be null for zero-length salt)
	 * @param info Optional context/application-specific info (can be null)
	 * @param length Number of bytes to derive
	 * @return Derived key material of the specified length
	 */
	public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
		if (ikm == null) throw new IllegalArgumentException("Input keying material must not be null");
		if (length <= 0) throw new IllegalArgumentException("Output length must be positive");

		HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
		HKDFParameters params = new HKDFParameters(ikm, salt, info);
		hkdf.init(params);

		byte[] output = new byte[length];
		hkdf.generateBytes(output, 0, length);
		return output;
	}

	/**
	 * Derives 32 bytes (256 bits) of key material using HKDF with SHA-256.
	 * Convenience method for the common case of deriving AES-256 keys.
	 *
	 * @param ikm  Input keying material
	 * @param salt Optional salt (can be null)
	 * @param info Optional context info (can be null)
	 * @return 32-byte derived key
	 */
	public static byte[] derive256(byte[] ikm, byte[] salt, byte[] info) {
		return derive(ikm, salt, info, 32);
	}
}
