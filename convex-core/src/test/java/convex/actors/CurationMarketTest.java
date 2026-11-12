package convex.actors;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.init.InitTest;
import convex.core.lang.ACVMTest;

public class CurationMarketTest extends ACVMTest {
	
	protected CurationMarketTest() {
		super(InitTest.UPGRADED);
	}
	
	@Override protected Context buildContext(Context ctx) {
		ctx=exec(ctx,"(import convex.asset :as asset)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(import asset.curation-market :as cm)");
			
		return ctx;
	}
	
	@Test public void testImport() {
		assertNotNull(evalA("cm"));
	}

}
