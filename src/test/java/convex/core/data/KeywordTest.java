package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.core.util.Text;
import convex.test.Samples;

public class KeywordTest {

	@Test
	public void testBadKeywords() {
		assertNotNull(Keyword.create(Text.whiteSpace(32)));

		// null return for invalid names
		assertNull(Keyword.create(Text.whiteSpace(33)));
		assertNull(Keyword.create(""));
		assertNull(Keyword.create((String)null));

		// exception for invalid names using createChecked
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked(Text.whiteSpace(33)));
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked(""));
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked((AString)null));
		assertThrows(IllegalArgumentException.class, () -> Keyword.createChecked((String)null));
	}

	@Test
	public void testBadFormat() {
		// should fail because this is an empty String
		assertThrows(BadFormatException.class, () -> Keyword.read(Blob.fromHex("00").toByteBuffer()));
	}

	@Test
	public void testNormalKeyword() {
		Keyword k = Keyword.create("foo");
		assertEquals(Samples.FOO, k);

		assertEquals("foo", k.getName().toString());
		assertEquals(":foo", k.toString());
		assertEquals(5, k.getEncoding().length); // tag+length+3 name

	}
}
