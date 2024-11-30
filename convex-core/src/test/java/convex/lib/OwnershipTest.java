package convex.lib;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertCastError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.lang.ACVMTest;

/**
 * Tests for asset ownership, including use of an asset as a trust monitor
 */
public class OwnershipTest extends ACVMTest {
	private Address monitor;

	@Override
	protected Context buildContext(Context ctx) {
		ctx = exec(ctx, "(import convex.trust :as trust)");
		ctx = exec(ctx, "(import convex.trust.ownership-monitor :as monitor)");
		
		monitor = (Address) ctx.getResult();
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
		Context ctx = context();
		ctx = exec(ctx, "(import asset.multi-token :as mt)");
		ctx = exec(ctx, "(call mt (create :USD))");
		ctx = exec(ctx, "(def USD [mt :USD])");

		ctx = step(ctx, "(call USD (mint 1000))");
		assertCVMEquals(1000, ctx.getResult());

		// Check that ownership monitor correctly handles edge cases
		assertTrue(evalB(ctx, "(trust/trusted? [monitor [USD 1000]] *address*)"));
		assertFalse(evalB(ctx, "(trust/trusted? [monitor [USD 1001]] *address*)"));

		assertCastError(step(ctx, "(trust/trusted? [monitor :bad-scope] *address*)"));
	}

	@Test
	public void testSimpleNFTOwnership() {
		Context ctx = context();
		
		// Setup with 2 simple NFTs
		ctx = exec(ctx, "(import asset.nft.simple :as nft-actor)");
		ctx = exec(ctx, "(def NFT (call nft-actor (create)))");
		ctx = exec(ctx, "(def NFT2 (call nft-actor (create)))");

		assertTrue(evalB(ctx, "(trust/trusted? [monitor [nft-actor #{NFT}]] *address*)"));
		assertTrue(evalB(ctx, "(trust/trusted? [monitor [nft-actor #{NFT NFT2}]] *address*)"));
		assertFalse(evalB(ctx, "(trust/trusted? [monitor [nft-actor #{NFT :bad-ID}]] *address*)"));
		assertFalse(evalB(ctx, "(trust/trusted? [monitor [nft-actor #{:bad-ID}]] *address*)"));

		assertCastError(step(ctx, "(trust/trusted? [monitor :bad-scope] *address*)"));
	}

}
