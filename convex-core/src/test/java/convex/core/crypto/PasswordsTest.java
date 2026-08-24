package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PasswordsTest {

	@Test
	public void testCategoryBonus() {
		// Each character scores 2, each switch of character category scores 2, and each
		// distinct category used scores 2 more.
		// "az": two lowercase characters, one category, no switches
		assertEquals(2+2+2,Passwords.estimateEntropy("az"));

		// "a1": two characters, one switch, two categories used
		assertEquals(2+2+2+4,Passwords.estimateEntropy("a1"));
	}

	@Test
	public void testAdjacentCharactersPenalised() {
		assertTrue(Passwords.estimateEntropy("aq")>Passwords.estimateEntropy("ab"));
	}
}
