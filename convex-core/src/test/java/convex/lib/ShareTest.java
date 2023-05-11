package convex.lib;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class ShareTest extends ACVMTest {
	
	private final Address mt;
	
	protected ShareTest() {
		super();
		mt=(Address) context().getEnvironment().get(Symbol.create("mt"));
	}
	
	@Override protected Context buildContext(Context ctx) {
		String importS="(import asset.share :as mt)";
		ctx=step(ctx,importS);
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.asset :as asset)");
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.fungible :as fungible)");
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.trust :as trust)");
		assertNotError(ctx);
		
		// TODO: fix underlying
		ctx=step(ctx,"(def token [mt (call mt (create :USD))])");
		assertNotError(ctx);

		ctx=step(ctx,"(call token (mint  1000))");
		assertCVMEquals(1000,ctx.getResult());
		
		ctx=step(ctx,"(asset/transfer "+InitTest.VILLAIN+" [token 400])");
		assertNotError(ctx);

		return ctx;
	}
	
	@Test public void testOfferAccept() {
		Context ctx = context();
		
		// Create a fresh token
		ctx=step(ctx,"(def FOO [mt (call mt (create :foo))])");
		
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
		
		// Non-existing token can't have balance
		assertEquals(0L,evalL(ctx,"(asset/balance [mt :foobar])"));
		
		ctx=step(ctx,"(def SSS [mt (call mt (create token))])");
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

		// Negative mint allowed?
		assertEquals(4043,evalL(ctx,"(call SSS (mint -1))"));

		assertError(step(ctx,"(call SSS (mint -9999999999999999))"));
		
		AVector<ACell> token=eval(ctx,"SSS");
		assertEquals(mt,token.get(0)); 
		AssetTester.doFungibleTests(ctx, token, HERO);
		
		// Test change of control
		TrustTest.testChangeControl(ctx, token);
		
		// Remove controller => no more minting!
		ctx=step(ctx,"(trust/change-control SSS #0)");
		assertNotError(ctx);
		assertTrustError(step(ctx,"(asset/mint SSS 2022)"));
	}

}
