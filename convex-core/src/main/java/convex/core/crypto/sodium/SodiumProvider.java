package convex.core.crypto.sodium;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;

import convex.core.crypto.AProvider;
import convex.core.crypto.ASignature;
import convex.core.data.ABlob;
import convex.core.data.AccountKey;

/**
 * Convex provider class for Sodium
 */
@SuppressWarnings("serial")
public class SodiumProvider extends AProvider {
	
	private static final SodiumJava NATIVE_SODIUM=new SodiumJava();
	
	public static final LazySodiumJava SODIUM= new LazySodiumJava(NATIVE_SODIUM);
	
	public static final Sign.Native SODIUM_SIGN=(Sign.Native) SODIUM;

	public SodiumProvider() {
		super("Convex-Sodium", "1.0", "Native Sodium integration for Convex");
	}
	
	@Override
	public boolean verify(ASignature signature, ABlob message, AccountKey publicKey) {
		byte[] sigBytes=signature.getBytes();
		boolean verified = SodiumProvider.SODIUM_SIGN.cryptoSignVerifyDetached(sigBytes, message.getBytes(), (int)message.count(), publicKey.getBytes());
		return verified;
	}

}
