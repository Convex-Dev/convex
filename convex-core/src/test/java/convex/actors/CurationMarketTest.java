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
		try {
			ctx=step(ctx,"(import convex.asset :as asset)");
			ctx=step(ctx,"(import convex.trust :as trust)");
			ctx=step(ctx,"(import asset.curation-market :as cm)");
			assertNotError(ctx);
			
			return ctx;
		} catch (Throwable t) {
			throw Utils.sneakyThrow(t);
		}	
	}
	
	@Test public void testCreate() {
		
	}

}
