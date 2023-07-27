package convex.lib;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.test.Samples;

public class TrustTest extends ACVMTest {
	private Address trusted;

	protected TrustTest() throws IOException {
		super(InitTest.BASE);
	}

	@Override protected Context buildContext(Context ctx) {
		String importS = "(import convex.trust :as trust)";
		ctx = step(ctx, importS);
		assertNotError(ctx);
		trusted = (Address)ctx.getResult();
		return ctx;
	}
	
	/**
	 * Test that re-deployment of Fungible matches what is expected
	 */
	@Test
	public void testLibraryProperties() {
		assertTrue(CONTEXT.getAccountStatus(trusted).isActor());

		// check alias is set up correctly
		assertEquals(trusted, eval(CONTEXT, "trust"));
	}

	@Test
	public void testSelfTrust() {
		Context ctx = CONTEXT.fork();

		assertTrue(evalB(ctx, "(trust/trusted? *address* *address*)"));
		assertFalse(evalB(ctx, "(trust/trusted? *address* nil)"));
		assertFalse(evalB(ctx, "(trust/trusted? *address* :foo)"));
		assertFalse(evalB(ctx,
				"(trust/trusted? *address* (address 666666))"));
	}

	@Test
	public void testUpgradeWhitelist() {
		Context ctx = CONTEXT.fork();

		// deploy a whitelist with default config and upgradable capability
		ctx = step(ctx, "(def wlist (deploy [(trust/build-whitelist nil) (trust/add-trusted-upgrade nil)]))");
		Address wl = (Address) ctx.getResult();
		assertNotNull(wl);

		assertTrue(evalB(ctx, "(trust/trusted? wlist *address*)"));

		// do an upgrade that blanks the whitelist
		ctx = step(ctx, "(call wlist (upgrade '(do (def whitelist #{}))))");

		{
			// check our villain cannot upgrade the actor!
			Address a1 = VILLAIN;
			
			Context c = ctx.forkWithAddress(a1);
			c = step(c, "(do (import " + trusted + " :as trust) (def wlist " + wl + "))");

			assertTrustError(step(c, "(call wlist (upgrade '(do :foo)))"));
		}

		// check that our edit has updated actor
		assertFalse(evalB(ctx, "(trust/trusted? wlist *address*)"));

		// check we can permanently remove upgradability
		ctx = step(ctx, "(trust/remove-upgradability! wlist)");
		assertNotError(ctx);
		assertStateError(step(ctx, "(call wlist (upgrade '(do :foo)))"));

		// actor functionality should still work otherwise
		assertFalse(evalB(ctx, "(trust/trusted? wlist *address*)"));
	}

	@Test
	public void testWhitelist() {
		// check our alias is right
		Context ctx = CONTEXT.fork();

		// deploy a whitelist with default config
		ctx = step(ctx, "(def wlist (deploy (trust/build-whitelist nil)))");
		Address wl = (Address) ctx.getResult();
		assertNotNull(wl);

		// initial creator should be on whitelist
		assertTrue(evalB(ctx, "(trust/trusted? wlist *address*)"));

		assertFalse(evalB(ctx, "(trust/trusted? wlist nil)"));
		assertFalse(evalB(ctx, "(trust/trusted? wlist [])"));

		assertCastError(step(ctx, "(trust/trusted? nil *address*)"));
		assertCastError(step(ctx, "(trust/trusted? [] *address*)"));

		{ // check adding and removing to whitelist
			Address a1 = Samples.BAD_ADDRESS;
			Context c = ctx;

			// Check not initially on whitelist
			assertFalse(evalB(c, "(trust/trusted? wlist " + a1 + ")"));

			// Add address to whitelist, shouldn't matter if it exists or not
			c = step(c, "(call wlist (set-trusted " + a1 + " true))");
			assertNotError(c);
			assertTrue(evalB(c, "(trust/trusted? wlist " + a1 + ")"));

			// Check removal from whitelist
			c = step(c, "(call wlist (set-trusted " + a1 + " false))");
			assertNotError(c);
			assertFalse(evalB(c, "(trust/trusted? wlist " + a1 + ")"));
		}

		{ // check the villain is excluded
			Address a1 = VILLAIN;
			Address a2 = HERO;
			
			Context c = ctx.forkWithAddress(a1);
			c = step(c, "(do (import " + trusted + " :as trust) (def wlist (address " + wl + ")))");
			assertNotError(c);

			// villain can still check monitor
			assertFalse(evalB(c, "(trust/trusted? wlist " + a1 + ")"));
			assertTrue(evalB(c, "(trust/trusted? wlist " + a2 + ")"));

			// villain can't change whitelist
			assertTrustError(step(c, "(call wlist (set-trusted " + a1 + " true))"));
			assertTrustError(step(c, "(call wlist (set-trusted " + a2 + " false))"));
		}
	}

