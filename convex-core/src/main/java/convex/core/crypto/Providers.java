package convex.core.crypto;

import java.security.Security;

import convex.core.crypto.bc.BCProvider;
import convex.core.data.AArrayBlob;
import convex.core.data.AccountKey;
import convex.core.data.Blob;


/**
 * Utility class for handling crypto providers for Convex.
 * 
 * We want to make sure crypto providers are switchable, but without the overhead of going via JCA.
 */
public class Providers {
	
	private static AProvider currentProvider;
	
	static {
		// Initialise BC provider
		Security.addProvider(BCProvider.BC);
		
		setProvider(new BCProvider());
	}
	
	public static void init() {
		// Call this method from anywhere to ensure static initialisation happens
	}

	public static boolean verify(ASignature signature, AArrayBlob message, AccountKey publicKey) {
		return currentProvider.verify(signature, message, publicKey);
	}

	public static AKeyPair generate() {
		return currentProvider.generate();
	}

	public static void setProvider(AProvider provider) {
		currentProvider=provider;
	}
	
	public static AKeyPair generate(Blob seed) {
		return currentProvider.create(seed);
	}
}
