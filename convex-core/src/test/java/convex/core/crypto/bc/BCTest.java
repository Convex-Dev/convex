package convex.core.crypto.bc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.lang.RT;

public class BCTest {
	
	BCProvider PROVIDER=new BCProvider();

	@Test
	public void testKeyGen() {
		AKeyPair kp1=PROVIDER.generate();
		AKeyPair kp2=PROVIDER.generate();
		assertNotEquals(kp1,kp2);
	}
	
	@Test
	public void testKeyRebuilding() {
		AKeyPair kp1=PROVIDER.generate();
		AKeyPair kp2=PROVIDER.create(kp1.getSeed());
		assertEquals(kp1,kp2);
		assertEquals(kp1.getAccountKey(),kp2.getAccountKey());

		ACell data=RT.cvm(1L);

		// TODO: figure out why encodings are different
		//assertEquals(kp1.getEncodedPrivateKey(),kp2.getEncodedPrivateKey());
		assertEquals(kp1.signData(data),kp2.signData(data));
	}
	
	@Test
	public void testRFC8032() {
		// From RFC8032 7.1
		{ // Empty message
			Blob seed=Blob.fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
			AccountKey pk=AccountKey.fromHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
			Blob msg=Blob.EMPTY;
			Blob esig=Blob.fromHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
			
			AKeyPair kp=PROVIDER.create(seed);
			assertEquals(pk,kp.getAccountKey());
			
			ASignature sig=kp.sign(msg);
			assertEquals(Ed25519Signature.fromBlob(esig),sig);
		}
	}

}
