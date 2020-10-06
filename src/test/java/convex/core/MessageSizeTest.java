package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Format;
import convex.test.Samples;

public class MessageSizeTest {

	@Test public void testMaxEmbedded() {
		assertEquals(Format.MAX_EMBEDDED_LENGTH,Samples.MAX_EMBEDDED_BLOB.getEncoding().length());
		assertTrue(Samples.MAX_EMBEDDED_BLOB.isEmbedded());
		
		assertEquals(Format.MAX_EMBEDDED_LENGTH+1,Samples.NON_EMBEDDED_BLOB.getEncoding().length());
		assertFalse(Samples.NON_EMBEDDED_BLOB.isEmbedded());
	}
	
}
