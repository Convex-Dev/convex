package convex.lib;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import convex.test.Samples;

import static convex.test.Assertions.*;
import static convex.core.lang.TestState.*;

public class TestTrust {
	private static final Symbol tSym=Symbol.create("trust-actor");
	
	private static Context<?> loadFungible() {
		Context<?> ctx=TestState.INITIAL_CONTEXT;
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/trust.con")), true);
			Address trust=(Address) ctx.getResult();
			String importS="(import "+trust+" :as trust)";
			ctx=step(ctx,importS);
			assertFalse(ctx.isExceptional());
			
			ctx=ctx.define(tSym, Syntax.create(trust));
		} catch (IOException e) {
			throw new Error(e);
		}
		
		return ctx;
	}
	
	private static final Context<?> ctx=loadFungible();
	private static final Address trusted=(Address) ctx.lookup(tSym).getResult();
	
	/**
	 * Test that re-deployment of Fungible matches what is expected
	 */
	@Test public void testLibraryProperties() {
		assertTrue(ctx.getAccountStatus(trusted).isActor());
		assertEquals(trusted,TestState.CON_TRUSTED);
		
		assertEquals(trusted,eval(ctx,"(get *aliases* 'trust)"));
		
		assertEquals("Trust Library",eval("(:name (call *registry* (lookup "+trusted+")))"));
	}
	
	@Test public void testSelfTrust() {
		// check our alias is right
		Context<?> ctx=TestTrust.ctx;
		
		assertTrue(evalB(ctx,"(trust/trusted? *address* *address*)"));
		assertFalse(evalB(ctx,"(trust/trusted? *address* nil)"));
		assertFalse(evalB(ctx,"(trust/trusted? *address* :foo)"));
		assertFalse(evalB(ctx,"(trust/trusted? *address* (address 0x1234567812345678123456781234567812345678123456781234567812345678))"));
	}
	
	@Test public void testWhitelist() {
		// check our alias is right
		Context<?> ctx=TestTrust.ctx;

		// deploy a whitelist with default config
		ctx=step(ctx,"(def wlist (deploy (trust/build-whitelist nil)))");
		Address wl=(Address) ctx.getResult();
		assertNotNull(wl);
		
		// initial creator should be on whitelist
		assertTrue(evalB(ctx,"(trust/trusted? wlist *address*)"));
		
		assertCastError(step(ctx,"(trust/trusted? wlist nil)"));
		assertCastError(step(ctx,"(trust/trusted? wlist [])"));
		
		assertCastError(step(ctx,"(trust/trusted? nil *address*)"));
		assertCastError(step(ctx,"(trust/trusted? [] *address*)"));
		
		{
			Address a1=Samples.BAD_ADDRESS;
			Context<?> c=ctx;
			
			// Check not initially on whitelist
			assertFalse(evalB(c,"(trust/trusted? wlist "+a1+")"));
	
			// Add address to whitelist, shouldn't matter if it exists or not
			c=step (c,"(call wlist (set-trusted "+a1+" true))");
			assertNotError(c);
			assertTrue(evalB(c,"(trust/trusted? wlist "+a1+")"));

			// Check removal from whitelist
			c=step (c,"(call wlist (set-trusted "+a1+" false))");
			assertNotError(c);
			assertFalse(evalB(c,"(trust/trusted? wlist "+a1+")"));
		}

	}
}
