package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.Symbols;
import convex.core.util.Text;
import convex.test.Samples;

public class SymbolTest {

	@Test
	public void testBadSymbols() {
		assertNotNull(Symbol.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH)));

		assertNull(Symbol.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH+1)));
		assertNull(Symbol.create(""));
		assertNull(Symbol.create((String)null));
	}
	
	@Test
	public void testEmbedded() {
		// max length Symbol should be embedded
		Symbol s=Symbol.create(Text.whiteSpace(Constants.MAX_NAME_LENGTH));
		assertTrue(s.isEmbedded());
	}
	
	@Test 
	public void testMaxSize() {
		Symbol max=Symbol.create(Samples.MAX_SYMBOLIC);
		assertEquals(Constants.MAX_NAME_LENGTH,max.getName().count());
		
		Symbol blown=Symbol.create(Samples.TOO_BIG_SYMBOLIC);
		assertNull(blown);
	}
	
	@Test
	public void testToString() {
		assertEquals("foo",Symbols.FOO.toString());
	}

	@Test
	public void testBadFormat() {
		// should fail because this is an empty String
		assertThrows(BadFormatException.class, () -> Symbol.read(Blob.fromHex("00").toByteBuffer()));
	}

	@Test
	public void testNormalSymbol() {
		Symbol k = Symbol.create("count");
		assertEquals(Symbols.COUNT, k);

		assertEquals("count", k.getName().toString());
		assertEquals("count", k.toString());
		assertEquals(7, k.getEncoding().length); // tag(1) + length(1) + name(5)

	}
}
