package convex.lib;

import static convex.core.lang.TestState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Context;
import convex.core.lang.TestState;

/**
 * 
 * Generic tests for ANY digital asset compatible with the convex.asset API
 */
public class AssetTest {
	
	static final AKeyPair TEST_KP=AKeyPair.generate();
	
	static {
		
	}
	/**
	 * Generic tests for an Asset
	 * 
	 * Both users must have a non-empty balance.
	 * 
	 * @param ctx
	 * @param fun
	 */
	public static void doAssetTests (Context<?> ctx, Address asset, Address user1, Address user2) {
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
	
	public static void doUserAssetTests (Context<?> ctx, Address asset, Address user, ACell balance) {
		ctx=ctx.forkWithAddress(user);
		ctx=step(ctx,"(def ast (address "+asset+"))");
		assertEquals(asset,ctx.getResult());

		ctx=step(ctx,"(def bal (asset/balance "+asset+"))");
		assertEquals(balance,eval(ctx,"bal"));
		assertEquals(balance,eval(ctx,"(asset/quantity-add ast bal nil)"));
		assertEquals(balance,eval(ctx,"(asset/quantity-add ast nil bal)"));
		assertEquals(eval(ctx,"(asset/quantity-zero ast)"),eval(ctx,"(asset/quantity-sub ast bal bal)"));

		assertTrue(evalB(ctx,"(asset/owns? *address* [ast bal])"));
		assertTrue(evalB(ctx,"(asset/owns? *address* [ast nil])"));
	}

}
