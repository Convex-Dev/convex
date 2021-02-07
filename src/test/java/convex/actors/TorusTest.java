package convex.actors;
 
import static convex.core.lang.TestState.*;
import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;

public class TorusTest {
	static Address USD = null;
	static Address GBP = null;
	static Address TORUS = null;
	static Address USD_MARKET = null;
	static Context<Address> CONTEXT = null;

	static {
		Context<?> INITIAL=TestState.INITIAL_CONTEXT.fork();
		try {
			Context<?> ctx=INITIAL;
			ctx=step(ctx,"(import convex.fungible :as fun)");
			ctx=step(ctx,"(import convex.asset :as asset)");
			
			// Deploy currencies for testing (10m each, 2 decimal places)
			ctx=step(ctx,"(def USD (deploy (fun/build-token {:supply 1000000000})))");
			assertNotError(ctx);
			USD=(Address) ctx.getResult();
			//System.out.println("USD deployed Address = "+USD);
			ctx=step(ctx,"(def GBP (deploy (fun/build-token {:supply 1000000000})))");
			GBP=(Address) ctx.getResult();
			
			// Deploy Torus actor itself
			ctx= ctx.deployActor(Reader.readResource("actors/torus.con"));
			assertNotError(ctx);
			TORUS=(Address)ctx.getResult();
			assertNotNull(ctx.getAccountStatus(TORUS));
			ctx= step(ctx,"(def TORUS "+TORUS+")");
			//System.out.println("Torus deployed Address = "+TORUS);
			
			// Deploy USD market. No market for GBP yet!
			ctx= step(ctx,"(call TORUS (create-market USD))");
			USD_MARKET=(Address)ctx.getResult();
			CONTEXT= ctx.withResult(TORUS);
		} catch (Throwable e) {
			e.printStackTrace();
			throw Utils.sneakyThrow(e);
		}
	}
	
	@Test public void testInitialMarket() {
		Context<?> ctx=CONTEXT.fork();
		
		// Check we can access the USD market
		ctx= step(ctx,"(def USDM (call TORUS (get-market USD)))");
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
	}

	@Test public void testSetup() {
		assertNotNull(TORUS);
		assertNotNull(USD);
		assertNotNull(GBP);
		assertNotNull(USD_MARKET);
	}
	
}
