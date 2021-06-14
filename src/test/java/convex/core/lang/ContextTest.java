package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertDepthError;
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
import convex.core.data.ABlobMap;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.BlobMaps;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.init.Init;
import convex.core.init.InitConfigTest;
import convex.core.lang.ops.Special;

/**
 * Tests for basic execution Context mechanics and internals
 */
public class ContextTest extends ACVMTest {

	protected ContextTest() {
		super(Init.createBaseAccounts(InitConfigTest.create()));
	}

	private final Address ADDR=context().getAddress();

	@Test
	public void testDefine() {
		Symbol sym = Symbol.create("the-test-symbol");

		final Context<?> c2 = context().define(sym, Strings.create("buffy"));
		assertCVMEquals("buffy", c2.lookup(sym).getResult());

		assertUndeclaredError(c2.lookup(Symbol.create("some-bad-symbol")));
	}

	@Test
	public void testQuery() {
		Context<?> c2 = context();
		c2=c2.query(Reader.read("(+ 1 2)"));
		assertNotSame(c2,context());
		assertCVMEquals(3L,c2.getResult());
		assertEquals(context().getDepth(),c2.getDepth(),"Query should preserve context depth");

		c2=c2.query(Reader.read("*address*"));
		assertEquals(c2.getAddress(),c2.getResult());
	}

	@Test
	public void testSymbolLookup() {
		Context<?> CTX=context();
		Symbol sym1=Symbol.create("count");
		assertEquals(Core.COUNT,CTX.lookup(sym1).getResult());

	}

	@Test
	public void testUndefine() {
		Symbol sym = Symbol.create("the-test-symbol");

		final Context<?> c2 = context().define(sym, Strings.create("vampire"));
		assertCVMEquals("vampire", c2.lookup(sym).getResult());

		final Context<?> c3 = c2.undefine(sym);
		assertUndeclaredError(c3.lookup(sym));

		final Context<?> c4 = c3.undefine(sym);
		assertSame(c3,c4);
	}

	@Test
	public void testExceptionalState() {
		Context<?> ctx=context();

		assertFalse(ctx.isExceptional());
		assertTrue(ctx.withError(ErrorCodes.ASSERT).isExceptional());
		assertTrue(ctx.withError(ErrorCodes.ASSERT,"Assert Failed").isExceptional());

		assertThrows(IllegalArgumentException.class,()->ctx.withError(null));

		assertThrows(Error.class,()->ctx.withError(ErrorCodes.ASSERT).getResult());
	}

	@Test
	public void testJuice() {
		Context<?> c=context();
		assertTrue(c.checkJuice(1000));

		// get a juice error if too much juice consumed
		assertJuiceError(c.consumeJuice(c.getJuice() + 1));

		// no error if all juice is consumed
		c=context();
		assertFalse(c.consumeJuice(c.getJuice()).isExceptional());
	}

	@Test
	public void testDepth() {
		Context<?> c=context();
		assertEquals(0L,c.getDepth());
		assertEquals(0L,evalL("*depth*"));
		assertEquals(1L,evalL("(do *depth*)"));
		assertEquals(2L,evalL("(do (do *depth*))"));

		// functions should add one level of depth
		assertEquals(1L,evalL("((fn [] *depth*))")); // invoke only
		assertEquals(2L,evalL("(do (defn f [] *depth*) (f))")); // do + invoke

		// In compiler unquote
		assertEquals(2L,evalL("~*depth*")); // compile, unquote
		assertEquals(3L,evalL("~(do *depth*)")); // compile+ unquote + do

		// in custom expander
		assertEquals(2L,evalL("(expand :foo (fn [x e] *depth*))")); // in expand, invoke
		assertEquals(1L,evalL("(expand *depth* (fn [x e] x))")); // in expand arg


		// In expansion, should be equivalent to expanded code
		assertEquals(evalL("*depth*"),evalL("`~*depth*"));
		assertEquals(evalL("(do *depth*)"),evalL("`~(do *depth*)"));

	}

	@Test
	public void testDepthLimit() {
		Context<?> c=context().withDepth(Constants.MAX_DEPTH-1);
		assertEquals(Constants.MAX_DEPTH-1,c.getDepth());

		// Can run 1 deep at this depth
		assertEquals(RT.cvm(Constants.MAX_DEPTH-1),c.execute(comp("*depth*")).getResult());
		assertNull(c.execute(comp("(do)")).getResult());

		// Shouldn't be possible to execute any Op beyond max depth
		assertDepthError(c.execute(comp("(do *depth*)")));
	}


	@Test
	public void testSpecial() {
		Context<?> ctx=context();
		assertEquals(ADDR, eval(Symbols.STAR_ADDRESS));
		assertEquals(ADDR, eval(Symbols.STAR_ORIGIN));
		assertNull(eval(Symbols.STAR_CALLER));

		// Compiler returns Special Op
		assertEquals(Special.forSymbol(Symbols.STAR_BALANCE),comp("*balance*"));

		assertNull(eval(Symbols.STAR_RESULT));
		assertCVMEquals(ctx.getJuice(), eval(Symbols.STAR_JUICE));
		assertCVMEquals(0L,eval(Symbols.STAR_DEPTH));
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
		Context<?> c = context();
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
		Context<?> ctx=context();
		String s = ctx.ednString();
		assertNotNull(s);
	}

	@Test
	public void testReturn() {
		Context<?> ctx=context();
		ctx = ctx.withResult(RT.cvm(100));
		assertEquals(ctx.getDepth(), ctx.getDepth());
	}

}
