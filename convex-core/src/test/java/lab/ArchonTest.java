package lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Address;
import convex.core.data.Strings;
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
		
		// User accounts for testing
		ctx=exec(ctx,"(def HERO *address*)");
		ctx=exec(ctx,"(def VILLAIN "+VILLAIN+")");
		
		ctx=exec(ctx,"(def archon (deploy '(set-controller *caller*)))");
		ARCHON=ctx.getResult();
		
		try {
			String code=Utils.readResourceAsString("/convex/lab/archon.cvx");
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
		
		// Metadata should work and have an image
		AHashMap<AString,ACell> meta=eval(ctx,"(call archon (get-metadata 0x0123))");
		assertFalse(meta.isEmpty());
		assertTrue(meta.containsKey(Strings.create("image")));
		
		assertNull(eval(ctx,"(call archon (get-metadata :not-a-valid-id))"));
	}
	
	@Test public void testArchonNFTs() {
		Context ctx=context();
		ctx=exec(ctx,"(asset/transfer "+VILLAIN+" [archon #{0x0123}])");
		AssetTester.doAssetTests(ctx, ARCHON, HERO, VILLAIN);
	}
}
