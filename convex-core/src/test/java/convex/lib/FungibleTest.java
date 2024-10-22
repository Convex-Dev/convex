package convex.lib;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.cvm.State;
import convex.core.data.AMap;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;

public class FungibleTest extends ACVMTest {

	private Address VILLAIN=InitTest.VILLAIN;

	private Address fungible;

	protected FungibleTest() {
		super(buildState());
		fungible = (Address) context().lookup(Symbol.create("fungible")).getResult();
	}
	
	private static State buildState() {
		Context ctx=TestState.CONTEXT.fork();
		ctx=exec(ctx,"(import convex.fungible :as fungible)");
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(def token (deploy (fungible/build-token {:supply 1000000})))");
		return ctx.getState();
	}
	
	@Test public void testGeneric() {
		Context ctx = context();
		Address token = eval(ctx,"token");
		assertNotNull(token);

		// generic tests
		AssetTester.doFungibleTests(ctx,token,ctx.getAddress());
		
		// Control change
		// TrustTest.testChangeControl(ctx, token);

	}

	@Test public void testAssetAPI() {
		Context ctx = context();
		ctx=exec(ctx,"(def VILLAIN "+VILLAIN+")");
		Address token = eval(ctx,"token");
		assertNotNull(token);


		assertEquals(1000000L,evalL(ctx,"(asset/balance token *address*)"));
		assertEquals(0L,evalL(ctx,"(asset/balance token *registry*)"));

		ctx=exec(ctx,"(asset/offer VILLAIN [token 1000])");
		ctx=exec(ctx,"(asset/transfer "+VILLAIN+" [token 2000])");

		assertEquals(998000L,evalL(ctx,"(asset/balance token *address*)"));
		assertEquals(2000L,evalL(ctx,"(asset/balance token VILLAIN)"));

		assertEquals(0L,evalL(ctx,"(asset/quantity-zero token)"));
		assertEquals(110L,evalL(ctx,"(asset/quantity-add token 100 10)"));
		assertEquals(110L,evalL(ctx,"(asset/quantity-sub token 120 10)"));
		assertEquals(110L,evalL(ctx,"(asset/quantity-sub token 110 nil)"));
		assertEquals(0L,evalL(ctx,"(asset/quantity-sub token 100 1000)"));

		assertTrue(evalB(ctx,"(asset/quantity-contains? [token 110] [token 100])"));
		assertTrue(evalB(ctx,"(asset/quantity-contains? [token 110] nil)"));
		assertTrue(evalB(ctx,"(asset/quantity-contains? token 1000 999)"));
		assertFalse(evalB(ctx,"(asset/quantity-contains? [token 110] [token 300])"));

		assertTrue(evalB(ctx,"(asset/owns? VILLAIN [token 1000])"));
		assertTrue(evalB(ctx,"(asset/owns? VILLAIN [token 2000])"));
		assertFalse(evalB(ctx,"(asset/owns? VILLAIN [token 2001])"));

		// transfer using map argument
		ctx=exec(ctx,"(asset/transfer VILLAIN {token 100})");
		assertTrue(ctx.getResult() instanceof AMap);
		assertTrue(evalB(ctx,"(asset/owns? VILLAIN [token 2100])"));

		// test non-zero offer
		ctx=exec(ctx,"(asset/offer VILLAIN [token 1337])");
		assertEquals(1337L,evalL(ctx,"(asset/get-offer token *address* VILLAIN)"));
		
		// nil should be seen as zero offer
		assertCVMEquals(0,eval(ctx,"(do (asset/offer VILLAIN [token nil]) (asset/get-offer token *address* VILLAIN))"));

		// Keyword quantity is an argument error
		assertArgumentError(step(ctx,"(asset/offer VILLAIN [token :foo])"));
	}

	@Test public void testBuildToken() {
		// check our alias is right
		Context ctx = context();
		assertEquals(fungible,eval(ctx,"fungible"));

		// deploy a token with default config
		ctx=exec(ctx,"(def token (deploy (fungible/build-token {:supply 1000000})))");
		Address token = (Address) ctx.getResult();
		assertTrue(ctx.getAccountStatus(token)!=null);
		ctx=exec(ctx,"(def token (address "+token+"))");

		// GEnric tests
		AssetTester.doFungibleTests(ctx,token,ctx.getAddress());

		// check our balance is positive as initial holder
		long bal=evalL(ctx,"(fungible/balance token *address*)");
		assertTrue(bal>0);

		// transfer to the Villain scenario
		{
			Context tctx=step(ctx,"(fungible/transfer token "+VILLAIN+" 100)");
			assertEquals(bal-100,evalL(tctx,"(fungible/balance token *address*)"));
			assertEquals(100,evalL(tctx,"(fungible/balance token "+VILLAIN+")"));
		}

		// acceptable transfers
		assertNotError(step(ctx,"(fungible/transfer token *address* 0)"));
		assertNotError(step(ctx,"(fungible/transfer token *address* "+bal+")"));

		// bad transfers
		assertArgumentError(step(ctx,"(fungible/transfer token *address* -1)"));
		assertFundsError(step(ctx,"(fungible/transfer token *address* "+(bal+1)+")"));
	}

