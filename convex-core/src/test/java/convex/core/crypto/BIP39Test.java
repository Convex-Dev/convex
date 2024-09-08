package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
		
		String s2=s1.replaceAll(" " , "  ");
		assertNotEquals(s1,s2);
		assertEquals(exSeed,BIP39.getSeed(s2,"").toHexString());
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
		doValidStringTest(BIP39.createSecureMnemonic(3));
		doValidStringTest(BIP39.createSecureMnemonic(15));
		doValidStringTest("   "+BIP39.createSecureMnemonic(12));
		doValidStringTest(BIP39.createSecureMnemonic(24)+"\t");
		doValidStringTest(BIP39.mnemonic(BIP39.createWords(new InsecureRandom(4), 3)));
		doValidStringTest(BIP39.mnemonic(BIP39.createWords(new InsecureRandom(16), 12)));
		
		String newGen=BIP39.createSecureMnemonic(24);
		assertEquals(newGen,BIP39.normalise(newGen));
	}
	
	
	@Test 
	public void testValidStrings() {
		doValidStringTest("behind emotion squeeze"); // insufficient words
		doValidStringTest("behinD Emotion SQUEEZE"); // insufficient words
	}

	private void doValidStringTest(String m) {
		assertNull(BIP39.checkMnemonic(m));
		String PP="pass";
		List<String> words=BIP39.getWords(m);
		try {
			Blob seed=BIP39.getSeed(words, PP);
			assertEquals(BIP39.SEED_LENGTH,seed.count());
			
			// Wrong passphrase => different seed
			assertNotEquals(seed,BIP39.getSeed(words, "badpass"));
			
			// with extra whitespace is OK
			assertEquals(seed,BIP39.getSeed(" \t  "+m, PP));

			AKeyPair kp=BIP39.seedToKeyPair(seed);
			assertNotNull(kp);
			
		} catch (Exception e) {
			fail("Enexpected Exception "+e);
		}
	
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
