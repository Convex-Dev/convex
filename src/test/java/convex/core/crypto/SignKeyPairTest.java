package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

public class SignKeyPairTest {

	@Test
	public void testSeeded() {
		assertEquals(ECDSAKeyPair.createSeeded(13), ECDSAKeyPair.createSeeded(13));

		assertNotEquals(ECDSAKeyPair.createSeeded(13), ECDSAKeyPair.createSeeded(1337));
	}
}
