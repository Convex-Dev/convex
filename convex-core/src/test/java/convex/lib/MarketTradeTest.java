package convex.lib;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;

public class MarketTradeTest extends ACVMTest {
	
	static final AKeyPair KP1=AKeyPair.generate();
	static final AKeyPair KP2=AKeyPair.generate();
	
	protected Address NFT;
	protected final long BAL=1000000;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		String importS = "(import asset.nft.basic :as nft)";
		ctx=exec(ctx,importS);
		NFT=ctx.getResult();
		
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import asset.market.trade :as trade)");
		ctx=exec(ctx,"(import asset.wrap.convex :as wcvx)");
		ctx=exec(ctx,"(wcvx/wrap "+BAL+")");
		return ctx;
	}
	
	@Test public void testCantAfford() {
		Context ctx=context();
		ctx=exec(ctx,"(def nid (call nft (create {:foo :bar})))");
		ctx=exec(ctx,"(def item [nft #{nid}])");
		ctx=exec(ctx,"(def tid (trade/post item [wcvx "+(BAL+1)+"]))");
		
		assertFundsError(step(ctx,"(trade/buy tid)"));
	}
	
	@Test public void testCancel() {
		Context ctx=context();
		ctx=exec(ctx,"(def nid (call nft (create {:foo :bar})))");
		ctx=exec(ctx,"(def item [nft #{nid}])");
		ctx=exec(ctx,"(def tid (trade/post item [wcvx "+(BAL*3)+"]))"); // unaffordable
		long tid=((CVMLong)(ctx.getResult())).longValue();
		
		// Creator should not hold item
		assertFalse(evalB(ctx,"(asset/owns? *address* item)"));
		
		// Nobody else should be able to cancel
		assertTrustError(step(ctx.forkWithAddress(Address.ZERO),"(do (import asset.market.trade :as t) (t/cancel "+tid+"))"));
		
		// Cancel trade - should succeed
		ctx=exec(ctx,"(trade/cancel tid)");
		
		// Check that asset is returned and no tokens spent
		assertEquals(BAL,evalL(ctx,"(asset/balance wcvx)"));
		assertTrue(evalB(ctx,"(asset/owns? *address* item)"));
		
		// Can't cancel a second time, should be already gone
		assertStateError(step(ctx,"(trade/cancel tid)"));
		
		// Can't cancel a non-existent trade
		assertStateError(step(ctx,"(trade/cancel 696969)"));

	}


	
	@Test public void testBuySell() {
		Context ctx=context();
		ctx=exec(ctx,"(def nid (call nft (create {:foo :bar})))");
		CVMLong nftid=ctx.getResult();
		assertNotNull(nftid);
		
		ctx=exec(ctx,"(def item [nft #{nid}])");
		assertTrue(evalB(ctx,"(asset/owns? *address* item)"));
		
		long PRICE=1000;
		
		ctx=exec(ctx,"(def tid (trade/post item [wcvx "+PRICE+"]))");
		CVMLong tid=ctx.getResult();
		assertNotNull(tid);
		
		// Can't post something not owned, since we already posted for sale
		assertError(step(ctx,"(def tid (trade/post item [wcvx 1]))"));
		
		// No coins spent yet!
		assertEquals(BAL,evalL(ctx,"(asset/balance wcvx)"));
		
		assertStateError(step(ctx,"(call [trade tid] (claim))"));
		
		// Item should have been removed from seller
		assertFalse(evalB(ctx,"(asset/owns? *address* item)"));
		
		// Buy the item back (no price)
		ctx=exec(ctx,"(trade/buy tid)");
		assertTrue(evalB(ctx,"(asset/owns? *address* item)"));
		
		// Coins should be gone
		assertEquals(BAL-PRICE,evalL(ctx,"(asset/balance wcvx)"));

		
		// Claim should be OK now
		ctx=exec(ctx,"(call [trade tid] (claim))");
		
		// Trade should no longer exist
		assertStateError(step(ctx,"(call [trade tid] (claim))"));

		// Coins should be reclaimed
		assertEquals(BAL,evalL(ctx,"(asset/balance wcvx)"));

	}
}
