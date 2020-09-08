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
	
	@Test public void testUpgradeWhitelist() {
		// check our alias is right
		Context<?> ctx=TestTrust.ctx;

		// deploy a whitelist with default config and upgradable capability
		ctx=step(ctx,"(def wlist (deploy [(trust/build-whitelist nil) (trust/add-trusted-upgrade nil)]))");
		Address wl=(Address) ctx.getResult();
		assertNotNull(wl);
		
		assertTrue(evalB(ctx,"(trust/trusted? wlist *address*)"));
		
		// do an upgrade that blanks the whitelist
		ctx=step(ctx,"(call wlist (upgrade '(do (def whitelist #{}))))");
		
		// check that our edit has updated actor
		assertFalse(evalB(ctx,"(trust/trusted? wlist *address*)"));
		
		// check we can permanently remove upgradability
		ctx=step(ctx,"(trust/remove-upgradability! wlist)");
		assertNotError(ctx);
		assertStateError(step(ctx,"(call wlist (upgrade '(do :foo)))"));
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
		
		{ // check adding and removing to whitelist
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
		
		{ // check the villain is excluded
			Address a1=VILLAIN;;
			Address a2=HERO;;
			Context<?> c=ctx.switchAddress(a1);
			c=step(c,"(do (import "+trusted+" :as trust) (def wlist (address "+wl+")))");
			assertNotError(c);
			
			// villain can still check monitor
			assertFalse(evalB(c,"(trust/trusted? wlist "+a1+")"));
			assertTrue(evalB(c,"(trust/trusted? wlist "+a2+")"));
			
			// villain can't change whitelist
			assertTrustError(step (c,"(call wlist (set-trusted "+a1+" true))"));
			assertTrustError(step (c,"(call wlist (set-trusted "+a2+" false))"));
		}
	}
	
	@Test public void testBlacklist() {
		Context<?> ctx=TestTrust.ctx;

		// deploy a blacklist with default config
		ctx=step(ctx,"(def blist (deploy (trust/build-blacklist {:blacklist ["+VILLAIN+"]})))");
		Address wl=(Address) ctx.getResult();
		assertNotNull(wl);
		
		// initial creator should not be on blacklist
		assertTrue(evalB(ctx,"(trust/trusted? blist *address*)"));
		
		// our villain should be on the blacklist
		assertFalse(evalB(ctx,"(trust/trusted? blist "+VILLAIN+")"));
		
		assertCastError(step(ctx,"(trust/trusted? blist nil)"));
		assertCastError(step(ctx,"(trust/trusted? blist [])"));
		
		assertCastError(step(ctx,"(trust/trusted? nil *address*)"));
		assertCastError(step(ctx,"(trust/trusted? [] *address*)"));
		
		{ // check adding and removing to blacklist
			Address a1=Samples.BAD_ADDRESS;
			Context<?> c=ctx;
			
			// Check not initially on blacklist
			assertTrue(evalB(c,"(trust/trusted? blist "+a1+")"));
	
			// Add address to blacklist, shouldn't matter if it exists or not
			c=step (c,"(call blist (set-trusted "+a1+" false))");
			assertNotError(c);
			assertFalse(evalB(c,"(trust/trusted? blist "+a1+")"));

			// Check removal from blacklist
			c=step (c,"(call blist (set-trusted "+a1+" true))");
			assertNotError(c);
			assertTrue(evalB(c,"(trust/trusted? blist "+a1+")"));
		}
		
		{ // check the villain is excluded
			Address a1=VILLAIN;;
			Address a2=HERO;;
			Context<?> c=ctx.switchAddress(a1);
			c=step(c,"(do (import "+trusted+" :as trust) (def blist (address "+wl+")))");
			assertNotError(c);
			
			// villain can still check monitor
			assertFalse(evalB(c,"(trust/trusted? blist "+a1+")"));
			assertTrue(evalB(c,"(trust/trusted? blist "+a2+")"));
			
			// villain can't change whitelist
			assertTrustError(step (c,"(call blist (set-trusted "+a1+" true))"));
			assertTrustError(step (c,"(call blist (set-trusted "+a2+" false))"));
		}
	}
}