	@Test public void testMint() {
		// check our alias is right
		Context ctx = context();

		// deploy a token with default config
		ctx=exec(ctx,"(def token (deploy [(fungible/build-token {:supply 100}) (fungible/add-mint {:max-supply 1000})]))");
		Address token = (Address) ctx.getResult();
		assertTrue(ctx.getAccountStatus(token)!=null);
		
		assertEquals(100,evalL(ctx,"(fungible/total-supply token)"));

		// do Generic Tests
		AssetTester.doFungibleTests(ctx,token,ctx.getAddress());

		// check our balance is positive as initial holder
		Long bal=evalL(ctx,"(fungible/balance token *address*)");
		assertEquals(100L,bal);

		// Mint up to max and back down to zero
		{
			Context c=exec(ctx,"(fungible/mint token 900)");
			assertEquals(1000L,evalL(c,"(fungible/balance token *address*)"));

			c=exec(c,"(fungible/mint token -900)");
			assertEquals(bal,evalL(c,"(fungible/balance token *address*)"));

			c=exec(c,"(fungible/mint token -100)");
			assertEquals(0L,evalL(c,"(fungible/balance token *address*)"));
		}

		// Mint up to max and burn down to zero
		{
			Context c=step(ctx,"(fungible/mint token 900)");
			assertEquals(1000L,evalL(c,"(fungible/balance token *address*)"));

			c=exec(c,"(fungible/burn token 900)");
			assertEquals(100L,evalL(c,"(fungible/balance token *address*)"));

			assertFundsError(step(c,"(fungible/burn token 101)")); // Fails, not held

			assertArgumentError(step(c,"(fungible/burn token -1)")); // Fails, can't burn negative
			
			c=exec(c,"(fungible/burn token 100)");
			assertEquals(0L,evalL(c,"(fungible/balance token *address*)"));

			assertFundsError(step(c,"(fungible/burn token 1)")); // Fails, not held
		}


		// Shouldn't be possible to burn tokens in supply but not held
		{
			Context c=exec(ctx,"(fungible/mint token 900)");
			assertEquals(1000L,evalL(c,"(fungible/balance token *address*)"));

			c=exec(c,"(fungible/transfer token "+VILLAIN+" 800)");
			assertEquals(200L,evalL(c,"(fungible/balance token *address*)"));

			assertFundsError(step(c,"(fungible/burn token 201)")); // Fails, not held
			assertNotError(step(c,"(fungible/burn token 200)")); // OK since held
		}

		// Illegal Minting amounts
		{
			assertStateError(step(ctx,"(fungible/mint token 901)")); // too much (exceeds max supply)
			assertStateError(step(ctx,"(fungible/mint token -101)")); // too little
		}

		// Villain shouldn't be able to mint or burn
		{
			Context c=ctx.forkWithAddress(VILLAIN);
			c=exec(c,"(def token "+token+")");
			c=exec(c,"(import convex.fungible :as fungible)");

			assertTrustError(step(c,"(fungible/mint token 100)"));
			assertTrustError(step(c,"(fungible/mint token 10000)")); // trust before amount checks

			assertTrustError(step(c,"(fungible/burn token 100)"));
		}
	}
	
	@Test
	public void testModularToken() {
		Context ctx = context();
		
		ctx=exec(ctx,"(def T1 (deploy ["
				+ "(fungible/build-token {:supply 1000000})"
				+ "(trust/add-controller)"
				+ "]))");
		Address T1=ctx.getResult();
		AssetTester.doFungibleTests(ctx, T1, ctx.getAddress());
		
		assertStateError(step(ctx,"(asset/mint T1 1000)"));
		
		ctx=exec(ctx,"(def T2 (deploy ["
				+ "(fungible/build-token {:supply 1000000})"
				+ "(fungible/add-mint {:max-supply 2000000})"
				+ "(trust/add-controller)"
				+ "]))");
		Address T2=ctx.getResult();
		AssetTester.doFungibleTests(ctx, T2, ctx.getAddress());
		
		assertCVMEquals(1001000,eval(ctx,"(asset/mint T2 1000)"));
		assertStateError(step(ctx,"(asset/mint T2 999999999999)"));

	}


}
