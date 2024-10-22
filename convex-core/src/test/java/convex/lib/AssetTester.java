package convex.lib;

import static convex.test.Assertions.assertArgumentError;
import static convex.test.Assertions.assertArityError;
import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertFundsError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.prim.AInteger;
import convex.core.lang.ACVMTest;
import convex.core.lang.RT;
import convex.core.lang.TestState;
import convex.test.Samples;

/**
 * 
 * Generic tests for ANY digital asset compatible with the convex.asset API
 */
public class AssetTester extends ACVMTest {

	static final AKeyPair TEST_KP = AKeyPair.generate();

	static {

	}
	
	@Test 
	public void testAssetAPI() {
		Context ctx = step(context(), "(import convex.asset :as asset)");
		
		// not an [asset quantity] pair
		assertArgumentError(step(ctx,"(asset/transfer #15 :foo)"));
		
		// insufficient args
		assertArityError(step(ctx,"(asset/transfer #15)"));

	}

	/**
	 * Generic tests for a fungible token. User account should have some of fungible
	 * token and sufficient coins.
	 * 
	 * @param ctx   Initial Context. Will be forked.
	 * @param token Fungible token Address
	 * @param user  User Address
	 */
	public static void doFungibleTests(Context ctx, ACell token, Address user) {
		ctx = ctx.forkWithAddress(user);
		ctx = step(ctx, "(import convex.asset :as asset)");
		ctx = step(ctx, "(import convex.fungible :as fungible)");
		ctx = step(ctx, "(def token " + token + ")");
		ctx = step(ctx, "(def actor (deploy '(set-controller " + user + ")))");
		Address actor = (Address) ctx.getResult();
		assertNotNull(actor);

		Long BAL = evalL(ctx, "(asset/balance token *address*)");
		assertEquals(0L, evalL(ctx, "(asset/balance token actor)"));
		assertTrue(BAL > 0, "Should provide a user account with positive balance!");
		assertNull(eval(ctx, "(asset/check-transfer *address* *address* [token " + BAL + "])"));

		// Fungibles have a non-negative decimals
		{
			AInteger decimals=eval(ctx,"(fungible/decimals token)");
			assertTrue(decimals.signum().longValue()>=0);
		}
		
		// Fungibles have a non-negative total supply
		{
			AInteger supply=eval(ctx,"(fungible/total-supply token)");
			assertTrue(supply.signum().longValue()>=0);
			assertTrue(BAL<=supply.longValue());
		}
		
		// New Address gets zero offers
		{
			assertEquals(0L, evalL(ctx, "(asset/balance token (deploy nil))"));
			assertEquals(0L, evalL(ctx, "(asset/balance token (create-account *key*))"));
			assertEquals(0L, evalL(ctx, "(asset/get-offer token (create-account *key*) (deploy nil))"));
			assertEquals(0L, evalL(ctx, "(asset/balance token #675475647)"));
		}

		// New Address gets zero offers
		{
			assertEquals(0L, evalL(ctx, "(asset/balance token (deploy nil))"));
			assertEquals(0L, evalL(ctx, "(asset/balance token (create-account *key*))"));
			assertEquals(0L, evalL(ctx, "(asset/get-offer token (create-account *key*) (deploy nil))"));
		}

		// New Address offers work
		{
			Context ctxx = step(ctx, "(do (def a1 (deploy nil)))");
			assertEquals(0L, evalL(ctxx, "(asset/get-offer token *address* a1)"));
			ctxx = step(ctxx, "(asset/offer a1 token 1000)");
			assertCVMEquals(1000L, ctxx.getResult());
			assertEquals(1000L, evalL(ctxx, "(asset/get-offer token *address* a1)"));
			ctxx = step(ctxx, "(asset/offer a1 [token 0])");
			assertCVMEquals(0L, ctxx.getResult());
			assertEquals(0L, evalL(ctxx, "(asset/get-offer token *address* a1)"));
		}

		// Offer / accept to self work
		{
			Context ctxx = step(ctx, "(do (def BAL " + BAL + "))");
			ctxx = step(ctxx, "(asset/offer *address* token BAL)");
			assertCVMEquals(BAL, ctxx.getResult());
			assertEquals(BAL, evalL(ctxx, "(asset/get-offer token *address* *address*)"));

			// accepting one more token than offered at this point should be :STATE error
			assertError(step(ctxx, "(asset/accept *address* [token (inc BAL)])"));

			ctxx = step(ctxx, "(asset/accept *address* token BAL)");
			assertCVMEquals(BAL, ctxx.getResult());
			assertCVMEquals(BAL, evalL(ctxx, "(asset/balance token)"));

			// accepting one more token at this point should be :STATE error
			assertStateError(step(ctxx, "(asset/accept *address* [token 1])"));

			// Offer / accept of half balance should work
			ctxx = step(ctxx, "(asset/offer *address* token (div BAL 2))");
			assertCVMEquals(BAL / 2, ctxx.getResult());
			assertStateError(step(ctxx, "(asset/accept *address* [token (inc (div BAL 2))])"));
			ctxx = step(ctxx, "(asset/accept *address* token (div BAL 2))");
			assertCVMEquals(BAL / 2, ctxx.getResult());
			assertCVMEquals(BAL, evalL(ctxx, "(asset/balance token)"));

			// Offer / accept of more than balance should fail with :FUNDS
			ctxx = step(ctxx, "(asset/offer *address* token (inc BAL))");
			assertCVMEquals(BAL + 1, ctxx.getResult());
			assertFundsError(step(ctxx, "(asset/accept *address* [token (inc BAL)])"));

			// Set back to zero offer
			ctxx = step(ctxx, "(asset/offer *address* [token 0])");
			assertCVMEquals(0L, ctxx.getResult());
			assertEquals(0L, evalL(ctxx, "(asset/get-offer token *address* *address*)"));
		}

		// transfer all to self, should not affect balance
		ctx = step(ctx, "(asset/transfer *address* [token " + BAL + "])");
		assertEquals(BAL, RT.jvm(ctx.getResult()));
		assertEquals(BAL, evalL(ctx, "(asset/balance token *address*)"));
		
		// transfer nothing to self, should not affect balance
		ctx = step(ctx, "(asset/transfer *address* [token nil])");
		assertEquals(0L, (long) RT.jvm(ctx.getResult()));
		assertEquals(BAL, evalL(ctx, "(asset/balance token *address*)"));

		// set a zero offer
		ctx = step(ctx, "(asset/offer *address* [token 0])");
		assertCVMEquals(0, ctx.getResult());
		assertCVMEquals(0, eval(ctx, "(asset/get-offer token *address* *address*)"));
		
		// negative transfers fail, even to self
		assertArgumentError(step(ctx,"(asset/transfer *address* [token -1])"));
		assertArgumentError(step(ctx,"(fungible/transfer token *address* -1)"));


		// set a non-zero offer
		ctx = step(ctx, "(asset/offer *address* token 666)");
		assertCVMEquals(666, eval(ctx, "(asset/get-offer token *address* *address*)"));

		// nil offer sets to zero
		assertCVMEquals(0,
				eval(ctx, "(do (asset/offer *address* [token nil]) (asset/get-offer token *address* *address*))"));

		// Non-integer offer is an ARGUMENT error
		assertArgumentError(step(ctx, "(asset/offer *address* [token :foo])"));
		
		// Run generic asset tests, giving 1/3 the balance to a new user account
		{
			Context c = ctx.fork();
			c =  step(c,"(create-account "+Samples.ACCOUNT_KEY+")");
			Address user2 = (Address) c.getResult();
			Long smallBal = BAL / 3;
			c = step(c, "(asset/transfer " + user2 + " [token " + smallBal + "])");

			AssetTester.doAssetTests(c, token, user, user2);
		}

		// Test transfers to actor: failure cases
		ctx = step(ctx, "(def nully (deploy nil))");
		assertArityError(step(ctx, "(asset/transfer nully)"));
		assertStateError(step(ctx, "(asset/transfer nully token 10)"));
		assertStateError(step(ctx, "(asset/transfer nully token 10 :foo)"));
		assertArityError(step(ctx, "(asset/transfer nully token 10 :foo :bar)"));

		// Test transfers to actor: accept cases
		ctx = step(ctx,
				"(def sink (deploy `(defn ^:callable receive-asset [tok qnt data] (~asset/accept *caller* tok qnt))))");
		{
			Context c = step(ctx, "(asset/transfer sink token 10)");
			assertCVMEquals(10L, c.getResult());
			assertEquals(10L, evalL(c, "(asset/balance token sink)"));
			c = step(c, "(asset/transfer sink token 15)");
			assertCVMEquals(15L, c.getResult());
			assertEquals(25L, evalL(c, "(asset/balance token sink)"));
		}
	}

