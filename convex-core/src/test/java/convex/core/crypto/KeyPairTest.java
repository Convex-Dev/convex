package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Blobs;

public class KeyPairTest {

	@Test
	public void testSeeded() {
		AKeyPair kp1 = AKeyPair.createSeeded(13);
		AKeyPair kp2 = AKeyPair.createSeeded(13);
		assertTrue(kp1.equals(kp2));
		AKeyPair kp3 = AKeyPair.createSeeded(1337);
		assertFalse(kp1.equals(kp3) );
	}
	
	@Test public void testBadSeed() {
		assertThrows(Exception.class,()->AKeyPair.create(Blobs.empty()));
	}
	
	@Test 
	public void testBaseSeed() {
		// by hard-coding this, we catch unexpected changes in deterministic generation. Aeren't we clever?
		assertEquals("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29",AKeyPair.createSeeded(0).getAccountKey().toHexString());
	}

	/**
	 * Test vectors from RFC 8032
	 */
	@Test 
	public void testVectors() {
		Blob seed1=Blob.fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
		AKeyPair kp1=AKeyPair.create(seed1);
		assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",kp1.getAccountKey().toHexString());
		Blob msg1=Blobs.empty();
		assertEquals("e5564300c360ac729086e2cc806e828a"
			     	+ "84877f1eb8e5d974d873e06522490155"
			     	+ "5fb8821590a33bacc61e39701cf9b46b"
			     	+ "d25bf5f0595bbe24655141438e7a100b",kp1.sign(msg1).toHexString());
		
		Blob seed2=Blob.fromHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
		AKeyPair kp2=AKeyPair.create(seed2);
		assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",kp2.getAccountKey().toHexString());
		Blob msg2=Blob.fromHex("72");
		assertEquals("92a009a9f0d4cab8720e820b5f642540"
			     	+ "a2b27b5416503f8fb3762223ebdb69da"
			     	+ "085ac1e43e15996e458f3613d0f11d8c"
			     	+ "387b2eaeb4302aeeb00d291612bb0c00",kp2.sign(msg2).toHexString());

		Blob seed3=Blob.fromHex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7");
		AKeyPair kp3=AKeyPair.create(seed3);
		Blob msg3=Blob.fromHex("af82");
		assertEquals("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",kp3.getAccountKey().toHexString());
		assertEquals("6291d657deec24024827e69c3abe01a3"
			     	+ "0ce548a284743a445e3680d7db5ac3ac"
			     	+ "18ff9b538d16f290ae67f760984dc659"
			     	+ "4a7c15e9716ed28dc027beceea1ec40a",kp3.sign(msg3).toHexString());

	}
}
