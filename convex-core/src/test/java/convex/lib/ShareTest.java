package convex.lib;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.*;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;

public class ShareTest extends ACVMTest {
	
	private final Address shareActor;
	
	protected ShareTest() {
		super();
		shareActor=(Address) context().getEnvironment().get(Symbol.create("share"));
	}
	
	@Override protected Context buildContext(Context ctx) {
		ctx=exec(ctx,"(import asset.multi-token :as mt)");
		ctx=step(ctx,"(import asset.share :as share)");	
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.fungible :as fungible)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		
		// Underlying asset
		ctx=exec(ctx,"(def underlying [mt (call mt (create :USD))])");

		ctx=exec(ctx,"(call underlying (mint 1000000))");
		assertCVMEquals(1000000,ctx.getResult());
		
		ctx=exec(ctx,"(asset/transfer "+InitTest.VILLAIN+" [underlying 400])");

		return ctx;
	}
	
	@Test public void testOfferAccept() {
		Context ctx = context();
		
		// Create a fresh token based on underlying
		ctx=step(ctx,"(def FOO [share (call share (create underlying))])");
		
		// Mint with standard call
		ctx=step(ctx,"(call FOO (mint 10000))");
		assertCVMEquals(10000,evalL(ctx,"(asset/balance FOO)"));
		
		assertCVMEquals(0,evalL(ctx,"(asset/get-offer FOO *address* #1)"));
		
		ctx=step(ctx,"(asset/offer *address* FOO 1000)");
		// System.out.println(ctx.getResult());
		
		assertCVMEquals(0,evalL(ctx,"(asset/get-offer FOO *address* #1)"));
		assertCVMEquals(1000,evalL(ctx,"(asset/get-offer FOO *address* *address*)"));
		
		// Consume 600 of offer
		ctx=step(ctx,"(asset/accept *address* FOO 400)");
		assertNotError(ctx);
		assertCVMEquals(10000,evalL(ctx,"(asset/balance FOO)"));
		assertCVMEquals(600,evalL(ctx,"(asset/get-offer FOO *address* *address*)"));
	}
	
	@Test public void testMint() {
		Context ctx = context();
		
		ctx=step(ctx,"(def SSS [share (call share (create underlying))])");
		assertNotError(ctx);
		
		assertEquals(0L,evalL(ctx,"(asset/balance SSS)"));
		
		// Mint with standard call
		ctx=step(ctx,"(call SSS (mint 2022))");
		assertNotError(ctx);
		assertEquals(2022L,evalL(ctx,"(asset/balance SSS)"));

		// Mint with asset API
		ctx=step(ctx,"(asset/mint SSS 2022)");
		assertNotError(ctx);
		ctx=step(ctx,"(asset/balance SSS *address*)");
		assertEquals(CVMLong.create(4044),ctx.getResult());

		assertArgumentError(step(ctx,"(call SSS (mint -1))"));
		assertArgumentError(step(ctx,"(call SSS (mint -9999999999999999))"));
		
		AVector<ACell> token=eval(ctx,"SSS");
		assertEquals(shareActor,token.get(0)); 
		AssetTester.doFungibleTests(ctx, token, HERO);
		
		// Test change of control
		TrustTest.testChangeControl(ctx, token);
		
		// Remove controller => no more minting!
		ctx=step(ctx,"(trust/change-control SSS #0)");
		assertNotError(ctx);
		assertTrustError(step(ctx,"(asset/mint SSS 2022)"));
	}

}
