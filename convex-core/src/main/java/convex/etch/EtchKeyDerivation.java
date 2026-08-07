package convex.etch;

import java.nio.charset.StandardCharsets;

import convex.core.crypto.HKDF;

/**
 * Fixed Etch v3 key derivations from caller-supplied secret material.
 *
 * <p>Each key is the 32-byte output of RFC 5869 HKDF-SHA-256:</p>
 *
 * <pre>
 * PRK  = HMAC-SHA-256(salt=fileSalt, data=secret)
 * T(1) = HMAC-SHA-256(key=PRK, data=context || 0x01)
 * key  = T(1)
 * </pre>
 *
 * <p>{@code 0x01} is HKDF-Expand's mandatory single-octet block counter,
 * appended by the HKDF implementation rather than included in either context
 * constant. Only one expansion block is required because the requested key
 * length equals the SHA-256 output length.</p>
 */
final class EtchKeyDerivation {
	private static final int DERIVED_KEY_LENGTH=32;
	private static final byte[] FILE_CIPHER_CONTEXT=
			"convex-etch-v3-file-cipher".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] HEADER_MAC_CONTEXT=
			"convex-etch-v3-header-mac".getBytes(StandardCharsets.US_ASCII);

	private EtchKeyDerivation() {
	}

	static byte[] deriveFileCipherKey(byte[] secret, byte[] fileSalt) {
		return deriveKey(secret,fileSalt,FILE_CIPHER_CONTEXT);
	}

	static byte[] deriveHeaderMacKey(byte[] secret, byte[] fileSalt) {
		return deriveKey(secret,fileSalt,HEADER_MAC_CONTEXT);
	}

	private static byte[] deriveKey(byte[] secret, byte[] fileSalt, byte[] context) {
		if ((secret==null)||(secret.length==0)) {
			throw new IllegalArgumentException("Etch secret must not be empty");
		}
		if ((fileSalt==null)||(fileSalt.length!=EtchConstants.V3_FILE_SALT_SIZE)) {
			throw new IllegalArgumentException("Etch v3 file salt must be exactly "
					+EtchConstants.V3_FILE_SALT_SIZE+" bytes");
		}
		int combined=0;
		for (byte b:fileSalt) combined|=b;
		if (combined==0) throw new IllegalArgumentException("Etch v3 file salt must not be all zero");
		return HKDF.derive(secret,fileSalt,context,DERIVED_KEY_LENGTH);
	}
}
