package convex.actors;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;

public class CurationMarketTest extends ACVMTest {
	
	protected CurationMarketTest() {
		super(InitTest.STATE);
	}
	
	@Override protected Context buildContext(Context ctx) {
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(import asset.curation-market :as cm)");
			
		return ctx;
	}
	
	@Test public void testCreate() {
		
	}

}
