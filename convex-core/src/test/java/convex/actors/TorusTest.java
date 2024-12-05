package convex.actors;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.AVector;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.RT;
import convex.lib.AssetTester;

public class TorusTest extends ACVMTest {

	Address USD;
	Address GBP;
	Address TORUS;
	Address USD_MARKET;
	
	private static final long SUPPLY=1000000000;
	
	@Override public Context buildContext(Context ctx) {
		ctx=exec(ctx,"(import convex.fungible :as fun)");
		ctx=exec(ctx,"(import convex.asset :as asset)");

		// Deploy currencies for testing (10m each, 2 decimal places)
		ctx=exec(ctx,"(def USD (deploy (fun/build-token {:supply "+SUPPLY+"})))");
		USD=(Address) ctx.getResult();
		//System.out.println("USD deployed Address = "+USD);
		ctx=exec(ctx,"(def GBP (deploy (fun/build-token {:supply "+SUPPLY+"})))");
		GBP=(Address) ctx.getResult();

		// Deploy Torus actor itself
		ctx= exec(ctx,"(def TORUS (import torus.exchange :as torus))");
		TORUS=(Address)ctx.getResult();
		assertNotNull(ctx.getAccountStatus(TORUS));
		//System.out.println("Torus deployed Address = "+TORUS);

		// Deploy USD market. NOTE: No market for GBP yet!
		ctx= exec(ctx,"(def USDM (call TORUS (create-market USD)))");
		USD_MARKET=(Address)ctx.getResult();
		
		return ctx;
	}
	
	// Basic tests for a range of USD trades
	@Test public void testUSDTrades() {
		long STK=1000000;
		Context baseContext=context();
		baseContext=exec(baseContext,"(torus/add-liquidity USD "+STK+" "+STK+")");
		long USDBAL = evalL(baseContext,"(fun/balance USD)");
		assertEquals(USDBAL,SUPPLY-STK);
		
		{ // Buy 100 USD
			Context ctx=baseContext;
			ctx=exec(ctx,"(torus/buy-tokens USD 100)");
			assertEquals(101L,RT.ensureLong(ctx.getResult()).longValue()); ;; // price should be 101
			assertEquals(USDBAL+100,evalL(ctx,"(fun/balance USD)")); // should have gained 100 USD
			assertEquals(STK,evalL(ctx,"(fun/balance USDM)")); // market shares unchanged
			assertTrue(evalB(ctx,"(< 1.0 (torus/price USD))")); // price has increased
		}
		
		{ // Buy whole pool
			Context ctx=baseContext;
			ctx=step(ctx,"(torus/buy-tokens USD "+STK+")");
			assertEquals(ErrorCodes.LIQUIDITY,ctx.getErrorCode());
		}
		
		{ // Buy 0 USD
			Context ctx=baseContext;
			ctx=exec(ctx,"(torus/buy-tokens USD 0)");
			assertEquals(0L,RT.ensureLong(ctx.getResult()).longValue()); ;; // price should be 0
			assertEquals(USDBAL,evalL(ctx,"(fun/balance USD)")); // no balance change
			assertEquals(STK,evalL(ctx,"(fun/balance USDM)")); // market shares unchanged
			assertTrue(evalB(ctx,"(= 1.0 (torus/price USD))")); // price should be unchanged
		}
		
		{ // Buy -100 USD
			Context ctx=baseContext;
			ctx=step(ctx,"(torus/buy-tokens USD -100)");
			assertArgumentError(ctx);
		}
		
		{ // Sell 100 USD
			Context ctx=baseContext;
			ctx=exec(ctx,"(torus/sell-tokens USD 100)");
			assertEquals(99L,RT.ensureLong(ctx.getResult()).longValue()); ;; // price should be 99
			assertEquals(USDBAL-100,evalL(ctx,"(fun/balance USD)")); // should have gained 100 USD
			assertEquals(STK,evalL(ctx,"(fun/balance USDM)")); // market shares unchanged
			assertTrue(evalB(ctx,"(> 1.0 (torus/price USD))")); // price has decreased
		}
		
		{ // Sell whole USD holding
			Context ctx=baseContext;
			ctx=step(ctx,"(torus/sell-tokens USD (fun/balance USD))");
			assertEquals(0,evalL(ctx,"(fun/balance USD)")); // should have no USD left
		}
		
		{ // Sell 0 USD
			Context ctx=baseContext;
			ctx=exec(ctx,"(torus/sell-tokens USD 0)");
			assertEquals(0L,RT.ensureLong(ctx.getResult()).longValue()); ;; // price should be 0
			assertEquals(USDBAL,evalL(ctx,"(fun/balance USD)"));  // no balance change
			assertEquals(STK,evalL(ctx,"(fun/balance USDM)")); // market shares unchanged
			assertTrue(evalB(ctx,"(= 1.0 (torus/price USD))")); // price should be unchanged
		}
		
		{ // Sell -100 USD
			Context ctx=baseContext;
			ctx=step(ctx,"(torus/sell-tokens USD -100)");
			assertArgumentError(ctx);
		}

	}

