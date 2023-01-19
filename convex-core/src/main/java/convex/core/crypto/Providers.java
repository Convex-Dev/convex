package convex.core.crypto;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;

import convex.core.data.ABlob;
import convex.core.data.AccountKey;


/**
 * Utility class for handling crypto providers for Convex.
 * 
 * We want to make sure crypto providers are switchable, but without the overhead of going via JCA.
 */
public class Providers {
	private static final SodiumJava NATIVE_SODIUM=new SodiumJava();
	
	public static final LazySodiumJava SODIUM= new LazySodiumJava(NATIVE_SODIUM);
	
	public static final Sign.Native SODIUM_SIGN=(Sign.Native) SODIUM;
	
	static {
		Security.addProvider(new BouncyCastleProvider());
	}
	
	public static void init() {
		// static method to ensure static initialisation happens
	}

	public static boolean verify(ASignature signature, ABlob message, AccountKey publicKey) {
		byte[] sigBytes=signature.getBytes();
		boolean verified = Providers.SODIUM_SIGN.cryptoSignVerifyDetached(sigBytes, message.getBytes(), (int)message.count(), publicKey.getBytes());
		return verified;
	}
}
