package convex.core.crypto;

import java.util.Arrays;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContext;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.Panic;

/**
 * Minimal ECIES-style encryption for Convex account keys.
 *
 * <p>This is HPKE Base mode from RFC 9180, using
 * DHKEM(X25519, HKDF-SHA256), HKDF-SHA256 and AES-128-GCM. Convex
 * {@link AccountKey AccountKeys} are Ed25519 keys, so they are converted to
 * X25519 using the conversion defined by libsodium. The corresponding X25519
 * private key is derived from the first 32 bytes of the Ed25519 secret key,
 * which is the Ed25519 seed exposed by {@link AKeyPair#getSeed()}.</p>
 *
 * <p>The encrypted value is a {@link Blob} with this byte layout:</p>
 *
 * <pre>
 * HPKE encapsulated key (32 bytes) || ciphertext || GCM tag (16 bytes)
 * </pre>
 *
 * <p>The suite is fixed by this API and is not encoded in the Blob. HPKE binds
 * the encapsulated key into its key schedule, providing confidentiality and
 * integrity, but Base mode does not authenticate the sender. Protocols that
 * can assign distinct long-term signing and encryption keys should prefer to
 * do so; this utility performs conversion specifically so existing
 * AccountKeys can be used.</p>
 */
public final class ECIES {

	/** Fixed overhead added by the encapsulated key and authentication tag. */
	public static final int OVERHEAD = 32 + 16;

	private static final int ENCAPSULATED_KEY_LENGTH = 32;
	private static final byte[] EMPTY = new byte[0];
	private ECIES() {
	}

	/**
	 * Encrypts a Blob for the holder of the private key corresponding to an
	 * AccountKey.
	 *
	 * @param recipient Recipient's Ed25519 AccountKey
	 * @param plaintext Plaintext Blob
	 * @return HPKE encrypted Blob
	 * @throws IllegalArgumentException If an argument is null or the AccountKey
	 *                                  is not a valid Ed25519 public key
	 */
	public static Blob encrypt(AccountKey recipient, Blob plaintext) {
		if (recipient == null) throw new IllegalArgumentException("Recipient AccountKey must not be null");
		if (plaintext == null) throw new IllegalArgumentException("Plaintext must not be null");

		return encryptHPKE(Ed25519X25519.publicKey(recipient), plaintext, EMPTY, EMPTY, null);
	}

	/** HPKE encryption path with injectable inputs for RFC 9180 vectors. */
	static Blob encryptHPKE(X25519PublicKeyParameters recipientKey, Blob plaintext, byte[] info, byte[] aad,
			AsymmetricCipherKeyPair ephemeralKeyPair) {
		HPKE hpke = createHPKE();
		HPKEContextWithEncapsulation context = (ephemeralKeyPair == null)
				? hpke.setupBaseS(recipientKey, info)
				: hpke.setupBaseS(recipientKey, info, ephemeralKeyPair);

		byte[] encapsulatedKey = context.getEncapsulation();

		try {
			byte[] ciphertext = context.seal(aad, plaintext.getBytes());
			// RFC 9180 returns enc and ct separately; this API serialises them as enc || ct.
			byte[] envelope = new byte[encapsulatedKey.length + ciphertext.length];
			System.arraycopy(encapsulatedKey, 0, envelope, 0, encapsulatedKey.length);
			System.arraycopy(ciphertext, 0, envelope, encapsulatedKey.length, ciphertext.length);
			return Blob.wrap(envelope);
		} catch (InvalidCipherTextException e) {
			// Encryption with a freshly-created context and valid inputs cannot fail.
			throw new Panic("ECIES encryption failed", e);
		}
	}

	/**
	 * Decrypts an ECIES Blob with the recipient's Ed25519 key pair.
	 *
	 * @param recipient Recipient key pair
	 * @param envelope HPKE encrypted Blob
	 * @return Decrypted plaintext Blob
	 * @throws BadFormatException If the envelope is malformed, was encrypted for
	 *                            another key, or fails authentication
	 */
	public static Blob decrypt(AKeyPair recipient, Blob envelope) throws BadFormatException {
		return createDecryptor(recipient).decrypt(envelope);
	}

