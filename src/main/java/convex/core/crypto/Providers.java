package convex.core.crypto;

import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class Providers {
	static {
		Security.addProvider(new BouncyCastleProvider());
	}
	
	public static void init() {
		// static method to ensure static initialisation happens
	}
}
