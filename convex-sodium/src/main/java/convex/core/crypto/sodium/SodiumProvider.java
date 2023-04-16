package convex.core.crypto.sodium;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;

import convex.core.crypto.AProvider;
import convex.core.crypto.ASignature;
import convex.core.crypto.Providers;
import convex.core.data.AArrayBlob;
import convex.core.data.AccountKey;
import convex.core.data.Blob;

/**
 * Convex provider class for Sodium
 */
@SuppressWarnings("serial")
public class SodiumProvider extends AProvider {
	public static long verificationCount=0;

	
	private static final SodiumJava NATIVE_SODIUM=new SodiumJava();
	
	public static final LazySodiumJava SODIUM= new LazySodiumJava(NATIVE_SODIUM);
	
	public static final Sign.Native SODIUM_SIGN=(Sign.Native) SODIUM;

	public SodiumProvider() {
		super("Convex-Sodium", "1.0", "Native Sodium integration for Convex");
	}
	
	@Override
	public boolean verify(ASignature signature, AArrayBlob message, AccountKey publicKey) {
		byte[] sigBytes=signature.getBytes();
		byte[] msgBytes;
		int mlength=(int)message.count();
		if (message.getInternalOffset()==0) {
			msgBytes=message.getInternalArray();
		} else {
			// need to copy into new zero-based array
			msgBytes=message.getBytes();
		}
		boolean verified = SodiumProvider.SODIUM_SIGN.cryptoSignVerifyDetached(sigBytes, msgBytes, mlength, publicKey.getBytes());
		verificationCount++;
		return verified;
	}
	
	@Override
	public SodiumKeyPair create(Blob seed) {
		return SodiumKeyPair.create(seed);
	}
	
	public static void install() {
		Providers.setProvider(new SodiumProvider());
	}

}
