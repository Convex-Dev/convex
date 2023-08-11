package convex.lib;

import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.data.AMap;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;

public class WrappedCVXTest extends ACVMTest {

	private Address VILLAIN=InitTest.VILLAIN;

	protected Address fungible;
	protected Address token;

	protected WrappedCVXTest() {
		super(buildState());
		fungible = (Address) context().lookup(Symbol.create("fungible")).getResult();
		token = (Address) context().lookup(Symbol.create("token")).getResult();
	}
	
	private static State buildState() {
		Context ctx=TestState.CONTEXT.fork();
		String importS="(import convex.fungible :as fungible)";
		ctx=step(ctx,importS);
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.asset :as asset)");
		assertNotError(ctx);
		
		ctx=step(ctx,"(import asset.wrap.convex :as wcvx)");
		assertNotError(ctx);

		ctx=step(ctx,"(def token wcvx)");
		
		return ctx.getState();
	}

	@Test public void testAssetAPI() {
		Context ctx = context();
		ctx=step(ctx,"(wcvx/wrap 1000000)");
		assertNotError(ctx);

		// generic tests
		AssetTester.doFungibleTests(ctx,token,ctx.getAddress());

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

	@Test public void testMint() {
		Context ctx = context();
		long BAL= evalL(ctx,"*balance*");

		ctx=step(ctx,"(wcvx/wrap 1000000)");
		assertNotError(ctx);
		
		assertEquals(1000000,evalL(ctx,"(asset/balance wcvx *address*)"));
		long NBAL= evalL(ctx,"*balance*");
		assertEquals(BAL-1000000,NBAL);
		
		ctx=step(ctx,"(wcvx/unwrap 50000)");
		assertNotError(ctx);
		assertEquals(950000,evalL(ctx,"(fungible/balance wcvx *address*)"));
		
		assertEquals(NBAL+50000,evalL(ctx,"*balance*"));

		// do Generic Tests
		AssetTester.doFungibleTests(ctx,token,ctx.getAddress());
	}


}
