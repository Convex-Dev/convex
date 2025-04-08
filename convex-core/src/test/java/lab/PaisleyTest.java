package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.ACVMTest;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.lib.AssetTester;

public class PaisleyTest extends ACVMTest  {

	Address PAI;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(import convex.fungible :as fun)");
		
		// User accounts for testing
		ctx=exec(ctx,"(def HERO *address*)");
		ctx=exec(ctx,"(def VILLAIN "+VILLAIN+")");
		
		ctx=exec(ctx,"(def pai (deploy '(set-controller *caller*)))");
		PAI=ctx.getResult();
		
		try {
			String code=Utils.readResourceAsString("/app/paisley/pai.cvx");
			ctx=exec(ctx,"(eval-as pai '(do "+code+"))");
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new Error(e);
		}
		return ctx;
	}
	
	@Test public void testPAIToken() {
	}
}
