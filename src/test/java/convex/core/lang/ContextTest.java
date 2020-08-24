package convex.core.lang;

import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.data.BlobMaps;
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
	public void testUndefine() {
		Symbol sym = Symbol.create("the-test-symbol");

		final Context<?> c2 = c.define(sym, Syntax.create("vampire"));
		assertEquals("vampire", c2.lookup(sym).getResult());

		final Context<?> c3 = c2.undefine(sym);
		assertUndeclaredError(c3.lookup(sym));
		
		final Context<?> c4 = c3.undefine(sym);
		assertSame(c3,c4);
	}

	@Test
	public void testJuice() {
		assertTrue(c.checkJuice(1000));
		
		// get a juice error if too much juice consumed
		assertJuiceError(c.consumeJuice(c.getJuice() + 1));
		
		// no error if all juice is consumed
		assertFalse(c.consumeJuice(c.getJuice()).isExceptional());
	}

	@Test
	public void testSpecial() {
		assertEquals(TestState.HERO, c.lookupSpecial(Symbols.STAR_ADDRESS).getResult());
		assertEquals(TestState.HERO, c.lookupSpecial(Symbols.STAR_ORIGIN).getResult());
		assertNull(c.lookupSpecial(Symbols.STAR_CALLER).getResult());
		
		assertNull(c.lookupSpecial(Symbols.STAR_RESULT).getResult());
		assertEquals(c.getJuice(), c.lookupSpecial(Symbols.STAR_JUICE).getResult());
		assertEquals(0L,c.lookupSpecial(Symbols.STAR_DEPTH).getResult());
		assertEquals(c.getBalance(TestState.HERO),c.lookupSpecial(Symbols.STAR_BALANCE).getResult());
		assertEquals(0L,c.lookupSpecial(Symbols.STAR_OFFER).getResult());
		
		assertEquals(Constants.INITIAL_TIMESTAMP,c.lookupSpecial(Symbols.STAR_TIMESTAMP).getResult());
		
		assertSame(c.getState(), c.lookupSpecial(Symbols.STAR_STATE).getResult());
		assertSame(BlobMaps.empty(),c.lookupSpecial(Symbols.STAR_HOLDINGS).getResult());
		
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
