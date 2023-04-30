package convex.lib;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;

public class MultiTokenTest extends ACVMTest {
	
	private final Address mt;
	
	protected MultiTokenTest() {
		super(createFungibleState());
		mt=(Address) context().getEnvironment().get(Symbol.create("mt"));
	}
	
	private static State createFungibleState() {
		Context<?> ctx=TestState.CONTEXT.fork();
		String importS="(import asset.multi-token :as mt)";
		ctx=step(ctx,importS);
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.asset :as asset)");
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.fungible :as fungible)");
		assertNotError(ctx);
		
		ctx=step(ctx,"(call mt (create :USD))");
		assertNotError(ctx);
		
		ctx=step(ctx,"(call [mt :USD] (mint  1000))");
		assertCVMEquals(1000,ctx.getResult());
		
		ctx=step(ctx,"(asset/transfer "+InitTest.VILLAIN+" [[mt :USD] 400])");
		assertNotError(ctx);

		
		return ctx.getState();
	}
	
	@Test public void testMint() {
		Context<?> ctx = context();
		
		// Non-existing token can't have balance
		assertEquals(0L,evalL("(asset/balance [mt :FOOSD])"));
		
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
	}

}
