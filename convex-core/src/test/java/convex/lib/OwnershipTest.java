package convex.lib;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertCastError;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class OwnershipTest extends ACVMTest {
	private Address monitor;

	@Override protected Context buildContext(Context ctx) {
		ctx = step(ctx, "(import convex.trust :as trust)");
		assertNotError(ctx);
		ctx = step(ctx, "(import convex.trust.ownership-monitor :as monitor)");
		assertNotError(ctx);
		monitor = (Address)ctx.getResult();
		return ctx;
	}
	
	/**
	 * Test that re-deployment of Fungible matches what is expected
	 */
	@Test
	public void testLibraryProperties() {
		assertTrue(CONTEXT.getAccountStatus(monitor).isActor());

		// check alias is set up correctly
		assertEquals(monitor, eval(CONTEXT, "monitor"));
	}
	
	@Test 
	public void testFungibleOwnership() {
		Context ctx=context();
		ctx = step(ctx, "(import asset.multi-token :as mt)");
		assertNotError(ctx);
		
		ctx=step(ctx,"(call mt (create :USD))");
		assertNotError(ctx);
		
		ctx=step(ctx,"(def USD [mt :USD])");
		assertNotError(ctx);
		
		ctx=step(ctx,"(call USD (mint 1000))");
		assertCVMEquals(1000,ctx.getResult());

		assertTrue(evalB(ctx,"(trust/trusted? [monitor [USD 1000]] *address*)"));
		assertFalse(evalB(ctx,"(trust/trusted? [monitor [USD 1001]] *address*)"));
		
		assertCastError(step(ctx,"(trust/trusted? [monitor :bad-scope] *address*)"));	
	}
	
	@Test 
	public void testNFTOwnership() {
		Context ctx=context();
		ctx = step(ctx, "(import asset.nft.simple :as nft-actor)");
		assertNotError(ctx);
		
		ctx=step(ctx,"(def NFT (call nft-actor (create)))");
		assertNotError(ctx);
		
		assertTrue(evalB(ctx,"(trust/trusted? [monitor [nft-actor #{NFT}]] *address*)"));
		assertFalse(evalB(ctx,"(trust/trusted? [monitor [nft-actor #{NFT :bad-ID}]] *address*)"));
		assertFalse(evalB(ctx,"(trust/trusted? [monitor [nft-actor #{:bad-ID}]] *address*)"));
		
		assertCastError(step(ctx,"(trust/trusted? [monitor :bad-scope] *address*)"));	
	}


}
