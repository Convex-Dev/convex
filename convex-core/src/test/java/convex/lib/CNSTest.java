package convex.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.init.Init;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.TestState;

import static convex.test.Assertions.*;

public class CNSTest extends ACVMTest {
		
		Address REG=Init.REGISTRY_ADDRESS;
		
		@Override protected Context buildContext(Context ctx) {
			ctx=TestState.CONTEXT.fork();
			
			ctx=step(ctx,"(import convex.asset :as asset)");
			ctx=step(ctx,"(import convex.trust :as trust)");
			return ctx;
		}
		
		@Test public void testSpecial() {
			assertEquals(REG,eval("*registry*"));
		}

		@Test public void testInit() {
			Address init=eval("(*registry*/resolve 'init)");
			assertEquals(Init.INIT_ADDRESS,init);
			
			assertEquals(eval("[#1 #1 nil]"), eval("(*registry*/read 'init)"));
		}
		

		@Test public void testCreateTopLevel() {
			// HERO shouldn't be able to create a top level CNS entry
			assertTrustError(step("(*registry*/create 'foo)"));
			
			// INIT should be able to create a top level CNS entry
			Context ictx=context().forkWithAddress(Init.INIT_ADDRESS);
			ictx=(step(ictx,"(*registry*/create 'foo #17)"));
			assertNotError(ictx);
			
			// System.out.println(eval(ictx,"*registry*/cns-database"));
			
			assertEquals(Address.create(17),eval(ictx,"(*registry*/resolve 'foo)"));
			
			ictx=(step(ictx,"(*registry*/create 'foo #666)"));
			assertEquals(Address.create(666),eval(ictx,"(*registry*/resolve 'foo)"));

			// HERO still shouldn't be able to update a top level CNS entry
			ictx=ictx.forkWithAddress(HERO);
			assertTrustError(step(ictx,"(*registry*/create 'foo *address* *address* {})"));

		}

	}
