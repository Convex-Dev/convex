package convex.examples;

import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.data.Vectors;

/**
 * Test class for Ed25519 functionality
 */
public class SigSamples {

	public static void main(String[] args) {
		
		AKeyPair kp=Init.HERO_KP;
		AccountKey a=kp.getAccountKey();
		
		AVector<Long> v=Vectors.of(1L,2L);
		SignedData<AVector<Long>> sd=kp.signData(v);
	    System.out.println("Address: "+a);
	    System.out.println("Hash: "+v.getHash());
	    System.out.println("Signature: "+sd.getSignature().toString());
	}

}
