package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class BIP39Test {

	@Test public void testWordList() {
		assertEquals(2048,BIP39.wordlist.length);
	}
}
