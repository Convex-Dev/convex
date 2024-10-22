package convex.lib;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertTrustError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;

public class MultiTokenTest extends ACVMTest {
	
	private final Address mt;
	
	protected MultiTokenTest() {
		super();
		mt=(Address) context().getEnvironment().get(Symbol.create("mt"));
	}
	
	@Override protected Context buildContext(Context ctx) {
		String importS="(import asset.multi-token :as mt)";
		ctx=exec(ctx,importS);
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(call mt (create :USD))");
		
		ctx=exec(ctx,"(call [mt :USD] (mint  1000))");
		assertCVMEquals(1000,ctx.getResult());
		
		ctx=exec(ctx,"(asset/transfer "+InitTest.VILLAIN+" [[mt :USD] 400])");

		return ctx;
	}
	
	@Test public void testOfferAccept() {
		Context ctx = context();
		
		// Note use of asset/create interface
		ctx=exec(ctx,"(def FOO (asset/create mt :foo))");
		
		// Mint with standard call
		ctx=exec(ctx,"(call FOO (mint 10000))");
		assertCVMEquals(10000,evalL(ctx,"(asset/balance FOO)"));
		
		assertCVMEquals(0,evalL(ctx,"(asset/get-offer FOO *address* #1)"));
		
		ctx=exec(ctx,"(asset/offer *address* FOO 1000)");
		// System.out.println(ctx.getResult());
		
		assertCVMEquals(0,evalL(ctx,"(asset/get-offer FOO *address* #1)"));
		assertCVMEquals(1000,evalL(ctx,"(asset/get-offer FOO *address* *address*)"));
		
		// Consume 600 of offer
		ctx=exec(ctx,"(asset/accept *address* FOO 400)");
		assertCVMEquals(10000,evalL(ctx,"(asset/balance FOO)"));
		assertCVMEquals(600,evalL(ctx,"(asset/get-offer FOO *address* *address*)"));
	}
	
	@Test public void testGenericFugible() {
		Context ctx = context();
		AVector<ACell> token=Vectors.of(mt,Keyword.create("FOOSD"));
		
		ctx=exec(ctx,"(call mt (create :FOOSD))");
		ctx=exec(ctx,"(asset/mint [mt :FOOSD] 2023)");		
		
		// Fungible tests
		AssetTester.doFungibleTests(ctx, token, HERO);		
		
		// Test change of control
		TrustTest.testChangeControl(ctx, token);
	}
	
	@Test public void testMint() {
		Context ctx = context();
		
		// Non-existing token can't have balance
		assertEquals(0L,evalL(ctx,"(asset/balance [mt :FOOSD])"));
		
		ctx=exec(ctx,"(call mt (create :FOOSD))");
		
		assertEquals(0L,evalL(ctx,"(asset/balance [mt :FOOSD])"));
		
		// Mint with standard call
		ctx=exec(ctx,"(call [mt :FOOSD] (mint 2022))");
		assertEquals(2022L,evalL(ctx,"(asset/balance [mt :FOOSD])"));

		// Mint with asset API
		ctx=exec(ctx,"(asset/mint [mt :FOOSD] 2022)");
		ctx=exec(ctx,"(asset/balance [mt :FOOSD] *address*)");
		assertEquals(CVMLong.create(4044),ctx.getResult());

		// Negative mint allowed?
		assertEquals(4043,evalL(ctx,"(call [mt :FOOSD] (mint -1))"));

		assertError(step(ctx,"(call [mt :FOOSD] (mint -9999999999999999))"));
		
		// Remove controller => no more minting!
		ctx=exec(ctx,"(trust/change-control [mt :FOOSD] #0)");
		assertTrustError(step(ctx,"(asset/mint [mt :FOOSD] 2022)"));
	}

}
