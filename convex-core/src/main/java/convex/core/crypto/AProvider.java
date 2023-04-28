package convex.core.crypto;

import java.security.Provider;
import java.security.SecureRandom;

import convex.core.data.AArrayBlob;
import convex.core.data.AccountKey;
import convex.core.data.Blob;

/**
 * Abstract base class for Custom Convex security providers
 */
@SuppressWarnings("serial")
public abstract class AProvider extends Provider {

	protected AProvider(String name, String versionStr, String info) {
		super(name, versionStr, info);
	}

	/**
	 * Verify an Ed25519 Signature 
	 * @param signature Signature
	 * @param message Message 
	 * @param publicKey Public Key
	 * @return true if verified, false otherwise
	 */
	public abstract boolean verify(ASignature signature, AArrayBlob message, AccountKey publicKey);

	/**
	 * Generates a secure random key pair. Uses the default SecureRandom
	 * provider as provided by the current JVM environment.
	 * 
	 * @return New key pair
	 */
	public AKeyPair generate() {
		return generate(new SecureRandom());
	}

	/**
	 * Generates a key pair using the provided source of randomness. 
	 * 
	 * SECURITY WARNING: Security of the key pair depends on security of the source of 
	 * randomness
	 * 
	 * @param random A secure random instance
	 * @return New key pair
	 */
	public AKeyPair generate(SecureRandom random) {
		Blob seed=Blob.createRandom(random, 32);
		return create(seed);
	}

	/**
	 * Create a key pair with the given seed
	 * @param seed Seed bytes for Key generation (32 bytes)
	 * @return Key Pair instance
	 */
	public abstract AKeyPair create(Blob seed);

}
