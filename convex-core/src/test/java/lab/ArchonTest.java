package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;
import convex.core.util.Utils;
import convex.lib.AssetTester;

public class ArchonTest extends ACVMTest  {

	Address ARCHON;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		
		ctx=exec(ctx,"(def archon (deploy '(set-controller *caller*) *address*))");
		ARCHON=ctx.getResult();
		
		try {
			String code=Utils.readResourceAsString("lab/archon.cvx");
			ctx=exec(ctx,"(eval-as archon '(do "+code+"))");
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new Error(e);
		}
		return ctx;
	}
	
	@Test public void testArchonActor() {
		Context ctx=context();
		Address archon=eval(ctx,"archon");
		assertEquals(ARCHON,archon);
		
		assertEquals(1024,evalL(ctx,"(count (asset/balance archon))"));
	}
	
	@Test public void testArchonNFTs() {
		Context ctx=context();
		ctx=exec(ctx,"(asset/transfer "+VILLAIN+" [archon #{0x0123}])");
		AssetTester.doAssetTests(ctx, ARCHON, HERO, VILLAIN);
	}
}
