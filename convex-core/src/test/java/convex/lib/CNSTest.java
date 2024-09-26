package convex.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.data.AVector;
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
		// ctx=step(ctx,"(import convex.cns :as cns)");
		return ctx;
	}
	
	@Test public void testSpecial() {
		Context ctx=context();
		assertEquals(REG,eval(ctx,"*registry*"));
		// assertEquals(REG,eval(ctx,"cns"));
	}

	@Test public void testInit() {
		Address init=eval("(*registry*/resolve 'init)");
		assertEquals(Init.INIT_ADDRESS,init);
		
		assertEquals(eval("[#1 #1 nil nil]"), eval("(*registry*/read 'init)"));
	}
	
	@Test public void testCreateNestedFromTop() {
		Context ctx=context().forkWithAddress(Init.INIT_ADDRESS);
		ctx=(step(ctx,"(*registry*/create 'foo.bar.bax #17)"));
		assertNotError(ctx);
		
		assertEquals(Address.create(17),eval(ctx,"(*registry*/resolve 'foo.bar.bax)"));
		assertNull(eval(ctx,"(*registry*/resolve 'foo.null.boo)"));
	}
	
	@Test public void testCreateTopLevel() {
		// HERO shouldn't be able to create a top level CNS entry
		assertTrustError(step("(*registry*/create 'foo)"));
		
		// INIT should be able to create a top level CNS entry
		Context ictx=context().forkWithAddress(Init.INIT_ADDRESS);
		ictx=step(ictx,"(import convex.trust :as trust)");
		ictx=(step(ictx,"(*registry*/create 'foo #17)"));
		assertNotError(ictx);
		ictx=step(ictx,"(def ref [*registry* [\"foo\"]])");
		AVector<?> ref=ictx.getResult();
		assertNotNull(ref);
		
		// System.out.println(eval(ictx,"*registry*/cns-database"));
		
		assertEquals(Address.create(17),eval(ictx,"(*registry*/resolve 'foo)"));
		
		ictx=(step(ictx,"(*registry*/create 'foo #666)"));
		assertEquals(Address.create(666),eval(ictx,"(*registry*/resolve 'foo)"));

		// HERO still shouldn't be able to update a top level CNS entry
		ictx=ictx.forkWithAddress(HERO);
		assertTrustError(step(ictx,"(*registry*/create 'foo *address* *address* {})"));
		assertTrustError(step(ictx,"(trust/change-control "+ref+" *address*)"));

	}

}
