package convex.lib;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import convex.test.Testing;

public class SimpleNFTTest {
	
	static final AKeyPair KP1=AKeyPair.generate();
	static final AKeyPair KP2=AKeyPair.generate();
	
	static final Address NFT;
	
	private static final Context<?> CTX;
	
	static {
		Context<?> ctx=TestState.CONTEXT.fork();
		String importS = "(import asset.simple-nft :as nft)";
		ctx=step(ctx,importS);
		NFT=(Address)ctx.getResult();
		assertNotNull(NFT);
		ctx=step(ctx,"(import convex.asset :as asset)");
		CTX=ctx;
	}
	
	@Test public void testScript1() {
		Context<?> c=Testing.runTests(CTX,"contracts/nft/simple-nft-test.con");
		assertNotError(c);
	}
	
	
	@SuppressWarnings("unchecked")
	@Test public void testAssetAPI() {
		Context<?> ctx=CTX.fork();
		ctx=step(ctx,"(def total (map (fn [v] (call nft (create))) [1 2 3 4]))");
		AVector<CVMLong> v=(AVector<CVMLong>) ctx.getResult();
		assertEquals(4,v.count());
		CVMLong b1=v.get(0);
		
		// Test balance
		assertEquals(Sets.of(v.toCellArray()),eval(ctx,"(asset/balance nft)"));
		
		// Create test Users
		ctx=ctx.createAccount(KP1.getAccountKey());
		Address user1=(Address) ctx.getResult();
		ctx=ctx.createAccount(KP2.getAccountKey());
		Address user2=(Address) ctx.getResult();
		
		ctx=step(ctx,"(asset/transfer "+user1+" [nft (set (next total))])");
		ctx=step(ctx,"(asset/transfer "+user2+" [nft #{"+b1+"}])");
		assertEquals(Sets.of(b1),ctx.getResult());
		
		AssetTest.doAssetTests(ctx, NFT, user1, user2);

	}
}
