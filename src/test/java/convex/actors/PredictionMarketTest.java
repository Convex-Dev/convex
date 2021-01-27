package convex.actors;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.evalB;
import static convex.core.lang.TestState.evalD;
import static convex.core.lang.TestState.evalL;
import static convex.core.lang.TestState.step;
import static convex.core.lang.TestState.stepAs;
import static convex.test.Assertions.assertAssertError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.data.Address;
import convex.core.data.Maps;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class PredictionMarketTest {

	@Test
	public void testPredictionContract() throws IOException {
		String contractString = Utils.readResourceAsString("actors/prediction-market.con");
			
		// Run code to initialise actor with [oracle oracle-key outcomes]
		Context<?> ctx = TestState.INITIAL_CONTEXT.fork();
		ctx=step("(deploy ("+contractString+" *address* :bar #{true,false}))");
		
		Address addr = (Address) ctx.getResult();
		assertNotNull(addr);
		ctx = step(ctx, "(def caddr (address 0x" + addr.toHexString() + "))");
		assertFalse(ctx.isExceptional());

		// tests of bonding curve function with empty stakes
		assertEquals(0.0, (double) ctx.actorCall(addr, 0, "bond", Maps.empty()).getResult(), 0.01);

		// bonding curve point with one staked outcome
		assertEquals(10.0, (double) ctx.actorCall(addr, 0, "bond", Maps.of(true, 10)).getResult(), 0.01);

		// two staked outcomes
		assertEquals(5.0, (double) ctx.actorCall(addr, 0, "bond", Maps.of(true, 3, false, 4)).getResult(), 0.01);

		long initalBal=ctx.getBalance(Init.HERO);
		{ // stake on, stake off.....
			// first we stake on the 'true' outcome
			Context<?> rctx1 = ctx.actorCall(addr, 10, "stake", true, 10);
			assertEquals(10L, (long) rctx1.getResult());
			assertEquals(10L,  initalBal- rctx1.getBalance(Init.HERO));
			assertEquals(1.0, evalD(rctx1, "(call caddr (price true))")); // should be exact price 100%
			assertEquals(0.0, evalD(rctx1, "(call caddr (price false))")); // should be exact price 0%

			// stake on other outcome. Note that we offer too much funds, but this won't be
			// accepted so no issue.
			Context<?> rctx2 = rctx1.actorCall(addr, 10, "stake", false, 10);
			assertEquals(4L, (long) rctx2.getResult());
			assertEquals(14L, initalBal - rctx2.getBalance(Init.HERO));
			assertEquals(TestState.TOTAL_FUNDS, rctx2.getState().computeTotalFunds());

			// halve stakes
			Context<?> rctx3 = rctx2.actorCall(addr, 10, "stake", false, 5);
			rctx3 = rctx3.actorCall(addr, 10, "stake", true, 5);
			assertEquals(7L, initalBal - rctx3.getBalance(Init.HERO));
			assertEquals(0.5, evalD(rctx3, "(call caddr (price true))"), 0.1); // approx price given rounding

			// zero one stake
			Context<?> rctx4 = rctx3.actorCall(addr, 10, "stake", false, 0);
			assertEquals(-2L, (long) rctx4.getResult()); // refund of 2
			assertEquals(5L, initalBal - rctx4.getBalance(Init.HERO));

			// Exit market
			Context<?> rctx5 = rctx4.actorCall(addr, 10, "stake", true, 0);
			assertEquals(-5L, (long) rctx5.getResult()); // refund of 5
			assertEquals(0L, initalBal - rctx5.getBalance(Init.HERO));
			assertEquals(TestState.TOTAL_FUNDS, rctx2.getState().computeTotalFunds());
		}

		{ // underfunded stake request
			Context<?> rctx1 = ctx.actorCall(addr, 5, "stake", true, 10);
			assertStateError(rctx1); // TODO: what is right error type?
			assertEquals(0L, initalBal - rctx1.getBalance(Init.HERO));
		}

		{ // negative stake request
			Context<?> rctx1 = ctx.actorCall(addr, 5, "stake", true, -10);
			assertAssertError(rctx1); // TODO: what is right error type?
			assertEquals(0L, initalBal - rctx1.getBalance(Init.HERO));
		}
	}

	@Test
	public void testPayouts() throws IOException {
		// setup address for this little play
		String VILLAIN = TestState.VILLAIN.toHexString();
		String HERO = TestState.HERO.toHexString();
		Context<?> ctx = step("(do (def HERO (address 0x" + HERO + ")) (def VILLAIN (address 0x" + VILLAIN + ")))");

		// deploy an oracle contract.
		String oracleString = Utils.readResourceAsString("actors/oracle-trusted.con");
		ctx=step("(def oaddr (deploy '"+oracleString+"))");
		Address oaddr=(Address) ctx.getResult();
		
		ctx = step(ctx, "(def oaddr 0x" + oaddr.toHexString() + ")");
		
		// call to create oracle with key :bar and current address (HERO) trusted
		ctx = step(ctx, "(call oaddr (register :bar {:trust #{*address*}}))");

		// deploy a prediction market using the oracle
		String contractString = Utils.readResourceAsString("actors/prediction-market.con");
		ctx=step(ctx,"(deploy ("+contractString+" oaddr :bar #{true,false}))");
		Address pmaddr = (Address) ctx.getResult();
		ctx = step(ctx, "(def pmaddr " + pmaddr.toString() + ")");
		ctx = stepAs(VILLAIN, ctx, "(def pmaddr "+pmaddr.toString()+")");

		// initial state checks
		assertEquals(false,eval(ctx, "(call pmaddr (finalised?))"));
		assertEquals(0L, evalL(ctx, "(balance pmaddr)"));

		{ // Act 1. Two players stake. our Villain wins this time....
			Context<?> c = ctx;
			c = step(c, "(call pmaddr 5000 (stake true 4000))");
			c = stepAs(VILLAIN, c, "(call pmaddr 5000 (stake false 3000))");
			assertEquals(5000L, c.getBalance(pmaddr));

			assertFalse(evalB(c, "(call pmaddr (finalised?))"));
			assertEquals(0.64, evalD(c, "(call pmaddr (price true))"), 0.0001); // 64% chance on true. Looks a good bet
			assertNull(eval(c, "(call pmaddr (payout))"));

			// But alas, our hero is thwarted...
			c = step(c, "(call oaddr (provide :bar false))");
			assertEquals(Boolean.FALSE, c.getResult());

			// collect payouts
			c = step(c, "(call pmaddr (payout))");
			assertEquals(0L, c.getResult());
			assertEquals(TestState.HERO_BALANCE - 4000, c.getBalance(TestState.HERO));

			c = stepAs(VILLAIN, c, "(call pmaddr (payout))");
			assertEquals(5000L, c.getResult());
			assertEquals(TestState.HERO_BALANCE + 4000, c.getBalance(TestState.VILLAIN));

			assertEquals(0L, c.getBalance(pmaddr));
			assertEquals(TestState.TOTAL_FUNDS, c.getState().computeTotalFunds());
		}
	}

}
