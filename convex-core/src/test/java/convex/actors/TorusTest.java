package convex.actors;

import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.prim.CVMDouble;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lib.AssetTester;

public class TorusTest extends ACVMTest {
	Context<?> INITIAL=context();

	protected TorusTest() {
		super(InitTest.STATE);

		try {
			Context<?> ctx=INITIAL;
			ctx=step(ctx,"(import convex.fungible :as fun)");

			ctx=step(ctx,"(import convex.asset :as asset)");

			// Deploy currencies for testing (10m each, 2 decimal places)
			ctx=step(ctx,"(def USD (deploy (fun/build-token {:supply 1000000000})))");
			USD=(Address) ctx.getResult();
			//System.out.println("USD deployed Address = "+USD);
			ctx=step(ctx,"(def GBP (deploy (fun/build-token {:supply 1000000000})))");
			GBP=(Address) ctx.getResult();

			// Deploy Torus actor itself
			ctx=ctx.withJuice(INITIAL_JUICE);

			ctx= step(ctx,"(def TORUS (import torus.exchange :as torus))");
			TORUS=(Address)ctx.getResult();
			assertNotNull(ctx.getAccountStatus(TORUS));
			//System.out.println("Torus deployed Address = "+TORUS);

			// Deploy USD market. No market for GBP yet!
			ctx= step(ctx,"(call TORUS (create-market USD))");
			USD_MARKET=(Address)ctx.getResult();
			INITIAL= ctx.withResult(TORUS).withJuice(INITIAL_JUICE);
		} catch (Throwable e) {
			e.printStackTrace();
			throw Utils.sneakyThrow(e);
		}
	}

	Address USD = null;
	Address GBP = null;
	Address TORUS = null;
	Address USD_MARKET = null;

	static {

	}

	@Test public void testMissingMarket() {
		Context<?> ctx=INITIAL.fork();

		assertNull(eval(ctx,"(torus/get-market GBP)"));

		// price should be null for missing markets, regardless of whether or not a token exists
		assertNull(eval(ctx,"(torus/price GBP)"));
		assertNull(eval(ctx,"(torus/price #789798789)"));

	}

	@Test public void testDeployedCurrencies() {
		Context<?> ctx=INITIAL.fork(); // Initial test context
		ctx=step(ctx,"(import torus.exchange :as torus)");
		ctx= step(ctx,"(def GBP (import currency.GBP :as GBP))");
		ctx= step(ctx,"(def USD (import currency.USD :as USD))");
		assertNotNull(ctx.getResult());
		ctx= step(ctx,"(torus/price GBP USD)");
		assertTrue(ctx.getResult() instanceof CVMDouble);

		ctx= step(ctx,"(torus/price GBP USD)");

	}
	
	@Test public void testMultiTokenListing() {
		Context<?> ctx=INITIAL.fork();
		String importS="(import asset.multi-token :as mt)";
		ctx=step(ctx,importS);
		assertNotError(ctx);
		
		ctx=step(ctx,"(def ECO [mt (call mt (create :ECO))])");
		assertTrue(ctx.getResult() instanceof AVector);

		ctx= step(ctx,"(def ECOM (call TORUS (create-market ECO)))");
		assertTrue(ctx.getResult() instanceof Address);

		assertNull(eval(ctx,"(torus/price ECO)"));
		
		ctx=step(ctx,"(call ECO (mint 1000000))");
		
		// TODO: multi-token needs offer and accept for this
		//ctx=step(ctx,"(torus/add-liquidity ECO 1000 10000)");
		//assertNotError(ctx);
		//assertEquals(10.0,evalD(ctx,"(torus/price ECO)"));

	}


