package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.List;

import org.junit.jupiter.api.Test;

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
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
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
}
