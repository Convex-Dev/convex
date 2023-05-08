package convex.lib;

import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import convex.test.Testing;

public class SimpleNFTTest extends ACVMTest {
	
	static final AKeyPair KP1=AKeyPair.generate();
	static final AKeyPair KP2=AKeyPair.generate();
	
	final Address NFT;
	
	protected SimpleNFTTest() {
		super(createState());
		NFT = context().lookup(Symbol.create("nft")).getResult();
	}
	
	private static State createState() {
		Context ctx=TestState.CONTEXT.fork();
		String importS = "(import asset.nft.simple :as nft)";
		ctx=step(ctx,importS);
		ctx=step(ctx,"(import convex.asset :as asset)");
		return ctx.getState();
	}
	
	@Test public void testScript1() {
		Context c=Testing.runTests(context(),"contracts/nft/simple-nft-test.con");
		assertNotError(c);
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
		ctx=ctx.createAccount(KP1.getAccountKey());
		Address user1=(Address) ctx.getResult();
		ctx=ctx.createAccount(KP2.getAccountKey());
		Address user2=(Address) ctx.getResult();
		
		ctx=step(ctx,"(asset/transfer "+user1+" [nft (set (next total))])");
		ctx=step(ctx,"(asset/transfer "+user2+" [nft #{"+b1+"}])");
		assertEquals(Sets.of(b1),ctx.getResult());
		
		AssetTester.doAssetTests(ctx, NFT, user1, user2);

	}
}
