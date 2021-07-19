package convex.lib;

import static convex.core.lang.TestState.eval;
import static convex.core.lang.TestState.evalL;
import static convex.core.lang.TestState.step;
import static convex.test.Assertions.assertError;
import static convex.test.Assertions.assertNotError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.test.Assertions;
import convex.test.Testing;

public class BoxTest {
	private static final Symbol nSym=Symbol.create("box");
	
	static final AKeyPair KP1=AKeyPair.generate();
	static final AKeyPair KP2=AKeyPair.generate();
	
	static final Address BOX;
	
	private static final Context<?> CTX;
	
	static {
		Context<?> ctx=TestState.CONTEXT.fork();
		try {
			ctx=ctx.deployActor(Reader.read(Utils.readResourceAsString("libraries/box.con")));
			Address nft=(Address) ctx.getResult();
			assert (ctx.getDepth()==0):"Invalid depth: "+ctx.getDepth();
			String importS="(def box (import "+nft+" :as "+nSym.getName()+"))";
			ctx=step(ctx,importS);
			BOX=(Address)ctx.getResult();
			assertNotNull(BOX);
			
			ctx=ctx.define(nSym, nft);
		} catch (IOException e) {
			e.printStackTrace();
			throw new Error(e);
		}
		ctx=step(ctx,"(import convex.asset :as asset)");
		ctx=step(ctx,"(import convex.fungible :as fun)");
		CTX=ctx;
	}
	
	@Test public void testSetup() {
		assertTrue(CTX.lookupValue(nSym) instanceof Address);
	}
	
	@Test public void testScript1() {
		Context<?> c=Testing.runTests(CTX,"contracts/box/test1.con");
		Assertions.assertNotError(c);
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testContents() {
		Context<?> ctx=CTX.fork();
		ctx=step(ctx,"(def total (map (fn [v] (box/create)) [1 2 3 4]))");
		AVector<CVMLong> v=(AVector<CVMLong>) ctx.getResult();
		assertEquals(4,v.count());
		CVMLong b0=v.get(0);
		CVMLong b1=v.get(1);
		CVMLong b2=v.get(2);
		CVMLong b3=v.get(3);
		
		// Put b1 and b2 in b0
		ctx=step(ctx,"(box/insert "+b0+" [box #{"+b1+" "+b2+"}])");
		assertNotError(ctx);
		assertEquals(Sets.of(b0,b3),eval(ctx,"(asset/balance box *address*)"));
		assertEquals(Sets.of(b1,b2),eval(ctx,"(asset/balance box box)"));
		
		// Try to put b0 in b1 - should fail since b1 not directly owned
		ctx=step(ctx,"(box/insert "+b1+" [box #{"+b0+"}])");
		assertError(ctx);
		
		// Take b1 and b2 out of b0
		ctx=step(ctx,"(box/remove "+b0+" [box #{"+b1+" "+b2+"}])");
		assertEquals(Sets.of(b0,b1,b2,b3),eval(ctx,"(asset/balance box *address*)"));
		assertEquals(Sets.empty(),eval(ctx,"(asset/balance box box)"));
		
		// Try to put a box into itself - should fail because b0 acceptance makes it no longer owned
		ctx=step(ctx,"(box/insert "+b0+" [box #{"+b0+"}])");
		assertError(ctx);
		assertEquals(Sets.of(b0,b1,b2,b3),eval(ctx,"(asset/balance box *address*)"));
		
		// Use a fungible token
		ctx=step(ctx,"(def FOOCOIN (deploy (fun/build-token {:supply 1000000})))");
		ctx=step(ctx,"(box/insert "+b1+" [FOOCOIN 1000])");
		ctx=step(ctx,"(box/insert "+b2+" [FOOCOIN 2000])");
		assertEquals(3000L,evalL(ctx, "(asset/balance FOOCOIN box)"));
		
		// removing too much should fail
		assertError(step(ctx,"(box/remove "+b1+" [FOOCOIN 1001])"));
		
		// remove a reasonable amount (500) from b1
		ctx=step(ctx,"(box/remove "+b1+" [FOOCOIN 500])");
		assertEquals(2500L,evalL(ctx, "(asset/balance FOOCOIN box)"));
		assertEquals(997500L,evalL(ctx, "(asset/balance FOOCOIN *address*)"));
		
		// removing more than remaining amount in b1 should fail
		assertError(step(ctx,"(box/remove "+b1+" [FOOCOIN 501])"));

	}
	
	
	@SuppressWarnings("unchecked")
	@Test public void testAssetAPI() {
		Context<?> ctx=CTX.fork();
		ctx=step(ctx,"(def total (map (fn [v] (call box (create-box))) [1 2 3 4]))");
		AVector<CVMLong> v=(AVector<CVMLong>) ctx.getResult();
		assertEquals(4,v.count());
		CVMLong b1=v.get(0);
		
		// Test balance
		assertEquals(Sets.of(v.toCellArray()),eval(ctx,"(asset/balance box)"));
		
		// Create test Users
		ctx=ctx.createAccount(KP1.getAccountKey());
		Address user1=(Address) ctx.getResult();
		ctx=ctx.createAccount(KP2.getAccountKey());
		Address user2=(Address) ctx.getResult();
		
		ctx=step(ctx,"(asset/transfer "+user1+" [box (set (next total))])");
		ctx=step(ctx,"(asset/transfer "+user2+" [box #{"+b1+"}])");
		assertEquals(Sets.of(b1),ctx.getResult());
		
		AssetTest.doAssetTests(ctx, BOX, user1, user2);

	}
}
