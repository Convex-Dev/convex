package convex.lib;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Set;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.prim.CVMLong;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.test.Testing;

public class SimpleNFTTest {
	private static final Symbol nSym=Symbol.create("nft");
	
	static final AKeyPair KP1=AKeyPair.generate();
	static final AKeyPair KP2=AKeyPair.generate();
	
	static final Address NFT;
	
	private static final Context<?> CTX;
	
	static {
		Context<?> ctx=TestState.INITIAL_CONTEXT.fork();
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/simple-nft.con")));
			Address nft=(Address) ctx.getResult();
			assert (ctx.getDepth()==0):"Invalid depth: "+ctx.getDepth();
			String importS="(def nft (import "+nft+" :as "+nSym.getName()+"))";
			ctx=step(ctx,importS);
			NFT=(Address)ctx.getResult();
			assertNotNull(NFT);
			
			ctx=ctx.define(nSym, Syntax.create(nft));
		} catch (IOException e) {
			e.printStackTrace();
			throw new Error(e);
		}
		ctx=step(ctx,"(import convex.asset :as asset)");
		CTX=ctx;
	}
	
	@Test public void testSetup() {
		assertTrue(CTX.lookup(nSym).getValue() instanceof Address);
	}
	
	@Test public void testScript1() {
		Context<?> c=Testing.runTests(CTX,"contracts/nft/simple-nft-test.con");
		assertNotError(c);
	}
	
	
	@SuppressWarnings("unchecked")
	@Test public void testAssetAPI() {
		Context<?> ctx=CTX.fork();
		ctx=step(ctx,"(def total (map (fn [v] (call nft (create-nft))) [1 2 3 4]))");
		AVector<CVMLong> v=(AVector<CVMLong>) ctx.getResult();
		assertEquals(4,v.count());
		CVMLong b1=v.get(0);
		
		// Test balance
		assertEquals(Set.create(v.toCellArray()),eval(ctx,"(asset/balance nft)"));
		
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
