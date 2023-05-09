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
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class MultiTokenTest extends ACVMTest {
	
	private final Address mt;
	
	protected MultiTokenTest() {
		super();
		mt=(Address) context().getEnvironment().get(Symbol.create("mt"));
	}
	
	@Override protected Context buildContext(Context ctx) {
		String importS="(import asset.multi-token :as mt)";
		ctx=step(ctx,importS);
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.asset :as asset)");
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.fungible :as fungible)");
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.trust :as trust)");
		assertNotError(ctx);
		
		ctx=step(ctx,"(call mt (create :USD))");
		assertNotError(ctx);
		
		ctx=step(ctx,"(call [mt :USD] (mint  1000))");
		assertCVMEquals(1000,ctx.getResult());
		
		ctx=step(ctx,"(asset/transfer "+InitTest.VILLAIN+" [[mt :USD] 400])");
		assertNotError(ctx);

		return ctx;
	}
	
	@Test public void testOfferAccept() {
		Context ctx = context();
		
		ctx=step(ctx,"(def id (call mt (create :foo)))");
		assertEquals(Keywords.FOO,ctx.getResult());
		ctx=step(ctx,"(def FOO [mt :foo])");
		
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
		assertEquals(0L,evalL(ctx,"(asset/balance [mt :FOOSD])"));
		
		ctx=step(ctx,"(call mt (create :FOOSD))");
		assertNotError(ctx);
		
		assertEquals(0L,evalL(ctx,"(asset/balance [mt :FOOSD])"));
		
		// Mint with standard call
		ctx=step(ctx,"(call [mt :FOOSD] (mint 2022))");
		assertNotError(ctx);
		assertEquals(2022L,evalL(ctx,"(asset/balance [mt :FOOSD])"));

		// Mint with asset API
		ctx=step(ctx,"(asset/mint [mt :FOOSD] 2022)");
		assertNotError(ctx);
		ctx=step(ctx,"(asset/balance [mt :FOOSD] *address*)");
		assertEquals(CVMLong.create(4044),ctx.getResult());

		// Negative mint allowed?
		assertEquals(4043,evalL(ctx,"(call [mt :FOOSD] (mint -1))"));

		assertError(step(ctx,"(call [mt :FOOSD] (mint -9999999999999999))"));
		
		AVector<ACell> token=Vectors.of(mt,Keyword.create("FOOSD"));
		AssetTester.doFungibleTests(ctx, token, HERO);
		
		// Test change of control
		TrustTest.testChangeControl(ctx, token);
		
		// Remove controller => no more minting!
		ctx=step(ctx,"(trust/change-control [mt :FOOSD] #0)");
		assertNotError(ctx);
		assertTrustError(step(ctx,"(asset/mint [mt :FOOSD] 2022)"));
	}

}
