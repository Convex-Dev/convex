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

public class TestNFT {
	private static final Symbol nSym=Symbol.create("nft");

	private static Context<?> loadNFT() {
		Context<?> ctx=TestState.INITIAL_CONTEXT;
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/nft-tokens.con")), true);
			Address nft=(Address) ctx.getResult();
			String importS="(import "+nft+" :as "+nSym.getName()+")";
			ctx=step(ctx,importS);
			assertFalse(ctx.isExceptional());
			
			ctx=ctx.define(nSym, Syntax.create(nft));
		} catch (IOException e) {
			throw new Error(e);
		}
		
		return ctx;
	}
	
	private static final Context<?> ctx=loadNFT();
	
	@Test public void testSetup() {
		assertTrue(ctx.lookup(nSym).getValue() instanceof Address);
	}
}
