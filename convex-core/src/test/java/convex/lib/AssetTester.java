package convex.lib;

import static convex.core.lang.TestState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static convex.test.Assertions.*;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.lang.TestState;
import convex.test.Samples;

/**
 * 
 * Generic tests for ANY digital asset compatible with the convex.asset API
 */
public class AssetTester {
	
	static final AKeyPair TEST_KP=AKeyPair.generate();
	
	static {
		
	}
	
	/**
	 * Generic tests for a fungible token. User account should have some of fungible token and sufficient coins.
	 * @param ctx Initial Context. Will be forked.
	 * @param token Fungible token Address
	 * @param user User Address
	 */
	public static void doFungibleTests (Context<?> ctx, ACell token, Address user) {
		ctx=ctx.forkWithAddress(user);
		ctx=step(ctx,"(import convex.asset :as asset)");
		ctx=step(ctx,"(import convex.fungible :as fungible)");
		ctx=step(ctx,"(def token "+token+")");
		ctx = TestState.step(ctx,"(def actor (deploy '(set-controller "+user+")))");
		Address actor = (Address) ctx.getResult();
		assertNotNull(actor);

		Long BAL=evalL(ctx,"(asset/balance token *address*)");
		assertEquals(0L, evalL(ctx,"(asset/balance token actor)"));
		assertTrue(BAL>0,"Should provide a user account with positive balance!");

		// transfer all to self, should not affect balance
		ctx=step(ctx,"(asset/transfer *address* [token "+BAL+"])");
		assertEquals(BAL,RT.jvm(ctx.getResult()));
		assertEquals(BAL, evalL(ctx,"(asset/balance token *address*)"));

		// transfer nothing to self, should not affect balance
		ctx=step(ctx,"(asset/transfer *address* [token nil])");
		assertEquals(0L,(long)RT.jvm(ctx.getResult()));
		assertEquals(BAL, evalL(ctx,"(asset/balance token *address*)"));
		
		// set a zero offer
		ctx=step(ctx,"(asset/offer *address* [token 0])");
		assertCVMEquals(0, ctx.getResult());
		assertCVMEquals(0, eval(ctx,"(asset/get-offer token *address* *address*)"));

		// set a non-zero offer
		ctx=step(ctx,"(asset/offer *address* token 666)");
		assertCVMEquals(666, eval(ctx,"(asset/get-offer token *address* *address*)"));

		// Run generic asset tests, giving 1/3 the balance to a new user account
		{
			Context<?> c=ctx.fork();
			c=c.createAccount(Samples.ACCOUNT_KEY);
			Address user2=(Address) c.getResult();
			Long smallBal=BAL/3;
			c=step(c,"(asset/transfer "+user2+" [token "+smallBal+"])");

			AssetTester.doAssetTests(c, token, user, user2);
		}
	}
	
	/**
	 * Generic tests for an Asset
	 * 
	 * Both users must have a non-empty balance.
	 * 
	 * @param ctx Context to execute in
	 * @param asset Address of asset
	 * @param user1 First user
	 * @param user2 Second user
	 */
	public static void doAssetTests (Context<?> ctx, ACell asset, Address user1, Address user2) {
		// Set up test user
		ctx=ctx.createAccount(TEST_KP.getAccountKey());
		Address tester=(Address) ctx.getResult();
		ctx=ctx.forkWithAddress(tester);
		ctx=step(ctx,"(import convex.asset :as asset)");
		ctx=step(ctx,"(def token "+asset+")");
		ctx=step(ctx,"(def user1 "+user1+")");
		ctx=step(ctx,"(def user2 "+user2+")");
		ctx = TestState.step(ctx,"(def actor (deploy '(set-controller "+tester+")))");
		Address actor = (Address) ctx.getResult();
		assertNotNull(actor);
		
		// Set up user imports
		ctx=stepAs(user1,ctx,"(import convex.asset :as asset)");
		ctx=stepAs(user2,ctx,"(import convex.asset :as asset)");
		
		
		// Tester balance should be the empty value
		ACell empty=eval(ctx,"(asset/balance token)");
		assertEquals(empty,eval(ctx,"(asset/quantity-zero token)"));
		
		// Get user balances and total balance, ensure they are not empty
		ctx=step(ctx,"(def bal1 (asset/balance token user1))");
		ACell balance1=ctx.getResult();
		assertNotNull(balance1);
		assertNotEquals(empty,balance1);
		ctx=step(ctx,"(def bal2 (asset/balance token user2))");
		ACell balance2=ctx.getResult();
		assertNotNull(balance2);
		assertNotEquals(empty,balance2);
		ACell total=eval(ctx,"(asset/quantity-add token bal1 bal2)");
		assertNotNull(total);
		assertNotEquals(empty,total);
		
		// Tests for each user
		doUserAssetTests(ctx,asset,user1,balance1);
		doUserAssetTests(ctx,asset,user2,balance2);
		
		// Test transferring everything to tester
		{
			Context<?> c=ctx.fork();
			c=stepAs(user1,c,"(asset/transfer "+tester+" ["+asset+" (asset/balance "+asset+")])");
			c=stepAs(user2,c,"(asset/transfer "+tester+" ["+asset+" (asset/balance "+asset+")])");
			
			// user balances should now be empty
			assertEquals(empty,eval(c,"(asset/balance token user1)"));
			assertEquals(empty,eval(c,"(asset/balance token user2)"));
			
			// tester should own everything
			assertEquals(total,eval(c,"(asset/balance token)"));
		}
	
	}
	
	public static void doUserAssetTests (Context<?> ctx, ACell asset, Address user, ACell balance) {
		ctx=ctx.forkWithAddress(user);
		ctx=step(ctx,"(def ast "+asset+")");
		assertEquals(asset,ctx.getResult());

		ctx=step(ctx,"(def bal (asset/balance "+asset+"))");
		assertEquals(balance,eval(ctx,"bal"));
		assertEquals(balance,eval(ctx,"(asset/quantity-add ast bal nil)"));
		assertEquals(balance,eval(ctx,"(asset/quantity-add ast nil bal)"));
		assertEquals(eval(ctx,"(asset/quantity-zero ast)"),eval(ctx,"(asset/quantity-sub ast bal bal)"));

		assertTrue(evalB(ctx,"(asset/owns? *address* [ast bal])"));
		assertTrue(evalB(ctx,"(asset/owns? *address* ast bal)"));
		assertTrue(evalB(ctx,"(asset/owns? *address* [ast nil])"));
		assertTrue(evalB(ctx,"(asset/owns? *address* ast nil)"));
		
	
	}

}
