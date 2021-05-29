package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertJuiceError;
import static convex.test.Assertions.assertUndeclaredError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Init;
import convex.core.data.ABlobMap;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.BlobMaps;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.lang.ops.Lookup;
import convex.core.lang.ops.Special;

/**
 * Tests for basic execution Context mechanics and internals
 */
public class ContextTest extends ACVMTest {

	protected ContextTest() {
		super(Init.createBaseAccounts());
	}

	private final Context<?> CTX = context();
	private final Address ADDR=CTX.getAddress();

	@Test
	public void testDefine() {
		Symbol sym = Symbol.create("the-test-symbol");

		final Context<?> c2 = CTX.fork().define(sym, Strings.create("buffy"));
		assertCVMEquals("buffy", c2.lookup(sym).getResult());

		assertUndeclaredError(c2.lookup(Symbol.create("some-bad-symbol")));
	}
	
	@Test
	public void testQuery() {
		Context<?> c2 = CTX.fork();
		c2=c2.query(Reader.read("(+ 1 2)"));
		assertNotSame(c2,CTX);
		assertCVMEquals(3L,c2.getResult());
		assertEquals(CTX.getDepth(),c2.getDepth(),"Query should preserve context depth");
		
		c2=c2.query(Reader.read("*address*"));
		assertEquals(c2.getAddress(),c2.getResult());
	}

	@Test
	public void testSymbolLookup() {
		Symbol sym1=Symbol.create("count");
		assertEquals(Core.COUNT,CTX.lookup(sym1).getResult());

		Symbol sym2=Symbol.create("count").withPath(Init.CORE_ADDRESS);
		assertEquals(Core.COUNT,CTX.lookup(sym2).getResult());

		Symbol sym3=Symbol.create("count").withPath(ADDR);
		assertUndeclaredError(CTX.lookup(sym3));
	}
	
	@Test
	public void testUndefine() {
		Symbol sym = Symbol.create("the-test-symbol");

		final Context<?> c2 = CTX.fork().define(sym, Strings.create("vampire"));
		assertCVMEquals("vampire", c2.lookup(sym).getResult());

		final Context<?> c3 = c2.undefine(sym);
		assertUndeclaredError(c3.lookup(sym));
		
		final Context<?> c4 = c3.undefine(sym);
		assertSame(c3,c4);
	}
	
	@Test
	public void testExceptionalState() {
		Context<?> ctx=CTX.fork();
		
		assertFalse(ctx.isExceptional());
		assertTrue(ctx.withError(ErrorCodes.ASSERT).isExceptional());
		assertTrue(ctx.withError(ErrorCodes.ASSERT,"Assert Failed").isExceptional());
		
		assertThrows(IllegalArgumentException.class,()->ctx.withError(null));
		
		assertThrows(Error.class,()->ctx.withError(ErrorCodes.ASSERT).getResult());
	}

	@Test
	public void testJuice() {
		Context<?> c=CTX.fork();
		assertTrue(c.checkJuice(1000));
		
		// get a juice error if too much juice consumed
		assertJuiceError(c.consumeJuice(c.getJuice() + 1));
		
		// no error if all juice is consumed
		c=CTX.fork();
		assertFalse(c.consumeJuice(c.getJuice()).isExceptional());
	}

	@Test
	public void testSpecial() {
		Context<?> ctx=CTX.fork();
		assertEquals(ADDR, eval(Symbols.STAR_ADDRESS));
		assertEquals(ADDR, eval(Symbols.STAR_ORIGIN));
		assertNull(eval(Symbols.STAR_CALLER));
		
		// Compiler returns Special Op
		assertEquals(Special.forSymbol(Symbols.STAR_BALANCE),comp("*balance*"));
		
		assertNull(eval(Symbols.STAR_RESULT));
		assertCVMEquals(ctx.getJuice(), eval(Symbols.STAR_JUICE));
		assertCVMEquals(1L,eval(Symbols.STAR_DEPTH));
		assertCVMEquals(ctx.getBalance(ADDR),eval(Symbols.STAR_BALANCE));
		assertCVMEquals(0L,eval(Symbols.STAR_OFFER));
		
		assertCVMEquals(0L,eval(Symbols.STAR_SEQUENCE));

		assertCVMEquals(Constants.INITIAL_TIMESTAMP,eval(Symbols.STAR_TIMESTAMP));
		
		assertSame(ctx.getState(), eval(Symbols.STAR_STATE));
		assertSame(BlobMaps.empty(),eval(Symbols.STAR_HOLDINGS));
		
		assertUndeclaredError(ctx.eval(Symbol.create("*bad-special-symbol*")));
	}
	
	@Test
	public void testLog() {
		Context<?> c = CTX.fork();
		assertTrue(c.getLog().isEmpty());
		
		AVector<ACell> v=Vectors.of(1,2,3);
		c.appendLog(v);
		
		ABlobMap<Address,AVector<AVector<ACell>>> log=c.getLog();
		assertFalse(c.getLog().isEmpty());
		
		
		AVector<AVector<ACell>> alog=log.get(c.getAddress());
		assertEquals(1,alog.count());
		assertEquals(v,alog.get(0));
	}

	@Test
	public void testEdn() {
		Context<?> ctx=CTX.fork();
		String s = ctx.ednString();
		assertNotNull(s);
	}

	@Test
	public void testReturn() {
		Context<?> ctx=CTX.fork();
		ctx = ctx.withResult(RT.cvm(100));
		assertEquals(ctx.getDepth(), ctx.getDepth());
	}

}
