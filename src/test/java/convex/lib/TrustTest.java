package convex.lib;

import static convex.core.lang.TestState.HERO;
import static convex.core.lang.TestState.VILLAIN;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertStateError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.test.Samples;

public class TrustTest extends ACVMTest {
	private final Symbol tSym = Symbol.create("trust-actor");
	private Address trusted=null;

	
	protected TrustTest() throws IOException {
		super(Init.createCoreLibraries());
		Context<?> ctx = CONTEXT.fork();
		
		assert(ctx.getDepth()==0):"Invalid depth: "+ctx.getDepth();
		
		try {
			ctx = ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/trust.con")));
			assert(ctx.getDepth()==0):"Invalid depth: "+ctx.getDepth();
			Address trust = (Address) ctx.getResult();
			String importS = "(import " + trust + " :as trust)";
			ctx = step(ctx, importS);
			assertNotError(ctx);

			ctx = ctx.define(tSym, Syntax.create(trust));
		} catch (Throwable e) {
			e.printStackTrace();
			throw new Error(e);
		}

		CONTEXT=ctx.fork();
		INITIAL=ctx.getState();
		trusted = (Address) ctx.lookup(tSym).getResult();
	}


	
	/**
	 * Test that re-deployment of Fungible matches what is expected
	 */
	@Test
	public void testLibraryProperties() {
		assertTrue(CONTEXT.getAccountStatus(trusted).isActor());

		// check alias is set up correctly
		assertEquals(trusted, eval(CONTEXT, "(get *aliases* 'trust)"));
	}

	@Test
	public void testSelfTrust() {
		Context<?> ctx = CONTEXT.fork();

		assertTrue(evalB(ctx, "(trust/trusted? *address* *address*)"));
		assertFalse(evalB(ctx, "(trust/trusted? *address* nil)"));
		assertFalse(evalB(ctx, "(trust/trusted? *address* :foo)"));
		assertFalse(evalB(ctx,
				"(trust/trusted? *address* (address 666666))"));
	}

	@Test
	public void testUpgradeWhitelist() {
		Context<?> ctx = CONTEXT.fork();

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
			;
			Context<?> c = ctx.forkWithAddress(a1);
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
		Context<?> ctx = CONTEXT.fork();

		// deploy a whitelist with default config
		ctx = step(ctx, "(def wlist (deploy (trust/build-whitelist nil)))");
		Address wl = (Address) ctx.getResult();
		assertNotNull(wl);

		// initial creator should be on whitelist
		assertTrue(evalB(ctx, "(trust/trusted? wlist *address*)"));

		assertCastError(step(ctx, "(trust/trusted? wlist nil)"));
		assertCastError(step(ctx, "(trust/trusted? wlist [])"));

		assertCastError(step(ctx, "(trust/trusted? nil *address*)"));
		assertCastError(step(ctx, "(trust/trusted? [] *address*)"));

		{ // check adding and removing to whitelist
			Address a1 = Samples.BAD_ADDRESS;
			Context<?> c = ctx;

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
			;
			Address a2 = HERO;
			;
			Context<?> c = ctx.forkWithAddress(a1);
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
		Context<?> ctx = CONTEXT.fork();

		// deploy a blacklist with default config
		ctx = step(ctx, "(def blist (deploy (trust/build-blacklist {:blacklist [" + VILLAIN + "]})))");
		Address wl = (Address) ctx.getResult();
		assertNotNull(wl);

		// initial creator should not be on blacklist
		assertTrue(evalB(ctx, "(trust/trusted? blist *address*)"));

		// our villain should be on the blacklist
		assertFalse(evalB(ctx, "(trust/trusted? blist " + VILLAIN + ")"));

		assertCastError(step(ctx, "(trust/trusted? blist nil)"));
		assertCastError(step(ctx, "(trust/trusted? blist [])"));

		assertCastError(step(ctx, "(trust/trusted? nil *address*)"));
		assertCastError(step(ctx, "(trust/trusted? [] *address*)"));

		{ // check adding and removing to blacklist
			Address a1 = Samples.BAD_ADDRESS;
			Context<?> c = ctx;

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
			;
			Address a2 = HERO;
			;
			Context<?> c = ctx.forkWithAddress(a1);
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
		Context<?> ctx = CONTEXT.fork();

		// deploy an initially empty whitelist
		ctx = step(ctx, "(def wlist (deploy (trust/build-whitelist {:whitelist []})))");

		// deploy two actors
		ctx = step(ctx, "(def alice (deploy '(set-controller ~*address*)))");
		ctx = step(ctx, "(def bob (deploy '(set-controller ~wlist)))");

		// check initial trust
		assertEquals(Keywords.FOO, eval(ctx, "(eval-as alice :foo)"));
		assertTrustError(step(ctx, "(eval-as bob :foo)"));

		// add alice to the whitelist
		ctx = step(ctx, "(call wlist (set-trusted alice true))");

		// eval-as should work from alice to bob
		assertEquals(eval(ctx, "bob"), (Object) eval(ctx, "(eval-as alice '(eval-as ~bob '*address*))"));

		// remove alice from the whitelist
		ctx = step(ctx, "(call wlist (set-trusted alice false))");

		// eval-as should now fail
		assertTrustError(step(ctx, "(eval-as alice '(eval-as ~bob :foo))"));
	}
}
