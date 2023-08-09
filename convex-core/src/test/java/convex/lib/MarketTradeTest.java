package convex.lib;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;

import static convex.test.Assertions.assertNotError;

public class MarketTradeTest extends ACVMTest {
	
	static final AKeyPair KP1=AKeyPair.generate();
	static final AKeyPair KP2=AKeyPair.generate();
	
	Address NFT;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		String importS = "(import asset.nft.basic :as nft)";
		ctx=step(ctx,importS);
		NFT=ctx.getResult();
		
		ctx=step(ctx,"(import convex.asset :as asset)");
		ctx=step(ctx,"(import asset.market.trade :as trade)");
		return ctx;
	}
	
	@Test public void testBuySell() {
		Context ctx=context();
		ctx=step(ctx,"(def nid (call nft (create {:foo :bar})))");
		CVMLong nftid=ctx.getResult();
		assertNotNull(nftid);
		
		ctx=step(ctx,"(def item [nft #{nid}])");
		assertTrue(evalB(ctx,"(asset/owns? *address* item)"));
		
		ctx=step(ctx,"(def tid (trade/post item nil))");
		CVMLong tid=ctx.getResult();
		assertNotNull(tid);
		
		// Item should have been removed from seller
		assertFalse(evalB(ctx,"(asset/owns? *address* item)"));
		
		// Buy the item back (no price)
		ctx=step(ctx,"(trade/buy tid)");
		assertNotError(ctx);
		assertTrue(evalB(ctx,"(asset/owns? *address* item)"));
	}
}
