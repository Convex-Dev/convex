package convex.lib;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

public class TestFungible {
	private static final Symbol fSym=Symbol.create("fun-actor");
	
	private static Context<?> loadFungible() {
		Context<?> ctx=TestState.INITIAL_CONTEXT;
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/fungible.con")), true);
			Address fun=(Address) ctx.getResult();
			String importS="(import "+fun+" :as fungible)";
			ctx=step(ctx,importS);
			assertFalse(ctx.isExceptional());
			
			ctx=ctx.define(fSym, Syntax.create(fun));
		} catch (IOException e) {
			throw new Error(e);
		}
		
		return ctx;
	}
	
	private static final Context<?> ctx=loadFungible();
	private static final Address fungible=(Address) ctx.lookup(fSym).getResult();
	
	/**
	 * Test that re-deployment of Fungible matches what is expected
	 */
	@Test public void testLibraryProperties() {
		assertTrue(ctx.getAccountStatus(fungible).isActor());
		assertEquals(fungible,TestState.CON_FUNGIBLE);
		
		assertEquals("Fungible Library",eval("(:name (call *registry* (lookup "+fungible+")))"));
	}
	
	@Test public void testBuildToken() {
		// check our alias is right
		Context<?> ctx=TestFungible.ctx;
		assertEquals(fungible,eval(ctx,"(get *aliases* 'fungible)"));
		
		ctx=step(ctx,"(def token (deploy (fungible/build-token nil)))");
		Address token = (Address) ctx.getResult();
		assertTrue(ctx.getAccountStatus(token)!=null);
	}
}
