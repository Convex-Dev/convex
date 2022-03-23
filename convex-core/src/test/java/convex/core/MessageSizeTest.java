package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Format;
import convex.core.data.StringShort;
import convex.test.Samples;

public class MessageSizeTest {

	@Test public void testMaxEmbedded() {
		assertEquals(Format.MAX_EMBEDDED_LENGTH,Samples.MAX_EMBEDDED_BLOB.getEncoding().count());
		assertTrue(Samples.MAX_EMBEDDED_BLOB.isEmbedded());
		
		assertEquals(Format.MAX_EMBEDDED_LENGTH+1,Samples.NON_EMBEDDED_BLOB.getEncoding().count());
		assertFalse(Samples.NON_EMBEDDED_BLOB.isEmbedded());
	}
	
	@Test public void testEmbeddedStrings() {
		assertTrue(Format.MAX_EMBEDDED_LENGTH>=Samples.MAX_EMBEDDED_STRING.getEncoding().count());
		assertEquals(StringShort.MAX_EMBEDDED_STRING_LENGTH,Samples.MAX_EMBEDDED_STRING.count());
		assertEquals(Format.MAX_EMBEDDED_LENGTH,Samples.MAX_EMBEDDED_STRING.getEncoding().count());
		assertTrue(Samples.MAX_EMBEDDED_STRING.isEmbedded());
		
		assertTrue(Format.MAX_EMBEDDED_LENGTH<Samples.NON_EMBEDDED_STRING.getEncoding().count());
		assertFalse(Samples.NON_EMBEDDED_STRING.isEmbedded());
	}
	
}