	@Test public void testTorusAPI() {
		Context<?> ctx=INITIAL.fork();

		// Deploy GBP market.
		ctx= step(ctx,"(def GBPM (call TORUS (create-market GBP)))");
		Address GBP_MARKET=(Address)ctx.getResult();
		assertNotNull(GBP_MARKET);

		// Check we can access the USD market
		ctx= step(ctx,"(def USDM (torus/get-market USD))");
		assertEquals(USD_MARKET,ctx.getResult());

		// Prices should be null with no markets
		assertNull(eval(ctx,"(torus/price USD)"));
		assertNull(eval(ctx,"(torus/price GBP)"));

		// ============================================================
		// FIRST TEST: Initial deposit of $100k USD liquidity, £50k GBP liquidity
		// Deposit some liquidity $100,000 for 1000 Gold = $100 price = 100000 CVX / US Cent
		ctx=step(ctx,"(torus/add-liquidity USD 10000000 1000000000000)");
		assertEquals(1.0,Math.sqrt(10000000.0*1000000000000.0)/(long)RT.jvm(ctx.getResult()),0.00001);
		ctx=step(ctx,"(torus/add-liquidity GBP  5000000 1000000000000)");
		assertEquals(1.0,Math.sqrt(5000000.0*1000000000000.0)/(long)RT.jvm(ctx.getResult()),0.00001);

		// ============================================================
		// SECOND TEST: Check prices
		assertEquals(100000.0,evalD(ctx,"(torus/price USD)"));
		assertEquals(200000.0,evalD(ctx,"(torus/price GBP)"));

		assertEquals(1.0,evalD(ctx,"(torus/price GBP GBP)"));
		assertEquals(0.5,evalD(ctx,"(torus/price USD GBP)"));
		assertEquals(2.0,evalD(ctx,"(torus/price GBP USD)"));

		// ============================================================
		// THIRD TEST: Check marginal buy trades for $1 / £1
		assertEquals(101,evalL(ctx,"(torus/buy GBP 100 GBP)"));
		assertEquals(101,evalL(ctx,"(torus/buy-quote GBP 100 GBP)"));
		assertEquals(51,evalL(ctx,"(torus/buy-quote USD 100 GBP)"));
		assertEquals(51,evalL(ctx,"(torus/buy USD 100 GBP)"));
		assertEquals(201,evalL(ctx,"(torus/buy GBP 100 USD)"));
		assertEquals(201,evalL(ctx,"(torus/buy-quote GBP 100 USD)"));

		// ============================================================
		// FOURTH TEST: Check marginal sell trades for $1 / £1
		assertEquals(99,evalL(ctx,"(torus/sell GBP 100 GBP)"));
		assertEquals(99,evalL(ctx,"(torus/sell-quote GBP 100 GBP)"));
		assertEquals(49,evalL(ctx,"(torus/sell USD 100 GBP)"));
		assertEquals(49,evalL(ctx,"(torus/sell-quote USD 100 GBP)"));
		assertEquals(199,evalL(ctx,"(torus/sell GBP 100 USD)"));
		assertEquals(199,evalL(ctx,"(torus/sell-quote GBP 100 USD)"));

		// Trades too big
		assertError(step(ctx,"(torus/buy USD "+Long.MAX_VALUE+")"));
		assertError(step(ctx,"(torus/buy USD 10000000)"));

		// Too expensive for pool
		assertError(step(ctx,"(torus/buy USD 9999999)"));
	}

