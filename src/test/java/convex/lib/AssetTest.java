package convex.lib;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static convex.core.lang.TestState.stepAs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.Context;
import convex.core.lang.TestState;

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
		
		// Get user balances and total balance, ensure they are not empty
		ACell balance1=eval(ctx,"(def bal1 (asset/balance token user1))");
		assertNotNull(balance1);
		assertNotEquals(empty,balance1);
		ACell balance2=eval(ctx,"(def bal2 (asset/balance token user2))");
		assertNotNull(balance1);
		assertNotEquals(empty,balance2);
		ACell total=eval(ctx,"(asset/quantity-add token bal1 bal2)");
		assertNotNull(total);
		assertNotEquals(empty,total);
		
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

}
