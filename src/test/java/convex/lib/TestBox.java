package convex.lib;

import static convex.core.lang.TestState.step;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.test.Assertions;
import convex.test.Testing;

public class TestBox {
	private static final Symbol nSym=Symbol.create("box");

	private static Context<?> loadBox() {
		Context<?> ctx=TestState.INITIAL_CONTEXT.fork();
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/box.con")));
			Address nft=(Address) ctx.getResult();
			assert (ctx.getDepth()==0):"Invalid depth: "+ctx.getDepth();
			String importS="(import "+nft+" :as "+nSym.getName()+")";
			ctx=step(ctx,importS);
			assertFalse(ctx.isExceptional());
			
			ctx=ctx.define(nSym, Syntax.create(nft));
		} catch (IOException e) {
			throw new Error(e);
		}
		ctx=step(ctx,"(import convex.asset :as asset)");
		return ctx;
	}
	
	private static final Context<?> ctx;
	
	static {
		ctx=loadBox();
	}
	
	@Test public void testSetup() {
		assertTrue(ctx.lookup(nSym).getValue() instanceof Address);
	}
	
	@Test public void testScript1() {
		Context<?> c=Testing.runTests(ctx,"contracts/box/test1.con");
		Assertions.assertNotError(c);
	}
}
