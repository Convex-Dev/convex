package convex.core.crypto;

import java.security.Provider;

import convex.core.data.ABlob;
import convex.core.data.AccountKey;

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

}
