package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.List;

import org.junit.jupiter.api.Test;

public class BIP39Test {

	@Test public void testWordList() {
		assertEquals(2048,BIP39.wordlist.length);
	}
	
	@Test
	public void testSeed() throws NoSuchAlgorithmException, InvalidKeySpecException {
		List<String> tw1=List.of("blue claw trip feature street glue element derive dentist rose daring cash".split(" "));
		String exSeed="8212cc694344bbc4ae70505948c58194c16cd10599b2e93f0f7f638aaa108009a5707f9274fc6bdeb23bf30783d0c2c7bb556a7aa7b9064dab6df9b8c469e39c";
		assertEquals(exSeed,BIP39.getSeed(tw1,"").toHexString());
	}
}
