package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

public class SignKeyPairTest {

	@Test
	public void testSeeded() {
		assertEquals(AKeyPair.createSeeded(13), AKeyPair.createSeeded(13));

		assertNotEquals(AKeyPair.createSeeded(13), AKeyPair.createSeeded(1337));
	}
}
