package convex.actors;

import static convex.test.Assertions.assertAssertError;
import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class PredictionMarketTest extends ACVMTest {
	Address addr;
	
	static String CONTRACT_STRING;
	static {
		try {
			CONTRACT_STRING = Utils.readResourceAsString("/convex/lab/prediction-market.cvx");
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	@Override protected Context buildContext(Context ctx) {
		try {
			ctx=exec(ctx,CONTRACT_STRING);
			ctx=exec(ctx,"(deploy (build-prediction-market *address* :bar #{true,false}))");

			addr = (Address) ctx.getResult();
			assertNotNull(addr);
			
			ctx = exec(ctx, "(def caddr " + addr + ")");

			return ctx;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@Test
	public void testPredictionContract() throws IOException {
		Context ctx=context();
		// Run code to initialise actor with [oracle oracle-key outcomes]

		// tests of bonding curve function with empty stakes
		assertEquals(0.0, evalD("(call caddr 0 (bond {}))"), 0.01);

		// bonding curve point with one staked outcome
		assertEquals(10.0, evalD("(call caddr 0 (bond {true 10}))"), 0.01);

		// two staked outcomes
		assertEquals(5.0, evalD("(call caddr 0 (bond {true 3 false 4}))"), 0.01);

		long initalBal=ctx.getBalance(HERO);
		{ // stake on, stake off.....
			// first we stake on the 'true' outcome
			Context rctx1 = step(ctx,"(call caddr 10 (stake true 10))");
			assertCVMEquals(10L, rctx1.getResult());
			assertEquals(10L,  initalBal- rctx1.getBalance(HERO));
			assertEquals(1.0, evalD(rctx1, "(call caddr (price true))")); // should be exact price 100%
			assertEquals(0.0, evalD(rctx1, "(call caddr (price false))")); // should be exact price 0%

			// stake on other outcome. Note that we offer too much funds, but this won't be
			// accepted so no issue.
			Context rctx2 = step(rctx1,"(call caddr 10 (stake false 10))");
			assertCVMEquals(4L, rctx2.getResult());
			assertEquals(TestState.TOTAL_FUNDS, rctx2.getState().computeTotalFunds());

			// halve stakes
			Context rctx3 = step(rctx2,"(call caddr 10 (stake false 5))");
			assertNotError(rctx3);
			rctx3 = step(rctx3,"(call caddr 10 (stake true 5))");
			//assertEquals(7L, initalBal - rctx3.getBalance(HERO));
			assertEquals(0.5, evalD(rctx3, "(call caddr (price true))"), 0.1); // approx price given rounding

			// zero one stake
			Context rctx4 = step(rctx3,"(call caddr 10 (stake false 0))");
			assertCVMEquals(-2L, rctx4.getResult()); // refund of 2
			assertEquals(5L, initalBal - rctx4.getBalance(HERO));

			// Exit market
			Context rctx5 =step(rctx4,"(call caddr 10 (stake true 0))");
			assertCVMEquals(-5L, rctx5.getResult()); // refund of 5
			assertEquals(0L, initalBal - rctx5.getBalance(HERO));
			assertEquals(TestState.TOTAL_FUNDS, rctx2.getState().computeTotalFunds());
		}

		{ // underfunded stake request
			Context rctx1 = step(ctx,"(call caddr 5 (stake true 10))");
			assertStateError(rctx1); // TODO: what is right error type?
			assertEquals(0L, initalBal - rctx1.getBalance(HERO));
		}

		{ // negative stake request
			Context rctx1 = step(ctx,"(call caddr 5 (stake true -10))");
			assertAssertError(rctx1); // TODO: what is right error type?
			assertEquals(0L, initalBal - rctx1.getBalance(HERO));
		}
	}

	@Test
	public void testPayouts() throws IOException {
		Context ctx=context();
		
		// setup address for this little play
		ctx = exec(ctx,"(do (def HERO " + HERO + ") (def VILLAIN " +VILLAIN + ") )");
		ctx = exec(ctx,"(import convex.oracle :as oaddr)");

		// call to create oracle with key :bar and current address (HERO) trusted
		ctx = exec(ctx, "(oaddr/register :bar {:trust #{*address*}})");

		// deploy a prediction market using the oracle
		ctx=exec(ctx,"(deploy ("+CONTRACT_STRING+" oaddr :bar #{true,false}))");
		Address pmaddr = (Address) ctx.getResult();
		ctx = exec(ctx, "(def pmaddr " + pmaddr + ")");
		ctx = stepAs(VILLAIN, ctx, "(def pmaddr "+pmaddr+")");

		// initial state checks
		assertEquals(false,evalB(ctx, "(call pmaddr (finalized?))"));
		assertEquals(0L, evalL(ctx, "(balance pmaddr)"));

		{ // Act 1. Two players stake. our Villain wins this time....
			Context c = ctx;
			c = exec(c, "(call pmaddr 5000 (stake true 4000))");
			c = stepAs(VILLAIN, c, "(call pmaddr 5000 (stake false 3000))");
			assertEquals(5000L, c.getBalance(pmaddr));

			assertFalse(evalB(c, "(call pmaddr (finalized?))"));
			assertEquals(0.64, evalD(c, "(call pmaddr (price true))"), 0.0001); // 64% chance on true. Looks a good bet
			assertNull(eval(c, "(call pmaddr (payout))"));

			// But alas, our hero is thwarted...
			c = exec(c, "(oaddr/provide :bar false)");
			assertCVMEquals(Boolean.FALSE, c.getResult());

			// collect payouts
			c = exec(c, "(call pmaddr (payout))");
			assertCVMEquals(0L, c.getResult());
			assertEquals(HERO_BALANCE - 4000, c.getBalance(HERO));

			c = stepAs(VILLAIN, c, "(call pmaddr (payout))");
			assertCVMEquals(5000L, c.getResult());
			assertEquals(VILLAIN_BALANCE + 4000, c.getBalance(VILLAIN));

			assertEquals(0L, c.getBalance(pmaddr));
			assertEquals(TestState.TOTAL_FUNDS, c.getState().computeTotalFunds());
		}
	}

}
