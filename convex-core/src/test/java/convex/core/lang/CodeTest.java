package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.Lists;

public class CodeTest {
	@Test public void testQuote() {
		assertEquals(Lists.of(Symbols.QUOTE, Symbols.FOO),Code.quote(Symbols.FOO));
		
		assertThrows(NullPointerException.class,()->Code.quote(null));
	}
}