	@Test public void testInitialTokenMarket() {
		Context<?> ctx=INITIAL.fork();

		// Check we can access the USD market
		ctx= step(ctx,"(def USDM (torus/get-market USD))");
		assertEquals(USD_MARKET,ctx.getResult());

		// should be no price for initial market with zero liquidity
		assertNull(eval(ctx,"(call USDM (price))"));

		// Offer tokens to market ($200k)
		ctx= step(ctx,"(asset/offer USDM [USD 20000000])");
		assertEquals(20000000L,evalL(ctx,"(asset/get-offer USD *address* USDM)"));

		// ============================================================
		// FIRST TEST: Initial deposit of $100k USD liquidity
		// Deposit some liquidity $100,000 for 1000 Gold = $100 price = 100000 CVX / US Cent
		ctx= step(ctx,"(call USDM 1000000000000 (add-liquidity 10000000))");
		final long INITIAL_SHARES=RT.jvm(ctx.getResult());

		assertEquals(10000000L,evalL(ctx,"(asset/balance USD USDM)"));
		assertEquals(1000000000000L,evalL(ctx,"(balance USDM)"));
		assertEquals(INITIAL_SHARES,evalL(ctx,"(asset/balance USDM *address*)")); // Initial pool shares, accessible as a fungible asset balance

		// Should have consumed half the full offer of tokens
		assertEquals(10000000L,evalL(ctx,"(asset/get-offer USD *address* USDM)"));

		// price should be 100000 CVX / US Cent
		assertEquals(100000.0,evalD(ctx,"(call USDM (price))"));

		// ============================================================
		// SECOND TEST: Initial deposit of $100k USD liquidity
		// Deposit more liquidity $100,000 for 1000 Gold - previous token offer should cover this
		ctx= step(ctx,"(call USDM 1000000000000 (add-liquidity 10000000))");
		final long NEW_SHARES=RT.jvm(ctx.getResult());
		assertEquals(20000000L,evalL(ctx,"(asset/balance USD USDM)"));

		// Check new pool shares, accessible as a fungible asset balance
		assertEquals(INITIAL_SHARES+NEW_SHARES,evalL(ctx,"(asset/balance USDM *address*)"));

		// Price should be unchanged
		assertEquals(100000.0,evalD(ctx,"(call USDM (price))"));

		// should have consumed remaining token offer
		assertEquals(0L,evalL(ctx,"(asset/get-offer USD *address* USDM)"));

		// ============================================================
		// THIRD TEST - withdraw half of liquidity
		long balanceBeforeWithdrawal=ctx.getBalance();
		ctx= step(ctx,"(call USDM (withdraw-liquidity "+NEW_SHARES+"))");
		assertEquals(RT.cvm(NEW_SHARES),ctx.getResult());

		assertEquals(INITIAL_SHARES,evalL(ctx,"(asset/balance USDM *address*)"));
		assertEquals(10000000L,evalL(ctx,"(asset/balance USD USDM)"));
		assertEquals(990000000L,evalL(ctx,"(asset/balance USD *address*)"));
		assertTrue(ctx.getBalance()>balanceBeforeWithdrawal);
		assertEquals(1000000000000L,evalL(ctx,"(balance USDM)")); // Convex balance back to start

		// Generic fungible test on shares
		AssetTester.doFungibleTests(ctx,USD_MARKET,ctx.getAddress());

		// ============================================================
		// FORTH TEST - buy half of all tokens ($50k)
		ctx= step(ctx,"(call USDM *balance* (buy-tokens 5000000))");
		long paidConvex=RT.jvm(ctx.getResult());
		assertTrue(paidConvex>1000000000000L); // should cost more than pool Convex balance after fee
		assertTrue(paidConvex<1100000000000L,"Paid:" +paidConvex); // but less than 10% fee
		assertEquals(5000000L,evalL(ctx,"(asset/balance USD USDM)"));
		assertEquals(995000000L,evalL(ctx,"(asset/balance USD *address*)"));
		assertEquals(INITIAL_SHARES,evalL(ctx,"(asset/balance USDM *address*)"));

		// ============================================================
		// FIFTH TEST - sell back tokens ($50k)
		ctx= step(ctx,"(asset/offer USDM [USD 5000000])");
		ctx= step(ctx,"(call USDM (sell-tokens 5000000))");
		long gainedConvex=RT.jvm(ctx.getResult());
		assertTrue(gainedConvex>900000000000L); // should gain most of money back
		assertTrue(gainedConvex<paidConvex,"Gain:" +gainedConvex); // but less than cost, since we have fees
		assertEquals( 10000000L,evalL(ctx,"(asset/balance USD USDM)"));
		assertEquals(990000000L,evalL(ctx,"(asset/balance USD *address*)"));
		assertEquals(INITIAL_SHARES,evalL(ctx,"(asset/balance USDM *address*)"));

		// ============================================================
		// FINAL TEST - Withdraw all liquidity
		long shares=evalL(ctx,"(asset/balance USDM *address*)");
		assertTrue(shares>0);
		ctx=step(ctx,"(torus/withdraw-liquidity USD "+shares+")");
		assertNotError(ctx);
		assertEquals(0L,evalL(ctx,"(asset/balance USDM *address*)")); // should have no shares left
		assertEquals(0L,evalL(ctx,"(asset/balance USD USDM)")); // should be no USD left in liquidity pool
		assertEquals(0L,evalL(ctx,"(balance USDM)")); // should be no CVX left in liquidity pool
	}

	@Test public void testSetup() {
		assertNotNull(TORUS);
		assertNotNull(USD);
		assertNotNull(GBP);
		assertNotNull(USD_MARKET);
	}

}
