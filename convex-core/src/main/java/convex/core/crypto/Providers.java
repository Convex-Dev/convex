package convex.core.crypto;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import convex.core.crypto.sodium.SodiumProvider;
import convex.core.data.ABlob;
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
		Security.addProvider(new BouncyCastleProvider());
		
		// Initialise Sodium provider
		SodiumProvider sp=new SodiumProvider();
		currentProvider=sp; 
		Security.addProvider(sp);
	}
	
	public static void init() {
		// Call this method from anywhere to ensure static initialisation happens
	}

	public static boolean verify(ASignature signature, ABlob message, AccountKey publicKey) {
		return currentProvider.verify(signature, message, publicKey);
	}

	public static AKeyPair generate() {
		return currentProvider.generate();
	}

	public static AKeyPair generate(Blob seed) {
		return currentProvider.generate(seed);
	}
}
