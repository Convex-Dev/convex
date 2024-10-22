package convex.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keywords;
import convex.core.init.Init;
import convex.core.lang.ACVMTest;
import convex.core.lang.Reader;
import convex.core.lang.TestState;

import static convex.test.Assertions.*;

public class CNSTest extends ACVMTest {
	
	Address REG=Init.REGISTRY_ADDRESS;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		
		ctx=step(ctx,"(import convex.asset :as asset)");
		ctx=step(ctx,"(import convex.trust :as trust)");
		ctx=step(ctx,"(def cns #9)");
		return ctx;
	}
	
	@Test public void testConstantSetup() {
		assertEquals(Init.REGISTRY_ADDRESS,eval("*registry*"));
		assertEquals(Init.REGISTRY_ADDRESS,eval("cns"));
		assertEquals(Init.REGISTRY_ADDRESS,eval("@convex.registry"));
		assertEquals(Init.REGISTRY_ADDRESS,eval("(call cns (resolve 'convex.registry))"));
		
		// TODO: fix this
		// assertEquals(Init.REGISTRY_ADDRESS,eval("@cns"));
	}
	
	@Test public void testSpecial() {
		Context ctx=context();
		assertEquals(REG,eval(ctx,"*registry*"));
		// assertEquals(REG,eval(ctx,"cns"));
	}
	
	@Test public void testTrust() {
		// Root CNS node should only trust governance account
		assertFalse(evalB("(trust/trusted? [cns []] *address*)"));
		assertTrue(evalB("(query-as #6 `(~trust/trusted? [~cns []] *address*))"));
	}
	
	@Test public void testInit() {
		Address init=eval("(*registry*/resolve 'init)");
		assertEquals(Init.INIT_ADDRESS,init);
		
		ACell INIT_REC=Reader.read("[#1 #1 nil nil]");
		assertEquals(INIT_REC, eval("(*registry*/read 'init)"));
	}
	
	@Test public void testCreateNestedFromTop() {
		Context ctx=context().forkWithAddress(Init.GOVERNANCE_ADDRESS);
		ctx=(step(ctx,"(*registry*/create 'foo.bar.bax #17)"));
		assertNotError(ctx);
		
		assertEquals(Address.create(17),eval(ctx,"(*registry*/resolve 'foo.bar.bax)"));
		assertNull(eval(ctx,"(*registry*/resolve 'foo.null.boo)"));
	}
	
	@Test public void testCreate() {
		Context ctx=context();
		assertArityError(step(ctx,"(cns/create)"));
		assertArityError(step(ctx,"(cns/create 'foo.bar #1 #2 #3 #4 #5)"));
		assertArgumentError(step(ctx,"(cns/create :foo.bar #1 #2 #3 #4)"));
		
		// can't create / update root namespaces!
		assertTrustError(step(ctx,"(cns/create 'foo #1 #2 #3 #4 )"));
		assertTrustError(step(ctx,"(cns/create 'convex.foo #1 #2 #3 #4 )"));
	}
	
//	@Test public void testDelete() {
//		Context ctx=context();
//		assertArityError(step(ctx,"(cns/delete 'foo.bar :baz)"));
//		assertArityError(step(ctx,"(cns/delete)"));
//
//		// can't delete root namespaces!
//		assertTrustError(step(ctx,"(cns/delete 'convex)"));
//		assertTrustError(step(ctx,"(cns/delete 'convex.core)"));
//	}
	
	/**
	 * What happens if we insert a bad CNS node that crashes?
	 */
	@Test public void testBadNode() {
		Context ctx=context().forkWithAddress(Init.GOVERNANCE_ADDRESS);
		ctx=exec(ctx,"(*registry*/create 'foo :foo :BROKEN nil :BAD)");
		
		assertEquals(Keywords.FOO,eval(ctx,"@foo"));
		
		// TODO: is this the right error type?
		assertCastError(step(ctx,"@foo.bar"));
	}
	
	@Test public void testCreateTopLevel() {
		// HERO shouldn't be able to create a top level CNS entry
		assertTrustError(step("(*registry*/create 'foo)"));
		
		// NEed governance address to be able to create a top level CNS entry
		Context ctx=context().forkWithAddress(Init.GOVERNANCE_ADDRESS);
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(*registry*/create 'foo #17)");
		ctx=exec(ctx,"(def ref [*registry* [\"foo\"]])");
		AVector<?> ref=ctx.getResult();
		assertNotNull(ref);
		
		// System.out.println(eval(ictx,"*registry*/cns-database"));
		
		assertEquals(Address.create(17),eval(ctx,"(*registry*/resolve 'foo)"));
		
		ctx=exec(ctx,"(*registry*/create 'foo #666)");
		assertEquals(Address.create(666),eval(ctx,"(*registry*/resolve 'foo)"));

		// HERO still shouldn't be able to update a top level CNS entry
		ctx=ctx.forkWithAddress(HERO);
		assertTrustError(step(ctx,"(*registry*/create 'foo *address* *address* {})"));
		assertTrustError(step(ctx,"(trust/change-control "+ref+" *address*)"));

	}

}
