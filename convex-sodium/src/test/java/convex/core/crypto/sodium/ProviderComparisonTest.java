package convex.core.crypto.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.AProvider;
import convex.core.crypto.ASignature;
import convex.core.crypto.bc.BCProvider;
import convex.core.data.AccountKey;
import convex.core.data.Blob;

public class ProviderComparisonTest {

	protected static final AProvider SDPROVIDER=new SodiumProvider();
	protected static final AProvider BCPROVIDER=new BCProvider();

	
	@Test
	public void testGenerate() {
		Blob seed=Blob.createRandom(new Random(123), 32);
		
		AKeyPair kp1=SDPROVIDER.create(seed);
		AKeyPair kp2=BCPROVIDER.create(seed);
		
		assertEquals(kp1.getAccountKey(),kp2.getAccountKey());
		assertEquals(kp1.getSeed(),kp2.getSeed());
		
		Blob msg=Blob.wrap("Hello World!".getBytes());
		
		ASignature sig1=kp1.sign(msg);
		ASignature sig2=kp2.sign(msg);
		
		assertEquals(sig1,sig2);
		
		// Should verify with correct message
		AccountKey pubKey=kp1.getAccountKey();
		assertTrue(SDPROVIDER.verify(sig2, msg, pubKey));
		assertTrue(BCPROVIDER.verify(sig1, msg, pubKey));
		
		// Should fail with wrong message
		assertFalse(SDPROVIDER.verify(sig2, Blob.EMPTY, pubKey));
		assertFalse(BCPROVIDER.verify(sig1, Blob.EMPTY, pubKey));

	}
}
