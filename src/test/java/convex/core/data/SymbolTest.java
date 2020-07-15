package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.Symbols;
import convex.core.util.Text;

public class SymbolTest {

	@Test
	public void testBadSymbols() {
		assertNotNull(Symbol.create(Text.whiteSpace(32)));

		assertNull(Symbol.create(Text.whiteSpace(33)));
		assertNull(Symbol.create(""));
		assertNull(Symbol.create(null));
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

		assertEquals("count", k.getName());
		assertEquals("count", k.toString());
		assertEquals(8, k.getEncoding().length); // tag(1) + null namespace(1) + length(1) + name(5)

	}
}
