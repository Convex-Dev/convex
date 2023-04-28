package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.Blobs;

public class SignKeyPairTest {

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


}