	/**
	 * Creates a reusable decryptor for scanning multiple encrypted Blobs.
	 *
	 * <p>The decryptor retains only the derived X25519 key, not the supplied
	 * AKeyPair, its Ed25519 seed, or any processed message. A fresh HPKE context
	 * is still created for each Blob because every message has a distinct
	 * encapsulated key.</p>
	 *
	 * @param recipient Recipient key pair
	 * @return Reusable decryptor for the recipient
	 */
	public static Decryptor createDecryptor(AKeyPair recipient) {
		if (recipient == null) throw new IllegalArgumentException("Recipient key pair must not be null");
		return new Decryptor(Ed25519X25519.keyPair(recipient));
	}

	/**
	 * Creates a reusable decryptor directly from a 32-byte Ed25519 seed.
	 *
	 * @param recipientSeed Recipient's 32-byte Ed25519 seed
	 * @return Reusable decryptor for the recipient
	 */
	public static Decryptor createDecryptor(Blob recipientSeed) {
		if (recipientSeed == null) throw new IllegalArgumentException("Recipient seed must not be null");
		if (recipientSeed.count() != AKeyPair.SEED_LENGTH) {
			throw new IllegalArgumentException("Recipient seed must be 32 bytes");
		}
		return new Decryptor(Ed25519X25519.keyPair(recipientSeed));
	}

	/**
	 * Reusable recipient state for decrypting multiple independent ECIES Blobs.
	 * This class is immutable and safe to share between readers.
	 */
	public static final class Decryptor {

		private final AsymmetricCipherKeyPair recipientKey;

		private Decryptor(AsymmetricCipherKeyPair recipientKey) {
			this.recipientKey = recipientKey;
		}

		/**
		 * Decrypts one ECIES Blob. Failed authentication does not affect later calls.
		 *
		 * @param envelope HPKE encrypted Blob
		 * @return Decrypted plaintext Blob
		 * @throws BadFormatException If the envelope is malformed or fails authentication
		 */
		public Blob decrypt(Blob envelope) throws BadFormatException {
			if (envelope == null) throw new IllegalArgumentException("Encrypted Blob must not be null");
			if (envelope.count() < OVERHEAD) throw new BadFormatException("ECIES Blob is too short");
			return decryptHPKE(recipientKey, envelope, EMPTY, EMPTY);
		}
	}

	/** HPKE decryption path with injectable inputs for RFC 9180 vectors. */
	static Blob decryptHPKE(AsymmetricCipherKeyPair recipientKey, Blob envelope, byte[] info, byte[] aad)
			throws BadFormatException {
		byte[] bytes = envelope.getBytes();
		// Nenc is fixed at 32 for X25519, so enc || ct is unambiguous.
		byte[] encapsulatedKey = Arrays.copyOf(bytes, ENCAPSULATED_KEY_LENGTH);
		byte[] ciphertext = Arrays.copyOfRange(bytes, ENCAPSULATED_KEY_LENGTH, bytes.length);

		try {
			HPKEContext context = createHPKE().setupBaseR(encapsulatedKey, recipientKey, info);
			return Blob.wrap(context.open(aad, ciphertext));
		} catch (InvalidCipherTextException | IllegalArgumentException | IllegalStateException e) {
			throw new BadFormatException("ECIES Blob is invalid or failed authentication", e);
		}
	}

	/**
	 * Decrypts an ECIES Blob directly from a 32-byte Ed25519 seed.
	 *
	 * @param recipientSeed Recipient's 32-byte Ed25519 seed
	 * @param envelope HPKE encrypted Blob
	 * @return Decrypted plaintext Blob
	 * @throws BadFormatException If the envelope is malformed, was encrypted for
	 *                            another seed, or fails authentication
	 */
	public static Blob decrypt(Blob recipientSeed, Blob envelope) throws BadFormatException {
		return createDecryptor(recipientSeed).decrypt(envelope);
	}

	private static HPKE createHPKE() {
		return new HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256,
				HPKE.aead_AES_GCM128);
	}

}
