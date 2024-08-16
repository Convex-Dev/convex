package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Blobs;

public class SLIP10Test {

	@Test 
	public void testSLIP10Vector1() throws InvalidKeyException, NoSuchAlgorithmException {
		// Tests from SLIP-10 spec, test vector 1
		
		Blob m=SLIP10.getMaster(Blob.fromHex("000102030405060708090a0b0c0d0e0f"));	
		assertEquals("90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb",m.slice(32, 64).toHexString());
		Blob ms=m.slice(0, 32);
		assertEquals("2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7",ms.toHexString());
		assertEquals("a4b2856bfec510abab89753fac1ac0e1112364e7d250545963f135f2a33188ed",AKeyPair.create(ms).getAccountKey().toHexString());
	
		assertEquals(m,SLIP10.derive(m));
		
		Blob m_0h=SLIP10.derive(m, 0);
		assertEquals("68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3",m_0h.slice(0,32).toHexString());

		Blob m_0h_1h=SLIP10.derive(m, 0,1);
		assertEquals("b1d0bad404bf35da785a64ca1ac54b2617211d2777696fbffaf208f746ae84f2",m_0h_1h.slice(0,32).toHexString());
		
		Blob m_0h_1h_2h=SLIP10.derive(m, 0,1,2);
		assertEquals("92a5b23c0b8a99e37d07df3fb9966917f5d06e02ddbd909c7e184371463e9fc9",m_0h_1h_2h.slice(0,32).toHexString());

		Blob m_0h_1h_2h_2h=SLIP10.derive(m, 0,1,2,2);
		assertEquals("30d1dc7e5fc04c31219ab25a27ae00b50f6fd66622f6e9c913253d6511d1e662",m_0h_1h_2h_2h.slice(0,32).toHexString());
		
		Blob m_last=SLIP10.derive(m, 0,1,2,2,1000000000);
		assertEquals("8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793",m_last.slice(0,32).toHexString());
	}
	
	@Test 
	public void testSLIP10Vector2() throws InvalidKeyException, NoSuchAlgorithmException {
		// Tests from SLIP-10 spec, test vector 2
		
		Blob m=SLIP10.getMaster(Blob.fromHex("fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542"));	
		assertEquals("ef70a74db9c3a5af931b5fe73ed8e1a53464133654fd55e7a66f8570b8e33c3b",m.slice(32, 64).toHexString());
		Blob ms=m.slice(0, 32);
		assertEquals("171cb88b1b3c1db25add599712e36245d75bc65a1a5c9e18d76f9f2b1eab4012",ms.toHexString());
		assertEquals("8fe9693f8fa62a4305a140b9764c5ee01e455963744fe18204b4fb948249308a",AKeyPair.create(ms).getAccountKey().toHexString());
	
		assertEquals(m,SLIP10.derive(m));
		
		Blob m_0h=SLIP10.derive(m, 0);
		assertEquals("1559eb2bbec5790b0c65d8693e4d0875b1747f4970ae8b650486ed7470845635",m_0h.slice(0,32).toHexString());

		Blob m_0h_1h=SLIP10.derive(m, 0,2147483647);
		assertEquals("ea4f5bfe8694d8bb74b7b59404632fd5968b774ed545e810de9c32a4fb4192f4",m_0h_1h.slice(0,32).toHexString());
		
		Blob m_0h_1h_2h=SLIP10.derive(m, 0,2147483647,1);
		assertEquals("3757c7577170179c7868353ada796c839135b3d30554bbb74a4b1e4a5a58505c",m_0h_1h_2h.slice(0,32).toHexString());

		Blob m_0h_1h_2h_2h=SLIP10.derive(m, 0,2147483647,1,2147483646);
		assertEquals("5837736c89570de861ebc173b1086da4f505d4adb387c6a1b1342d5e4ac9ec72",m_0h_1h_2h_2h.slice(0,32).toHexString());
		
		Blob m_last=SLIP10.derive(m, 0,2147483647,1,2147483646,2);
		assertEquals("551d333177df541ad876a60ea71f00447931c0a9da16f227c11ea080d7391b8d",m_last.slice(0,32).toHexString());
	}
	
	@Test 
	public void testCAD25 () {
		String seedPhrase="hold round save brand meat deposit armed idea taste reunion silent pair estate ladder copper";
		Blob bipSeed=BIP39.getSeed(seedPhrase, "test");
		// Verified using tool: https://iancoleman.io/bip39/
		assertEquals("d46c4e60d0137e7ee0acc8b836d76d9a0458705caa128899709f576bade690b3c7cba49ece50a211193b9eb7803be49d02c8ddae02c3b88790ac17fa72f219a6",bipSeed.toHexString());

		// Root Ed25519 private seed `m`
		Blob rootSeed=SLIP10.deriveKeyPair(bipSeed).getSeed();
		assertEquals(rootSeed,BIP39.seedToEd25519Seed(bipSeed));
		assertEquals("c2dac2f387243ac480162af7c1c21519e88a6b3a8e938daed9c062cc0606c0d9",rootSeed.toHexString());
		
		// Derived Ed25519 private seed `m/44/888/1234/0/1`
		AKeyPair kp2=SLIP10.deriveKeyPair(bipSeed, 44, 888, 1234,0,1);
		Blob priv=kp2.getSeed();
		assertEquals("2172bb864deb4f978ad6360beefe205d38a6839c011dc4f37592769007c8321f",priv.toHexString());
	}
	
	@Test
	public void testBIP39Compatibility() throws NoSuchAlgorithmException, InvalidKeySpecException {
		String phrase=BIP39.createSecureMnemonic(12);
		Blob seed=BIP39.getSeed(phrase, "test");
		
		AKeyPair kp1=BIP39.seedToKeyPair(seed);
		AKeyPair kp2=SLIP10.deriveKeyPair(seed);
		assertEquals(kp1,kp2);
		
	}
	
	@Test 
	public void testSLIP10EdgeCases () throws InvalidKeyException, NoSuchAlgorithmException {
		Blob seed=Blob.fromHex("DEADBEEF");
		Blob m=SLIP10.getMaster(seed);
		
		// hardened and normal indexes produce the same result for Ed25519
		assertEquals(SLIP10.derive(m, 0),SLIP10.derive(m, 0x80000000));
	}
	
	@Test 
	public void testSLIP10Fails () {
		assertThrows(IllegalArgumentException.class,()->SLIP10.derive(Blobs.empty(),1,2));
	}
	
} 