	/**
	 * Generic tests for an Asset
	 * 
	 * Both users must have a non-empty balance.
	 * 
	 * @param ctx   Context to execute in
	 * @param asset Address of asset
	 * @param user1 First user
	 * @param user2 Second user
	 */
	public static void doAssetTests(Context ctx, ACell asset, Address user1, Address user2) {
		// Set up test user
		ctx = step(ctx,"(create-account "+TEST_KP.getAccountKey()+")");
		Address tester = (Address) ctx.getResult();
		ctx = ctx.forkWithAddress(tester);
		ctx = step(ctx, "(import convex.asset :as asset)");
		ctx = step(ctx, "(def token " + asset + ")");
		ctx = step(ctx, "(def user1 " + user1 + ")");
		ctx = step(ctx, "(def user2 " + user2 + ")");
		ctx = TestState.step(ctx, "(def actor (deploy '(set-controller " + tester + ")))");
		Address actor = (Address) ctx.getResult();
		assertNotNull(actor);

		// Set up user imports
		ctx = stepAs(user1, ctx, "(import convex.asset :as asset)");
		ctx = stepAs(user2, ctx, "(import convex.asset :as asset)");

		// Tester balance should be the empty value
		ACell empty = eval(ctx, "(asset/balance token)");
		assertEquals(empty, eval(ctx, "(asset/quantity-zero token)"));

		// Get user balances and total balance, ensure they are not empty
		ctx = step(ctx, "(def bal1 (asset/balance token user1))");
		ACell balance1 = ctx.getResult();
		assertNotNull(balance1);
		assertNotEquals(empty, balance1,"User 1 should not have an empty balance");
		ctx = step(ctx, "(def bal2 (asset/balance token user2))");
		ACell balance2 = ctx.getResult();
		assertNotNull(balance2);
		assertNotEquals(empty, balance2,"User 2 should not have an empty balance");
		ACell total = eval(ctx, "(asset/quantity-add token bal1 bal2)");
		assertNotNull(total);
		assertNotEquals(empty, total);
		
		ACell supply=eval(ctx,"(asset/total-supply token)");
		if (supply!=null) {
			assertTrue(evalB(ctx,"(asset/quantity-contains? "+supply+" bal1)"));
			assertTrue(evalB(ctx,"(asset/quantity-contains? "+supply+" bal2)"));
		}
		
		// Trying to accept everything should be a STATE error (insufficient offer)
		assertStateError(step(ctx,"(asset/accept user1 token "+total+")"));
		
		// Trying to offer an invalid quantity should fail with ARGUMENT
		assertArgumentError(step(ctx,"(asset/offer user1 token :foobar)"));

		// Tests for each user
		doUserAssetTests(ctx, asset, user1, balance1);
		doUserAssetTests(ctx, asset, user2, balance2);

		// Test transferring everything to tester
		{
			Context c = ctx.fork();
			c = stepAs(user1, c, "(asset/transfer " + tester + " [" + asset + " (asset/balance " + asset + ")])");
			c = stepAs(user2, c, "(asset/transfer " + tester + " [" + asset + " (asset/balance " + asset + ")])");

			// user balances should now be empty
			assertEquals(empty, eval(c, "(asset/balance token user1)"));
			assertEquals(empty, eval(c, "(asset/balance token user2)"));

			// tester should own everything
			assertEquals(total, eval(c, "(asset/balance token)"));
		}

	}

	public static void doUserAssetTests(Context ctx, ACell asset, Address user, ACell balance) {
		ctx = ctx.forkWithAddress(user);
		ctx = step(ctx, "(def ast " + asset + ")");
		assertEquals(asset, ctx.getResult());

		ctx = step(ctx, "(def bal (asset/balance " + asset + "))");
		assertEquals(balance, eval(ctx, "bal"));
		assertEquals(balance, eval(ctx, "(asset/quantity-add ast bal nil)"));
		assertEquals(balance, eval(ctx, "(asset/quantity-add ast nil bal)"));
		assertEquals(eval(ctx, "(asset/quantity-zero ast)"), eval(ctx, "(asset/quantity-sub ast bal bal)"));

		assertTrue(evalB(ctx, "(asset/owns? *address* [ast bal])"));
		assertTrue(evalB(ctx, "(asset/owns? *address* ast bal)"));
		assertTrue(evalB(ctx, "(asset/owns? *address* [ast nil])"));
		assertTrue(evalB(ctx, "(asset/owns? *address* ast nil)"));

	}

}
