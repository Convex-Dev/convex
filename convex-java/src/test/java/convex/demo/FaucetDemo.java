package convex.demo;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.java.Convex;

public class FaucetDemo {

	public static void main(String[] args) {
		Convex convex=Convex.connect("https://convex.world");
		
		// Create a key pair to use with Convex
		AKeyPair kp=AKeyPair.generate();
		
		// Create an account
		Address address= convex.createAccount(kp);
		System.out.println("Created account "+address);
		
		
		System.out.println("All done");
	}
}
