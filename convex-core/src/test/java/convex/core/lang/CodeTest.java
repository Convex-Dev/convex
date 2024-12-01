package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Symbols;
import convex.core.data.Lists;
import convex.core.data.prim.CVMLong;

public class CodeTest {
	@Test public void testQuote() {
		assertEquals(Lists.of(Symbols.QUOTE, Symbols.FOO),Code.quote(Symbols.FOO));
		assertEquals(Lists.of(Symbols.QUOTE, 1),Code.quote(CVMLong.ONE));
		
	}
}
