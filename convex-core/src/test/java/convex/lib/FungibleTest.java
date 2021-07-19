package convex.lib;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.evalB;
import static convex.core.lang.TestState.evalL;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertAssertError;
import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AMap;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.init.InitTest;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class FungibleTest {
	private static final Symbol fSym=Symbol.create("fun-actor");

	static final AKeyPair TEST_KEYPAIR=AKeyPair.generate();
	
	private static final Address VILLAIN=InitTest.VILLAIN;


	private static Context<?> loadFungible() {
		Context<?> ctx=TestState.CONTEXT.fork();
		assert(ctx.getDepth()==0):"Invalid depth: "+ctx.getDepth();
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/fungible.con")));
			Address fun=(Address) ctx.getResult();
			String importS="(import "+fun+" :as fungible)";
			ctx=step(ctx,importS);
			assertNotError(ctx);

			ctx=step(ctx,"(import convex.asset :as asset)");
			assertFalse(ctx.isExceptional());

			ctx=ctx.define(fSym, fun);
		} catch (Throwable e) {
			e.printStackTrace();
			throw new Error(e);
		}

		return ctx;
	}

	private static final Context<?> ctx;
	private static final Address fungible;

	static {
		ctx=loadFungible();
		fungible=(Address) ctx.lookupValue(fSym);
	}

	/**
	 * Test that re-deployment of Fungible matches what is expected
	 */
	@Test public void testLibraryProperties() {
		assertTrue(ctx.getAccountStatus(fungible).isActor());

		assertEquals("Fungible Library",eval(ctx,"(:name (call *registry* (lookup "+fungible+")))").toString());
	}

	@Test public void testAssetAPI() {
		Context<?> ctx=FungibleTest.ctx.fork();
		ctx=step(ctx,"(def token (deploy (fungible/build-token {:supply 1000000})))");
		Address token = (Address) ctx.getResult();
		assertNotNull(token);

		// generic tests
		doFungibleTests(ctx,token,ctx.getAddress());

		assertEquals(1000000L,evalL(ctx,"(asset/balance token *address*)"));
		assertEquals(0L,evalL(ctx,"(asset/balance token *registry*)"));

		ctx=step(ctx,"(asset/offer "+VILLAIN+" [token 1000])");
		assertNotError(ctx);

		ctx=step(ctx,"(asset/transfer "+VILLAIN+" [token 2000])");
		assertNotError(ctx);

		assertEquals(998000L,evalL(ctx,"(asset/balance token *address*)"));
		assertEquals(2000L,evalL(ctx,"(asset/balance token "+VILLAIN+")"));

		assertEquals(0L,evalL(ctx,"(asset/quantity-zero token)"));
		assertEquals(110L,evalL(ctx,"(asset/quantity-add token 100 10)"));
		assertEquals(110L,evalL(ctx,"(asset/quantity-sub token 120 10)"));
		assertEquals(110L,evalL(ctx,"(asset/quantity-sub token 110 nil)"));
		assertEquals(0L,evalL(ctx,"(asset/quantity-sub token 100 1000)"));

		assertTrue(evalB(ctx,"(asset/quantity-contains? [token 110] [token 100])"));
		assertTrue(evalB(ctx,"(asset/quantity-contains? [token 110] nil)"));
		assertTrue(evalB(ctx,"(asset/quantity-contains? token 1000 999)"));
		assertFalse(evalB(ctx,"(asset/quantity-contains? [token 110] [token 300])"));



		assertTrue(evalB(ctx,"(asset/owns? "+VILLAIN+" [token 1000])"));
		assertTrue(evalB(ctx,"(asset/owns? "+VILLAIN+" [token 2000])"));
		assertFalse(evalB(ctx,"(asset/owns? "+VILLAIN+" [token 2001])"));

		// transfer using map argument
		ctx=step(ctx,"(asset/transfer "+VILLAIN+" {token 100})");
		assertTrue(ctx.getResult() instanceof AMap);
		assertTrue(evalB(ctx,"(asset/owns? "+VILLAIN+" [token 2100])"));

		// test offer
		ctx=step(ctx,"(asset/offer "+VILLAIN+" [token 1337])");
		assertEquals(1337L,evalL(ctx,"(asset/get-offer token *address* "+VILLAIN+")"));
	}

	@Test public void testBuildToken() {
		// check our alias is right
		Context<?> ctx=FungibleTest.ctx.fork();
		assertEquals(fungible,eval(ctx,"fungible"));

		// deploy a token with default config
		ctx=step(ctx,"(def token (deploy (fungible/build-token {})))");
		Address token = (Address) ctx.getResult();
		assertTrue(ctx.getAccountStatus(token)!=null);
		ctx=step(ctx,"(def token (address "+token+"))");

		// GEnric tests
		doFungibleTests(ctx,token,ctx.getAddress());

		// check our balance is positive as initial holder
		long bal=evalL(ctx,"(fungible/balance token *address*)");
		assertTrue(bal>0);

		// transfer to the Villain scenario
		{
			Context<?> tctx=step(ctx,"(fungible/transfer token "+VILLAIN+" 100)");
			assertEquals(bal-100,evalL(tctx,"(fungible/balance token *address*)"));
			assertEquals(100,evalL(tctx,"(fungible/balance token "+VILLAIN+")"));
		}

		// acceptable transfers
		assertNotError(step(ctx,"(fungible/transfer token *address* 0)"));
		assertNotError(step(ctx,"(fungible/transfer token *address* "+bal+")"));

		// bad transfers
		assertAssertError(step(ctx,"(fungible/transfer token *address* -1)"));
		assertAssertError(step(ctx,"(fungible/transfer token *address* "+(bal+1)+")"));
	}

	@Test public void testMint() {
		// check our alias is right
		Context<?> ctx=FungibleTest.ctx.fork();

		// deploy a token with default config
		ctx=step(ctx,"(def token (deploy [(fungible/build-token {:supply 100}) (fungible/add-mint {:max-supply 1000})]))");
		Address token = (Address) ctx.getResult();
		assertTrue(ctx.getAccountStatus(token)!=null);

		// do Generic Tests
		doFungibleTests(ctx,token,ctx.getAddress());

		// check our balance is positive as initial holder
		Long bal=evalL(ctx,"(fungible/balance token *address*)");
		assertEquals(100L,bal);

		// Mint up to max and back down to zero
		{
			Context<?> c=step(ctx,"(fungible/mint token 900)");
			assertEquals(1000L,evalL(c,"(fungible/balance token *address*)"));

			c=step(c,"(fungible/mint token -900)");
			assertEquals(bal,evalL(c,"(fungible/balance token *address*)"));

			c=step(c,"(fungible/mint token -100)");
			assertEquals(0L,evalL(c,"(fungible/balance token *address*)"));
		}

		// Mint up to max and burn down to zero
		{
			Context<?> c=step(ctx,"(fungible/mint token 900)");
			assertEquals(1000L,evalL(c,"(fungible/balance token *address*)"));

			c=step(c,"(fungible/burn token 900)");
			assertEquals(100L,evalL(c,"(fungible/balance token *address*)"));

			assertAssertError(step(c,"(fungible/burn token 101)")); // Fails, not held

			c=step(c,"(fungible/burn token 100)");
			assertEquals(0L,evalL(c,"(fungible/balance token *address*)"));

			assertAssertError(step(c,"(fungible/burn token 1)")); // Fails, not held
		}


		// Shouldn't be possible to burn tokens in supply but not held
		{
			Context<?> c=step(ctx,"(fungible/mint token 900)");
			assertEquals(1000L,evalL(c,"(fungible/balance token *address*)"));

			c=step(c,"(fungible/transfer token "+VILLAIN+" 800)");
			assertEquals(200L,evalL(c,"(fungible/balance token *address*)"));

			assertAssertError(step(c,"(fungible/burn token 201)")); // Fails, not held
			assertNotError(step(c,"(fungible/burn token 200)")); // OK since held
		}

		// Illegal Minting amounts
		{
			assertError(step(ctx,"(fungible/mint token 901)")); // too much (exceeds max supply)
			assertError(step(ctx,"(fungible/mint token -101)")); // too little
		}

		// Villain shouldn't be able to mint or burn
		{
			Context<?> c=ctx.forkWithAddress(VILLAIN);
			c=step(c,"(def token "+token+")");
			c=step(c,"(import convex.fungible :as fungible)");

			assertTrustError(step(c,"(fungible/mint token 100)"));
			assertTrustError(step(c,"(fungible/mint token 10000)")); // trust before amount checks

			assertTrustError(step(c,"(fungible/burn token 100)"));
		}
	}

	/**
	 * Generic tests for a fungible token. User account should have some of fungible token and sufficient coins.
	 * @param ctx Initial Context. Will be forked.
	 * @param token Fungible token Address
	 * @param user User Address
	 */
	public static void doFungibleTests (Context<?> ctx, Address token, Address user) {
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

		// Run generic asset tests, giving 1/3 the balance to a new user account
		{
			Context<?> c=ctx.fork();
			c=c.createAccount(TEST_KEYPAIR.getAccountKey());
			Address user2=(Address) c.getResult();
			Long smallBal=BAL/3;
			c=step(c,"(asset/transfer "+user2+" [token "+smallBal+"])");

			AssetTest.doAssetTests(c, token, user, user2);
		}
	}
}
