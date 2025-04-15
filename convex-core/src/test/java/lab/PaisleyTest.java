package lab;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.lib.AssetTester;

public class PaisleyTest extends ACVMTest  {

	Address PAI;
	Address PERSONAL;
	
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
		
		c=exec(c,"(def id (call [personal id] (mint 1000000)))");
		
		AssetTester.doFungibleTests(c, AID, c.getAddress());
	}
}
