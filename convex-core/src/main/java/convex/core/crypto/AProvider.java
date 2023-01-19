package convex.core.crypto;

import java.security.Provider;
import java.security.SecureRandom;

import convex.core.data.ABlob;
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
	public abstract boolean verify(ASignature signature, ABlob message, AccountKey publicKey);

	/**
	 * Generates a secure random key pair for this provider
	 * @return New key pair
	 */
	public AKeyPair generate() {
		return generate(new SecureRandom());
	}

	/**
	 * Generates a secure random key pair
	 * @param random A secure random instance
	 * @return New key pair
	 */
	public AKeyPair generate(SecureRandom random) {
		Blob seed=Blob.createRandom(random, 32);
		return generate(seed);
	}

	/**
	 * Create a key pair with the given seed
	 * @param seed
	 * @return
	 */
	protected abstract AKeyPair generate(Blob seed);

}
