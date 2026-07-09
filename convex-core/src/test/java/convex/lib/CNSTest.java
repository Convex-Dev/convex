package convex.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AVector;
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
	
	/**
	 * CAD014 authority model: record control and node ownership are separate
	 * capabilities, by design. Transferring a record with `control` deliberately
	 * does NOT transfer ownership of the associated child node.
	 */
	@Test public void testDelegatedControlTransfer() {
		Context ctx=context().forkWithAddress(Init.GOVERNANCE_ADDRESS);
		ctx=exec(ctx,"(import convex.trust :as trust)");
		ctx=exec(ctx,"(*registry*/create 'dtest.bar #17)");

		// Transfer record controller of 'dtest to HERO. Node ["dtest"] remains owned by governance.
		ctx=exec(ctx,"(*registry*/control 'dtest "+HERO+")");

		{ // HERO now controls the 'dtest record: can update its value
			Context c=ctx.forkWithAddress(HERO);
			c=exec(c,"(*registry*/update 'dtest #42)");
			assertEquals(Address.create(42),eval(c,"(*registry*/resolve 'dtest)"));

			// HERO can create records in the node. NOTE: this currently checks the
			// parent record's controller; CAD014 specifies the node owner (open issue 1).
			c=exec(c,"(*registry*/create 'dtest.baz #18)");
			assertEquals(Address.create(18),eval(c,"(*registry*/resolve 'dtest.baz)"));

			// HERO does not own node ["dtest"], so cannot create deeper paths
			// (which require creating a child node)
			assertTrustError(step(c,"(*registry*/create 'dtest.deep.q #19)"));
		}

		{ // Governance still owns node ["dtest"]: retains revocation (delete) rights
		  // over records in the node, even though HERO controls the parent record
			Context c=ctx.forkWithAddress(Init.GOVERNANCE_ADDRESS);
			c=exec(c,"(call [*registry* [\"dtest\"]] (cns-delete-node \"bar\" nil))");
			assertNull(eval(c,"(*registry*/resolve 'dtest.bar)"));
		}

		{ // Full handover of a name and its subtree = two capability transfers:
		  // record control (above) plus node ownership via trust/change-control
			Context c=exec(ctx,"(trust/change-control [*registry* [\"dtest\"]] "+HERO+")");
			c=c.forkWithAddress(HERO);
			c=exec(c,"(*registry*/create 'dtest.deep.q #19)");
			assertEquals(Address.create(19),eval(c,"(*registry*/resolve 'dtest.deep.q)"));
		}
	}

	/**
	 * CAD014: deleting a node entry does not recursively delete descendant nodes,
	 * since subtrees are independently owned and may be shared.
	 */
	@Test public void testNodeDeletionOrphans() {
		Context ctx=context().forkWithAddress(Init.GOVERNANCE_ADDRESS);
		ctx=exec(ctx,"(*registry*/create 'otest.a.b.c #17)");
		assertNotNull(eval(ctx,"(get *registry*/cns-database [\"otest\" \"a\" \"b\"])"));

		ctx=exec(ctx,"(call [*registry* [\"otest\"]] (cns-delete-node \"a\" nil))");
		assertNull(eval(ctx,"(*registry*/resolve 'otest.a.b.c)"));

		// Descendant node survives, no longer reachable via path resolution
		assertNotNull(eval(ctx,"(get *registry*/cns-database [\"otest\" \"a\" \"b\"])"));

		// CAD014 open issue 2: nobody can delete the orphaned node — deletion
		// requires trust from the owner of the (now deleted) parent node
		assertTrustError(step(ctx,"(call [*registry* [\"otest\" \"a\"]] (cns-delete-node \"b\" nil))"));
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
