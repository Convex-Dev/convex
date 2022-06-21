package convex.lib;

import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;

import static convex.test.Assertions.*;

public class MultiTokenTest extends ACVMTest {
	
	protected MultiTokenTest() {
		super(createFungibleState());
	}
	
	private static State createFungibleState() {
		Context<?> ctx=TestState.CONTEXT.fork();
		String importS="(import asset.multi-token :as mt)";
		ctx=step(ctx,importS);
		assertNotError(ctx);
		ctx=step(ctx,"(import convex.asset :as asset)");
		assertNotError(ctx);
		
		ctx=step(ctx,"(call mt (create :USD))");
		assertNotError(ctx);
		ctx=step(ctx,"(call mt (mint :USD 1000000000))");
		assertNotError(ctx);
		ctx=step(ctx,"(asset/transfer "+InitTest.VILLAIN+" [[mt :USD] 1000000000])");
		assertNotError(ctx);

		
		return ctx.getState();
	}
	
	@Test public void testMint() {
		Context<?> ctx = context();
		
		// Non-existing token can't have balance
		assertError(step("(asset/balance [mt :FOOSD])"));
		
		ctx=step(ctx,"(call mt (create :FOOSD))");
		assertNotError(ctx);
		
		assertEquals(0L,evalL(ctx,"(asset/balance [mt :FOOSD])"));
		
		ctx=step(ctx,"(call mt (mint :FOOSD 2022))");
		assertNotError(ctx);

		assertEquals(2022L,evalL(ctx,"(asset/balance [mt :FOOSD])"));
		
		// Negative mint allowed?
		// assertError(step(ctx,"(call mt (mint :FOOSD -1))"));

	}

}
