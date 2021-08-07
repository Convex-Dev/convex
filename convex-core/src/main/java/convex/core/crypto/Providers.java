package convex.core.crypto;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;



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
}
