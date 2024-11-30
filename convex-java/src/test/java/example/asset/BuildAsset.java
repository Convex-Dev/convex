package example.asset;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.java.Convex;
import convex.java.asset.Fungible;
import convex.java.asset.TokenBuilder;

public class BuildAsset {
	/**
	 * URL for the Peer API server
	 */
	static final String TEST_PEER="https://convex.world";
	
	/**
	 * A new Ed25519 key to use for this example. 
	 */
	static final AKeyPair KP=AKeyPair.generate();

	public static void main(String [] args) {
		// Make a Convex connection to test network
		Convex convex=Convex.connect(TEST_PEER);
		System.out.println("Hello Convex! Connected to "+TEST_PEER);
		
		// Create a new account with our key pair
		Address address = convex.createAccount(KP);
		System.out.println("Created account "+address+" with public key "+KP.getAccountKey());
		
		// Request some Convex coins for testing. We need this to transact (but queries are free)
		System.out.println("Requesting Convex coins for testing");
		convex.faucet(address, 100000000);
		
		// Set our connection to use the current address and key pair
		convex.setAddress(address);
		convex.setKeyPair(KP);
		
		// Deploy a new fungible token with a specified total supply
		TokenBuilder tBuilder=new TokenBuilder().withSupply(999999999); 
		Fungible asset=tBuilder.deploy(convex);
		System.out.println("Created fungible token: "+asset);
		
		// Check our balance with the new token
		Long balance = asset.getBalance();
		System.out.println("Balance for address "+address.toString()+" is: "+balance);
		
		System.exit(0);
	}
}
