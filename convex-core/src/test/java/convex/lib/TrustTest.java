package convex.lib;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertStateError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.init.BaseTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class TrustTest extends ACVMTest {
	private Address trusted;

	protected TrustTest() throws IOException {
		super(BaseTest.STATE);
	}

	@Override protected Context buildContext(Context ctx) {
		String importS = "(import convex.trust :as trust)";
		ctx = exec(ctx, importS);
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

		// A non-callable address trusts itself only
		assertTrue(evalB(ctx, "(trust/trusted? *address* *address*)"));
		assertFalse(evalB(ctx, "(trust/trusted? *address* nil)"));
		assertFalse(evalB(ctx, "(trust/trusted? *address* #456756)"));
		assertFalse(evalB(ctx, "(trust/trusted? *address* :foo)"));
		
		// A nil trust monitor reference always fails
		assertFalse(evalB(ctx, "(trust/trusted? nil *address*)"));
		
	}

	@Test
	public void testUpgrade() {
		Context ctx = CONTEXT.fork();

		// deploy an actor with upgradable capability
		ctx = exec(ctx, "(def wlist (deploy (trust/add-trusted-upgrade nil)))");
		Address wl = (Address) ctx.getResult();
		assertNotNull(wl);

		// do an upgrade that edits the actor
		ctx = exec(ctx, "(call wlist (upgrade '(def foo 2)))");

		{
			// check our villain cannot upgrade the actor!
			Address a1 = VILLAIN;
			
			Context c = ctx.forkWithAddress(a1);
			c = exec(c, "(do (import " + trusted + " :as trust) (def wlist " + wl + "))");

			assertTrustError(step(c, "(call wlist (upgrade '(def foo 3)))"));
		}

		// check that our edit has updated actor
		assertCVMEquals(2,eval(ctx, "wlist/foo"));
		
		testChangeControl(ctx,wl);
		
		// check we can permanently remove upgradability
		ctx = exec(ctx, "(trust/remove-upgradability! wlist)");
		assertStateError(step(ctx, "(call wlist (upgrade '(do :foo)))"));

		// actor functionality should still work otherwise
		assertCVMEquals(2,eval(ctx, "wlist/foo"));
		

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
