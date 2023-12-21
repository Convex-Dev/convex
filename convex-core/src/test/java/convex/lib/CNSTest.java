package convex.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.init.Init;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;

public class CNSTest extends ACVMTest {
		
		Address REG=Init.REGISTRY_ADDRESS;
		
		@Override protected Context buildContext(Context ctx) {
			ctx=TestState.CONTEXT.fork();
			
			ctx=step(ctx,"(import convex.asset :as asset)");
			ctx=step(ctx,"(import convex.trust :as trust)");
			return ctx;
		}
		
		@Test public void testspecial() {
			assertEquals(REG,eval("*registry*"));
		}

		@Test public void testInit() {
			Address init=eval("(*registry*/resolve 'init)");
			assertEquals(Init.INIT_ADDRESS,init);
			
			assertEquals(eval("[#1 #1 nil]"), eval("(*registry*/read 'init)"));
		}
		


	}
