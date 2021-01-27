package convex.lib;

import static convex.core.lang.TestState.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static convex.test.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.test.Assertions;
import convex.test.Testing;

public class TestNFT {
	private static final Symbol nSym=Symbol.create("nft");

	private static Context<?> loadNFT() {
		Context<?> ctx=TestState.INITIAL_CONTEXT.fork();
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/nft-tokens.con")));
			Address nft=(Address) ctx.getResult();
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
	
	private static final Context<?> ctx=loadNFT().fork();
	
	@Test public void testSetup() {
		assertTrue(ctx.lookup(nSym).getValue() instanceof Address);
	}
	
	@Test public void testOneAccount() {
		Context<?> c=Testing.runTests(ctx,"contracts/nft/test1.con");
		Assertions.assertNotError(c);
	}
	
	@Test public void testTwoAccounts() {
		Context<?> c=ctx.fork();
		assertEquals(0L,ctx.getDepth());
		// set up p2 as a zombie account
		c=step(c,"(def p2 (address "+Init.VILLAIN+"))");
		c=TestState.stepAs(TestState.VILLAIN,c,"(do "
				+ "(import convex.asset :as asset)\r\n"
				+ "(import convex.nft-tokens :as nft)\r\n"
				+ "(def nft (get *aliases* 'nft))"
				+ "(set-controller "+TestState.HERO+"))");
		
		c=Testing.runTests(c,"contracts/nft/test2.con");
		Assertions.assertNotError(c);
		
		assertAssertError(step(c,"(do\r\n"
				+ "  (def t1 (call nft (create-token nil nil)))\r\n"
				+ "  (asset/transfer nft [nft t1] nil)\r\n"
				+ "  (asset/offer nft [nft t1]))"));
	}
}