	@Test
	public void testBlacklist() {
		Context ctx = CONTEXT.fork();

		// deploy a blacklist with default config
		ctx = step(ctx, "(def blist (deploy (trust/build-blacklist {:blacklist [" + VILLAIN + "]})))");
		Address wl = (Address) ctx.getResult();
		assertNotNull(wl);

		// initial creator should not be on blacklist
		assertTrue(evalB(ctx, "(trust/trusted? blist *address*)"));

		// our villain should be on the blacklist
		assertFalse(evalB(ctx, "(trust/trusted? blist " + VILLAIN + ")"));

		assertFalse(evalB(ctx, "(trust/trusted? blist nil)"));
		assertFalse(evalB(ctx, "(trust/trusted? blist [])"));

		assertCastError(step(ctx, "(trust/trusted? nil *address*)"));
		assertCastError(step(ctx, "(trust/trusted? [] *address*)"));

		{ // check adding and removing to blacklist
			Address a1 = Samples.BAD_ADDRESS;
			Context c = ctx;

			// Check not initially on blacklist
			assertTrue(evalB(c, "(trust/trusted? blist " + a1 + ")"));

			// Add address to blacklist, shouldn't matter if it exists or not
			c = step(c, "(call blist (set-trusted " + a1 + " false))");
			assertNotError(c);
			assertFalse(evalB(c, "(trust/trusted? blist " + a1 + ")"));

			// Check removal from blacklist
			c = step(c, "(call blist (set-trusted " + a1 + " true))");
			assertNotError(c);
			assertTrue(evalB(c, "(trust/trusted? blist " + a1 + ")"));
		}

		{ // check the villain is excluded
			Address a1 = VILLAIN;
			Address a2 = HERO;

			Context c = ctx.forkWithAddress(a1);
			c = step(c, "(do (import " + trusted + " :as trust) (def blist (address " + wl + ")))");
			assertNotError(c);

			// villain can still check monitor
			assertFalse(evalB(c, "(trust/trusted? blist " + a1 + ")"));
			assertTrue(evalB(c, "(trust/trusted? blist " + a2 + ")"));

			// villain can't change whitelist
			assertTrustError(step(c, "(call blist (set-trusted " + a1 + " true))"));
			assertTrustError(step(c, "(call blist (set-trusted " + a2 + " false))"));
		}
	}

	@Test
	public void testWhitelistController() {
		Context ctx = CONTEXT.fork();

		// deploy an initially empty whitelist
		ctx = step(ctx, "(def wlist (deploy (trust/build-whitelist {:whitelist []})))");

		// deploy two actors
		ctx = step(ctx, "(def alice (deploy `(set-controller ~*address*)))");
		ctx = step(ctx, "(def bob (deploy `(set-controller ~wlist)))");

		// check initial trust
		assertEquals(Keywords.FOO, eval(ctx, "(eval-as alice :foo)"));
		assertTrustError(step(ctx, "(eval-as bob :foo)"));

		// add alice to the whitelist
		ctx = step(ctx, "(call wlist (set-trusted alice true))");

		// eval-as should work from alice to bob
		assertEquals(eval(ctx, "bob"), (Object) eval(ctx, "(eval-as alice `(eval-as ~bob '*address*))"));

		// remove alice from the whitelist
		ctx = step(ctx, "(call wlist (set-trusted alice false))");

		// eval-as should now fail
		assertTrustError(step(ctx, "(eval-as alice `(eval-as ~bob :foo))"));
	}

	
	/**
	 * Tests change of control of a scoped target
	 * @param ctx Context from which to change control
	 * @param thing Entity to change control (Address or scope vector)
	 */
	public static void testChangeControl(Context ctx, ACell thing) {
		ctx=ctx.createAccount(null);
		Address nca=(Address) ctx.getResult();
		
		// Change control should work
		ctx=step(ctx,"(call "+thing+" (change-control "+nca+"))");
		assertNotError(ctx);
		
		// Should have lost control
		ctx=step(ctx,"(call "+thing+" (change-control "+nca+"))");
		assertTrustError(ctx);
		
		// Switch to new Address, should have control now
		ctx=ctx.forkWithAddress(nca);
		ctx=step(ctx,"(call "+thing+" (change-control "+nca+"))");
		assertNotError(ctx);	
	}
	

}
