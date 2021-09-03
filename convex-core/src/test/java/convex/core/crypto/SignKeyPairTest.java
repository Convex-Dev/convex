package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

public class SignKeyPairTest {

	@Test
	public void testSeeded() {
		AKeyPair kp1 = AKeyPair.createSeeded(13);
		AKeyPair kp2 = AKeyPair.createSeeded(13);
		assertTrue(kp1.equals(kp2));
		AKeyPair kp3 = AKeyPair.createSeeded(1337);
		assertFalse(kp1.equals(kp3) );
	}


}
