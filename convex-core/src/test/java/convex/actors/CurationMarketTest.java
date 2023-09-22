package convex.actors;

import org.junit.jupiter.api.Test;

import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.util.Utils;

import static convex.test.Assertions.*;

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
