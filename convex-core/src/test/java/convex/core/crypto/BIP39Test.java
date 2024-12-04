package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import convex.core.data.Blob;

public class BIP39Test {
	@Test public void testWordList() {
		assertEquals(2048,BIP39.wordlist.length);
	}
	
	@Test
	public void testFromEntropy() {
		// Test vectors from https://github.com/trezor/python-mnemonic/blob/master/vectors.json
		{
			byte[] ent=Blob.fromHex("00000000000000000000000000000000").getBytes();
			String ph=BIP39.mnemonic(BIP39.createWordsAddingChecksum(ent, 12));
			assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",ph);
			Blob b=BIP39.getSeed(ph, "TREZOR");
			assertEquals("c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",b.toHexString());
		}
		
		{
			byte[] ent=Blob.fromHex("ffffffffffffffffffffffffffffffff").getBytes();
			String ph=BIP39.mnemonic(BIP39.createWordsAddingChecksum(ent, 12));
			assertEquals("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",ph);
			assertTrue(BIP39.checkSum(ph)); // should be valid checksum
		}
		
		{
			byte[] ent=Blob.fromHex("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c").getBytes();
			String ph=BIP39.mnemonic(BIP39.createWordsAddingChecksum(ent, 24));
			assertEquals("hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length",ph);
			doMnemonicTest(ph);
		}
	}
	
	@Test
	public void testExtendWord() {
		assertEquals("shallow",BIP39.extendWord("SHAL"));
		assertEquals("zoo",BIP39.extendWord(" zoo "));
		assertEquals("list",BIP39.extendWord("list"));
		assertEquals("capital",BIP39.extendWord("capi"));
		assertNull(BIP39.extendWord(""));
		assertNull(BIP39.extendWord("z"));
		assertNull(BIP39.extendWord(" zo"));
	}
	
	@Test
	public void testSeed() throws NoSuchAlgorithmException, InvalidKeySpecException {
		List<String> tw1=List.of("blue claw trip feature street glue element derive dentist rose daring cash".split(" "));
		String exSeed="8212cc694344bbc4ae70505948c58194c16cd10599b2e93f0f7f638aaa108009a5707f9274fc6bdeb23bf30783d0c2c7bb556a7aa7b9064dab6df9b8c469e39c";
		Blob seed = BIP39.getSeed(tw1,"");
		assertEquals(exSeed,seed.toHexString());
		
		AKeyPair kp=BIP39.seedToKeyPair(seed);
		assertNotNull(kp);
	}
	
	/**
	 * Test vector from https://iancoleman.io/bip39/
	 */
	@Test
	public void testExample15() throws NoSuchAlgorithmException, InvalidKeySpecException {
		String s1="slush blind shaft return gentle isolate notice busy silent toast joy again almost perfect century";
		List<String> tw1=List.of(s1.split(" "));
		String exSeed="8ac1d802490b34488eb265d72b3de8aa4cbe4ad0c674ccc083463a3cb9466ab11933f6251aec5b6b2260442435bd2f5257aa3fc219745f642295d8b6e401fe3f";
		Blob seed = BIP39.getSeed(tw1,"");
		assertEquals(exSeed,seed.toHexString());
		assertEquals(BIP39.SEED_LENGTH,seed.count());
		
		// Different string equals different seed
		String s2=s1.replaceAll(" " , "  ");
		assertNotEquals(s1,s2);
		assertNotEquals(exSeed,BIP39.getSeed(s2,"").toHexString());
	}
	
	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"   ",
			"dgfdiwe biuh ihu ",
			"equal pear fiber",
			"sorry river evoke equal pear fiber bitter shadow cattle key enforce valve   ",
			"   gate snack    turkey kick tell affair medal gallery scatter master dignity morning snake flower jealous",
			"behind emotion false squeeze private fever dragon keen rifle attend couple base entire push cart kingdom library twist family wear subway thumb slide february"}) 
	public void doMnemonicTest(String m) {
		String fail=BIP39.checkMnemonic(m);
		if (fail==null) {
			doValidStringTest(m);
		} else {
			doInvalidStringTest(m);
		}
	}

	@Test public void testNewlyGenerated() {
		doValidStringTest(BIP39.createSecureMnemonic(12));
		doValidStringTest(BIP39.createSecureMnemonic(15));
		doValidStringTest(BIP39.createSecureMnemonic(24));
		doValidStringTest(BIP39.createSecureMnemonic(3));
		doValidStringTest(BIP39.mnemonic(BIP39.createWords(new InsecureRandom(4), 3)));
		doValidStringTest(BIP39.mnemonic(BIP39.createWords(new InsecureRandom(16), 12)));
		
		String newGen=BIP39.createSecureMnemonic(24);
		assertEquals(newGen,BIP39.normaliseFormat(newGen));
	}
	
	@Test
	public void testDerivePath() {
		// Ed25199 Test vector 2 from : https://github.com/satoshilabs/slips/blob/master/slip-0010.md
		Blob seed = Blob.fromHex("fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542");	
		{
			Blob priv=SLIP10.deriveKeyPair(seed, "m").getSeed();
			assertEquals(priv,SLIP10.deriveKeyPair(seed, new int[0]).getSeed());
			assertEquals("171cb88b1b3c1db25add599712e36245d75bc65a1a5c9e18d76f9f2b1eab4012",priv.toHexString());
		}
		
		{
			Blob priv=SLIP10.deriveKeyPair(seed, "m/0").getSeed();
			assertEquals("1559eb2bbec5790b0c65d8693e4d0875b1747f4970ae8b650486ed7470845635",priv.toHexString());
		}
		
		{
			Blob priv=SLIP10.deriveKeyPair(seed, "m/0/2147483647/1/2147483646/2").getSeed();
			assertEquals("551d333177df541ad876a60ea71f00447931c0a9da16f227c11ea080d7391b8d",priv.toHexString());
		}


	}
	
	@Test 
	public void testValidStrings() {
		doValidStringTest("double liar property"); 
		
		// Another example from https://github.com/trezor/python-mnemonic/blob/master/vectors.json
		doValidStringTest("legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title");
	}

	private void doValidStringTest(String m) {
		assertTrue(BIP39.checkSum(m));

		String PP="pass";
		List<String> words=BIP39.getWords(m);
		int n=words.size();
		try {
			Blob seed=BIP39.getSeed(words, PP);
			assertEquals(BIP39.SEED_LENGTH,seed.count());
			
			// Wrong passphrase => different seed
			assertNotEquals(seed,BIP39.getSeed(words, "badpass"));
			
			AKeyPair kp=BIP39.seedToKeyPair(seed);
			assertNotNull(kp);
			
		} catch (Exception e) {
			fail("Unexpected Exception "+e);
		}
	
		// Tests for round trips to entropy
		byte[] bs=BIP39.mnemonicToBytes(m);
		List<String> rwords=BIP39.createWords(bs, n);
		String rm=BIP39.mnemonic(rwords);
		
		
		assertNull(BIP39.checkMnemonic(m),()->"For string: "+m);
		
		assertEquals(m,rm);
	}

	@Test 
	public void testInvalidStrings() {
		doInvalidStringTest("zzzz");
		doInvalidStringTest("");
		doInvalidStringTest("behind emotion"); // insufficient words
	}
	
	private void doInvalidStringTest(String m) {
		assertNotNull(BIP39.checkMnemonic(m));
	}
	
}
