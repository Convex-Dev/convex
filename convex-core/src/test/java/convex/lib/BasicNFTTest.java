package convex.lib;

import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.AVector;
import convex.core.data.Sets;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;

public class BasicNFTTest extends ACVMTest {
	
	static final AKeyPair KP1=AKeyPair.generate();
	static final AKeyPair KP2=AKeyPair.generate();
	
	Address NFT;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		
		// Import basic NFTs
		ctx=step(ctx,"(import asset.nft.basic :as nft)");
		NFT=ctx.getResult();
		
		ctx=step(ctx,"(import convex.asset :as asset)");
		return ctx;
	}
	
	@Test public void testCreate() {
		Context ctx=context();
		
		// Create Basic NFT with map metadata via asset/create
		ctx=step(ctx,"(def t1 (asset/create nft {:name \"Bob\"}))");
		assertNotError(ctx);
		
	}
	
	@Test public void testMetadata() {
		Context ctx=context();
		
		// Create Basic NFT with vector as metadata
		ctx=step(ctx,"(def t1 (call nft (create [1 2])))");
		
		assertEquals(Vectors.of(1,2),eval(ctx,"(call [nft t1] (get-metadata))"));
		assertEquals(Vectors.of(1,2),eval(ctx,"(call nft (get-metadata t1))"));
		
		// Create Basic NFT with `nil` metadata (empty) using asset/create
		ctx=step(ctx,"(def t2 (asset/create nft))");
		assertNull(eval(ctx,"(call [nft t2] (get-metadata))"));
		assertNull(eval(ctx,"(call nft (get-metadata t2))"));

		// Burning NFT should delete metadata
		ctx=step(ctx,"(call nft (burn t1))");
		assertNotError(ctx);
		assertNull(eval(ctx,"(call [nft t1] (get-metadata))"));
		
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testAssetAPI() {
		Context ctx=context();
		ctx=step(ctx,"(def total (map (fn [v] (call nft (create))) [1 2 3 4]))");
		AVector<CVMLong> v=(AVector<CVMLong>) ctx.getResult();
		assertEquals(4,v.count());
		CVMLong b1=v.get(0);
		
		// Test balance
		assertEquals(Sets.of(v.toCellArray()),eval(ctx,"(asset/balance nft)"));
		
		// Create test Users
		ctx = exec(ctx,"(create-account "+KP1.getAccountKey()+")");
		Address user1=(Address) ctx.getResult();
		ctx = exec(ctx,"(create-account "+KP2.getAccountKey()+")");
		Address user2=(Address) ctx.getResult();
		
		ctx=step(ctx,"(asset/transfer "+user1+" [nft (set (next total))])");
		ctx=step(ctx,"(asset/transfer "+user2+" [nft #{"+b1+"}])");
		assertEquals(Sets.of(b1),ctx.getResult());
		
		AssetTester.doAssetTests(ctx, NFT, user1, user2);
	}
}
