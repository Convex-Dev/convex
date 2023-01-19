package convex.core.crypto.sodium;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;

import convex.core.crypto.AProvider;

/**
 * Convex provider class for Sodium
 */
@SuppressWarnings("serial")
public class SodiumProvider extends AProvider {
	
	private static final SodiumJava NATIVE_SODIUM=new SodiumJava();
	
	public static final LazySodiumJava SODIUM= new LazySodiumJava(NATIVE_SODIUM);
	
	public static final Sign.Native SODIUM_SIGN=(Sign.Native) SODIUM;


	protected SodiumProvider(String name, String versionStr, String info) {
		super("Convex-Sodium", "1.0", "Native Sodium integration for Convex");
	}

}
