package lab;

import static convex.test.Assertions.assertCVMEquals;
import static convex.test.Assertions.assertNotError;
import static convex.test.Assertions.assertStateError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.lib.AssetTester;

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
	
	/**
	 * This test highlights use of a personal token *without* using the members list
	 */
	@Test public void testPersonalTokenDetached() {
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
	
	/**
	 * This test highlights use of a personal token using the members list
	 */
	@Test public void testMemberPersonalToken() {
		Context c=context();
		
		// Create a member Convex account
		c=exec(c,"(def member-acct (deploy '(set-controller *caller*)))"); 
		Address memberID=c.getResult();
		assertNotNull(memberID);
		
		// Create a member entry
		c=exec(c,"(def mid (call members (create-member member-acct)))");

		// Create a member personal token (remembering 1 CVM offer)
		c=exec(c,"(def aid (call members 1000000000 (create-pt mid)))");
		AVector<?> AID=c.getResult(); // should be asset ID
		
		// user should be able to mint now
		c=exec(c,"(eval-as member-acct `(call ~aid (mint 1000000)))");
		
		// user should be able to transfer to HERO
		c=exec(c,"(eval-as member-acct `(@convex.asset/transfer ~*address* [~aid 10000]))");
		
		// Run generic tests (as HERO who should now have 10000 units)
		AssetTester.doFungibleTests(c, AID, c.getAddress());
	}
	
	
	
	@Test public void testMembersList() {
		Context c=context();
		
		// Check members database is initially empty
		c=exec(c,"members/members");
		assertSame(Maps.empty(),c.getResult());
		assertCVMEquals(0,eval(c,"members/mcount"));

		
		{
			// Bad token create (non-member)
			Context ce=step("(call members 1000000000 (create-pt -1))");
			assertStateError(ce);
		}
		
		// Create a new member
		c=exec(c,"(def mid (call members (create-member *address*)))");
		assertNotError(c);
		AInteger MID=c.getResult();
		assertNotNull(eval(c,"(call members (get-metadata "+MID+"))"));
		
		{
			// Bad token create (insufficient offer)
			Context ce=step(c,"(call members 999999999 (create-pt "+MID+"))");
			assertStateError(ce);
		}

		// Example using [members-actor id] as a trust monitor 
		assertEquals(CVMBool.TRUE,eval(c,"(trust/trusted? [members mid] *address*)"));
		
		
		// Example setting and reading metadata
		c=exec(c,"(call members (update-member mid {:foo 34}))");
		AMap<?,?> rmd=eval(c,"(call members (get-metadata mid))");
		assertCVMEquals(34,rmd.get(Keywords.FOO));
	}
}