	@Test public void testMissingMarket() {
		Context ctx=context();

		// The GBP market should not exist initially
		assertNull(eval(ctx,"(torus/get-market GBP)"));

		// price should be null for missing markets, regardless of whether or not a token exists
		assertNull(eval(ctx,"(torus/price GBP)"));
		assertNull(eval(ctx,"(torus/price #789798789)"));

		// Adding liquidity without a convex amount should fail
		assertError(step(ctx,"(torus/add-liquidity GBP 100)"));
		
		// Adding liquidity should work if CVX amount provided
		assertEquals(1000L,evalL(ctx,"(torus/add-liquidity GBP 100 10000)"));
		
		// Adding liquidity multiple times in single transaction
		assertEquals(3000L,evalL(ctx,"(do (dotimes [i 3] (torus/add-liquidity GBP 100 10000)) (asset/balance (torus/get-market GBP)))"));
	}

	@Test public void testDeployedCurrencies() {
		Context ctx=context(); // Initial test context
		ctx= step(ctx,"(def CFGBP (import currency.GBPF :as GBP))");
		ctx= step(ctx,"(def CFUSD (import currency.USDF :as USD))");
		assertNotNull(ctx.getResult());
		ctx= step(ctx,"(torus/price GBP USD)");
		assertTrue(ctx.getResult() instanceof CVMDouble);

		ctx= step(ctx,"(torus/price GBP USD)");

	}
	
	@Test public void testMultiTokenListing() {
		Context ctx=context();
		String importS="(import asset.multi-token :as mt)";
		ctx=exec(ctx,importS);
		
		ctx=exec(ctx,"(def ECO [mt (call mt (create :ECO))])");
		assertTrue(ctx.getResult() instanceof AVector);

		ctx= exec(ctx,"(def ECOM (call TORUS (create-market ECO)))");
		assertTrue(ctx.getResult() instanceof Address);

		assertNull(eval(ctx,"(torus/price ECO)"));
		
		ctx=exec(ctx,"(call ECO (mint 1000000))");
		assertCVMEquals(1000000,eval(ctx,"(asset/balance ECO)"));
		
		ctx=exec(ctx,"(torus/add-liquidity ECO 1000 10000)");
		assertEquals(10.0,evalD(ctx,"(torus/price ECO)"));
	}
	
	@Test public void testLiquidityShareToken() {
		Context ctx=context();
	
		// Set up some liquidity
		ctx=step(ctx,"(torus/add-liquidity USD 100 10000)");
		
		AssetTester.doFungibleTests(ctx, USD_MARKET, ctx.getAddress());
		
		// Withdraw some liquidity
		ctx=step(ctx,"(torus/withdraw-liquidity USD 500)");

		AssetTester.doFungibleTests(ctx, USD_MARKET, ctx.getAddress());

		// Withdraw remaining liquidity, should cause fungible tests to fail because no balance to test
		final Context fctx=step(ctx,"(torus/withdraw-liquidity USD 500)");		
		assertThrows(Throwable.class,()->{
			AssetTester.doFungibleTests(fctx, USD_MARKET, fctx.getAddress());
		});
		
		// Tests should fail for a non-existent market
		assertThrows(Throwable.class,()->{
			AssetTester.doFungibleTests(fctx, eval(fctx,"(torus/get-market GBP)"), fctx.getAddress());
		});
	}

	@Test public void testLiquidityZeroTokens() {
		Context ctx=context();
		ctx= exec(ctx,"(def BROK (deploy (@convex.fungible/build-token {:supply 1000000})))");
		
		ctx= exec(ctx,"(def BM (call torus (create-market BROK)))");
		assertNull(eval(ctx,"(torus/price BROK)"));
		
		ctx= exec(ctx,"(torus/add-liquidity BROK 0 1000)");
		assertEquals(CVMLong.ZERO,ctx.getResult());
		assertNull(eval(ctx,"(torus/price BROK)"));

		ctx= exec(ctx,"(torus/add-liquidity BROK 1000 1000)");
		assertEquals(CVMDouble.create(2.0),eval(ctx,"(torus/price BROK)"));
		
		CVMLong E_SHARES=CVMLong.create(1414); // 1000 * sqrt(2)
		assertEquals(E_SHARES,eval(ctx,"(asset/balance BM *address*)"));
	}
	
