package convex.lib;

import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import convex.test.Samples;

public class BoxTest extends ACVMTest {
	
	Address BOX;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		
		// Import basic NFTs
		ctx=exec(ctx,"(import asset.box :as box)");
		ctx=exec(ctx,"(import asset.box.actor :as box.actor)");
		BOX=ctx.getResult();
		
		ctx=exec(ctx,"(import convex.asset :as asset)");
		return ctx;
	}
	
	@Test public void testCreate() {
		Context ctx=context();
		
		// Create Basic NFT with map metadata via asset/create
		ctx=step(ctx,"(def t1 (box/create))");
		AVector<?> t1=ctx.getResult();
		assertEquals(BOX,t1.get(0));
		assertTrue(t1.get(1) instanceof CVMLong);
		
		assertStateError(step(ctx,"(box/insert t1 t1)"));
	}
	
	@Test public void testAssetAPI() {
		int NUM=5;
		Context ctx=context();
		for (int i=0; i<NUM; i++) {
			ctx=step(ctx,"(def t"+i+" (box/create))");
		}
		ASet<?> BAL=(ASet<?>)eval(ctx,"(asset/balance box.actor)");
		assertEquals(NUM,BAL.count());
		
		// Test balance of non-holder
		assertSame(Sets.empty(),eval(ctx,"(asset/balance box.actor #0)"));
		
		// Create test Users
		ctx = exec(ctx,"(create-account "+Samples.KEY_PAIR.getAccountKey()+")");
		Address user2=(Address) ctx.getResult();

		ctx=step(ctx,"(asset/transfer "+user2+" ["+BOX+" #{"+BAL.get(0)+"}])");
		assertNotError(ctx);
		
		AssetTester.doAssetTests(ctx, BOX, ctx.getAddress(), user2);
	}
}
