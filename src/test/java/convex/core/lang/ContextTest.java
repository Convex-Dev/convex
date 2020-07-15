package convex.core.lang;

import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Symbol;
import convex.core.data.Syntax;

/**
 * Tests for execution context mechanics and internals
 */
public class ContextTest {

	private final Context<?> c = TestState.INITIAL_CONTEXT;

	@Test
	public void testDefine() {
		Symbol sym = Symbol.create("the-test-symbol");

		final Context<?> c2 = c.define(sym, Syntax.create("buffy"));
		assertEquals("buffy", c2.lookup(sym).getResult());

		assertUndeclaredError(c2.lookup(Symbol.create("some-bad-symbol")));
	}

	@Test
	public void testJuice() {
		assertTrue(c.checkJuice(1000));
		assertJuiceError(c.consumeJuice(c.getJuice() + 1));
	}

	@Test
	public void testSpecial() {
		assertEquals(TestState.HERO, c.lookupSpecial(Symbols.STAR_ADDRESS).getResult());
		assertUndeclaredError(c.lookupSpecial(Symbol.create("*bad-special-symbol*")));
		assertUndeclaredError(c.lookupSpecial(Symbol.create("count")));
	}

	@Test
	public void testEdn() {
		String s = c.ednString();
		assertNotNull(s);
	}

	@Test
	public void testReturn() {
		Context<Number> ctx = c.withResult(Long.valueOf(100));
		assertEquals(c.getDepth(), ctx.getDepth());
	}

}
