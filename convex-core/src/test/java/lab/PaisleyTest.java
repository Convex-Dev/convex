package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.lib.AssetTester;
import static convex.test.Assertions.*;

public class PaisleyTest extends ACVMTest  {

	Address PAI;
	Address PERSONAL;
	Address MEMBERS;
	
	@Override protected Context buildContext(Context ctx) throws IOException {
		ctx=TestState.CONTEXT.fork();
		
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(import convex.fungible :as fun)");
		
		// User accounts for testing
		ctx=exec(ctx,"(def HERO *address*)");
		ctx=exec(ctx,"(def VILLAIN "+VILLAIN+")");
		
		// Deploy an account with a pre-set controller for PAI
		ctx=exec(ctx,"(def pai (deploy '(set-controller *caller*)))");
		PAI=ctx.getResult();
		
		// Execute token setup code in PAI account
		String code=Utils.readResourceAsString("/app/paisley/pai.cvx");
		ctx=exec(ctx,"(eval-as pai '(do "+code+"))");

		// Deploy an account with a pre-set controller for Personal tokens
		ctx=exec(ctx,"(def personal (deploy '(set-controller *caller*)))");
		PERSONAL=ctx.getResult();

		// Execute token setup code in PAI account
		String pcode=Utils.readResourceAsString("/app/paisley/personal.cvx");
		ctx=exec(ctx,"(eval-as personal '(do "+pcode+"))");

		// Deploy an account with a pre-set controller for Membership actor
		ctx=exec(ctx,"(def members (deploy '(set-controller *caller*)))");
		MEMBERS=ctx.getResult();
		
		// Execute member setup code in PAI account
		String memberscode=Utils.readResourceAsString("/app/paisley/members.cvx");
		ctx=exec(ctx,"(eval-as members '(def pt-actor "+PERSONAL+"))");
		ctx=exec(ctx,"(eval-as members '(def operator *caller*))"); // use HERO account as operator
		ctx=exec(ctx,"(eval-as members '(do "+memberscode+"))");

		
		return ctx;
	}
	
	@Test public void testPAIToken() {
		Context c=context();
		AssetTester.doFungibleTests(c, PAI, c.getAddress());
	}
	
	@Test public void testPersonalToken() {
		Context c=context();
		c=exec(c,"(def id (call personal (create)))");
		CVMLong ID=c.getResult();
		AVector<?> AID=Vectors.of(PERSONAL,ID);
		c=exec(c,"(def aid [personal id])");
		
		c=exec(c,"(call aid (mint 1000000))");
		assertEquals(1000000,evalL(c,"(asset/balance aid)")); // minted quantity
		assertEquals(0,evalL(c,"(asset/balance aid #0)")); // zero account has no holding
		
		AssetTester.doFungibleTests(c, AID, c.getAddress());
	}
	
	@Test public void testMembersList() {
		Context c=context();
		c=exec(c,"members/members");
		assertSame(Maps.empty(),c.getResult());
		
		{
			// Bad token create (non-member)
			Context ce=step("(call members 1000000000 (create-pt -1))");
			assertStateError(ce);
		}
		
		// Create a new member
		c=exec(c,"(def mid (call members (create-member)))");
		assertNotError(c);
		AInteger MID=c.getResult();
		assertNotNull(eval(c,"(call members (get-metadata "+MID+"))"));
		
		{
			// Bad token create (insufficient offer)
			Context ce=step(c,"(call members 999999999 (create-pt "+MID+"))");
			assertStateError(ce);
		}

	}
}
