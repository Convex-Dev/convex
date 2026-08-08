package convex.etch;

import java.nio.charset.StandardCharsets;

import convex.core.crypto.HKDF;

/**
 * Fixed Etch v3 derivations from caller-supplied master-key material.
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
public final class EtchKeyDerivation {
	private static final int DERIVED_KEY_LENGTH=32;
	private static final byte[] MASTER_KEY_CONTEXT=
			"convex-etch-master-key-v1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] FILE_CIPHER_CONTEXT=
			"convex-etch-v3-file-cipher".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] HEADER_MAC_CONTEXT=
			"convex-etch-v3-header-mac".getBytes(StandardCharsets.US_ASCII);

	private EtchKeyDerivation() {
	}

	/**
	 * Derives the standard Convex Etch master key from a 32-byte high-entropy
	 * source such as an Ed25519 seed. The returned array is newly allocated and
	 * remains caller-owned when returned by an Etch key function.
	 */
	public static byte[] deriveMasterKey(byte[] sourceKey) {
		if ((sourceKey==null)||(sourceKey.length!=EtchConstants.V3_MASTER_KEY_SIZE)) {
			throw new IllegalArgumentException("Etch master-key source must be exactly "
					+EtchConstants.V3_MASTER_KEY_SIZE+" bytes");
		}
		return HKDF.derive(sourceKey,null,MASTER_KEY_CONTEXT,DERIVED_KEY_LENGTH);
	}

	static byte[] deriveFileCipherKey(byte[] masterKey, byte[] fileSalt) {
		return deriveKey(masterKey,fileSalt,FILE_CIPHER_CONTEXT);
	}

	static byte[] deriveHeaderMacKey(byte[] masterKey, byte[] fileSalt) {
		return deriveKey(masterKey,fileSalt,HEADER_MAC_CONTEXT);
	}

	private static byte[] deriveKey(byte[] masterKey, byte[] fileSalt, byte[] context) {
		if ((masterKey==null)||(masterKey.length!=EtchConstants.V3_MASTER_KEY_SIZE)) {
			throw new IllegalArgumentException("Etch master key must be exactly "
					+EtchConstants.V3_MASTER_KEY_SIZE+" bytes");
		}
		if ((fileSalt==null)||(fileSalt.length!=EtchConstants.V3_FILE_SALT_SIZE)) {
			throw new IllegalArgumentException("Etch v3 file salt must be exactly "
					+EtchConstants.V3_FILE_SALT_SIZE+" bytes");
		}
		int combined=0;
		for (byte b:fileSalt) combined|=b;
		if (combined==0) throw new IllegalArgumentException("Etch v3 file salt must not be all zero");
		return HKDF.derive(masterKey,fileSalt,context,DERIVED_KEY_LENGTH);
	}
}