	@Test public void testBadWithdraw() {
		Context ctx=context();
		ctx= exec(ctx,"(def TOK (deploy (@convex.fungible/build-token {:supply 10000})))");
		ctx= exec(ctx,"(def TM (torus/get-market TOK))");
		
		CVMLong E_SHARES=CVMLong.create(10000);
		ctx= exec(ctx,"(torus/add-liquidity TOK 5000 20000)");
		assertEquals(E_SHARES,ctx.getResult());
		assertEquals(RT.cvm(4.0),eval(ctx,"(torus/price TOK)"));
		
		assertFundsError(step(ctx,"(torus/withdraw-liquidity TOK 10001)"));
		assertArgumentError(step(ctx,"(torus/withdraw-liquidity TOK -100)"));
	}
	
	@Test public void testLiquidityZeroCVM() {
		// Bug fix for #517, thanks Ash!
		Context ctx=context();
		ctx= exec(ctx,"(def BROK (deploy (@convex.fungible/build-token {:supply 1000000})))");
		
		ctx= exec(ctx,"(def BM (call torus (create-market BROK)))");
		assertNull(eval(ctx,"(torus/price BROK)"));
		
		ctx= exec(ctx,"(torus/add-liquidity BROK 1000 0)");
		assertEquals(CVMLong.ZERO,ctx.getResult());
		assertNull(eval(ctx,"(torus/price BROK)"));

		ctx= exec(ctx,"(torus/add-liquidity BROK 0 1000)");
		CVMLong E_SHARES=CVMLong.create(1000);
		assertEquals(E_SHARES,ctx.getResult());
		assertEquals(CVMDouble.ONE,eval(ctx,"(torus/price BROK)"));
		assertEquals(E_SHARES,eval(ctx,"(asset/balance BM *address*)"));

		// check withdrawing all shares
		ctx= exec(ctx,"(torus/withdraw-liquidity BROK "+E_SHARES+")");
		assertNull(eval(ctx,"(torus/price BROK)"));
		assertEquals(CVMLong.ZERO,eval(ctx,"(asset/balance BM *address*)"));
		assertEquals(CVMLong.ZERO,eval(ctx,"(asset/balance BROK BM)"));
		assertEquals(CVMLong.ZERO,eval(ctx,"(balance BM)"));
	}
	
	@Test public void testTorusAPI() {
		Context ctx=context();

		// Deploy GBP market.
		ctx= exec(ctx,"(def GBPM (call TORUS (create-market GBP)))");
		Address GBP_MARKET=(Address)ctx.getResult();
		assertNotNull(GBP_MARKET);

		// Check we can access the USD market
		ctx= exec(ctx,"(def USDM (torus/get-market USD))");
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
		Context ctx=context();

		// Check we can access the USD market
		ctx= exec(ctx,"(def USDM (torus/get-market USD))");
		assertEquals(USD_MARKET,ctx.getResult());

		// should be no price for initial market with zero liquidity
		assertNull(eval(ctx,"(call USDM (price))"));

		// Offer tokens to market ($200k)
		ctx= exec(ctx,"(asset/offer USDM [USD 20000000])");
		assertEquals(20000000L,evalL(ctx,"(asset/get-offer USD *address* USDM)"));

		// ============================================================
		// FIRST TEST: Initial deposit of $100k USD liquidity
		// Deposit some liquidity $100,000 for 1000 Gold = $100 price = 100000 CVX / US Cent
		ctx= exec(ctx,"(call USDM 1000000000000 (add-liquidity 10000000))");
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
		ctx= exec(ctx,"(call USDM 1000000000000 (add-liquidity 10000000))");
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
		// ctx=ctx.withJuice(0);
		ctx=exec(ctx,"(torus/withdraw-liquidity USD "+shares+")");
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
	
	public static void main(String[] args) {
		TorusTest t=new TorusTest();
		
		t.doAnalysis();
	}


	private void doAnalysis() {
		Context ctx=step(context(),"(torus/add-liquidity USD 10000000 1000000000000)");
		
		StringBuilder sb=new StringBuilder();
		sb.append("Torus Juice Costs:\n");
		sb.append(" - Price       "+juice(ctx,"(torus/price GBP)")+"\n");
		sb.append(" - Transfer     "+juice(ctx,"(asset/transfer #1 [USD 10])")+"\n");
		sb.append(" - Swap        "+juice(ctx,"(call USDM *balance* (buy-tokens 10))")+"\n");
		System.out.println(sb.toString());
	}

}
