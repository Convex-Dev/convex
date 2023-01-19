package convex.core.crypto;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import convex.core.crypto.sodium.SodiumProvider;
import convex.core.data.ABlob;
import convex.core.data.AccountKey;


/**
 * Utility class for handling crypto providers for Convex.
 * 
 * We want to make sure crypto providers are switchable, but without the overhead of going via JCA.
 */
public class Providers {
	
	static {
		Security.addProvider(new BouncyCastleProvider());
	}
	
	public static void init() {
		// static method to ensure static initialisation happens
	}

	public static boolean verify(ASignature signature, ABlob message, AccountKey publicKey) {
		byte[] sigBytes=signature.getBytes();
		boolean verified = SodiumProvider.SODIUM_SIGN.cryptoSignVerifyDetached(sigBytes, message.getBytes(), (int)message.count(), publicKey.getBytes());
		return verified;
	}
}
