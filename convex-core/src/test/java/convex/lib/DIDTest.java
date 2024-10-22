package convex.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.RT;
import convex.core.lang.TestState;
import convex.test.Assertions;

public class DIDTest extends ACVMTest {
	
	Address DID;
	
	@Override protected Context buildContext(Context ctx) {
		ctx=TestState.CONTEXT.fork();
		
		// Import basic NFTs
		ctx=step(ctx,"(import convex.did :as did)");
		DID=ctx.getResult();
		
		ctx=step(ctx,"(import convex.asset :as asset)");
		ctx=step(ctx,"(import convex.trust :as trust)");
		return ctx;
	}

	@Test public void testLibrary() {
		Address did=eval("(import convex.did)");
		assertNotNull(did);
		assertEquals(DID,did);
	}
	
	@Test public void testResolveNotThere() {
		Context ctx=context();
		assertNull(eval(ctx,"(call did (read 5875875865))"));
	}
	
	@Test public void testCreate() {
		Context ctx=context();
		Address did=DID;
		
		// create an id, should be entered in registry
		ctx=step(ctx,"(call did (create *address*))");
		CVMLong id=(CVMLong) ctx.getResult();
		assertNotNull(id);
		ACell dids=eval(ctx,"did/dids");
		assertTrue(dids instanceof AMap);
		assertTrue(evalB(ctx,"(contains-key? did/dids "+id+")"));
		
		// check another DID created is different
		assertNotEquals(id,eval(ctx,"(call did (create))"));
		
		// should be initially empty
		ctx=step(ctx,"(call did (read "+id+"))");
		assertSame(Strings.EMPTY,ctx.getResult()); 
		
		// should be initially empty
		AString ddo=Strings.create("{}");
		ctx=step(ctx,"(call did (update "+id+" "+RT.print(ddo)+"))");
		assertTrue(ctx.getResult() instanceof AVector);
		ctx=step(ctx,"(call did (read "+id+"))");
		assertEquals(ddo,ctx.getResult()); 
		
		// Try change of Control with scoped DID
		TrustTest.testChangeControl(ctx,Vectors.of(did,id));
		
	}
	
	@Test public void testAuthorise() {
		Context ctx=context();
		
		// Set up DDO controlled by HERO (current *address*)
		ctx=step(ctx,"(def id (call did (create)))");
		
		// Not initially trusted
		assertFalse(evalB(ctx,"(trust/trusted? [did id] *address*)"));

		// Authorise
		ctx=step(ctx,"(call did (authorise id #{*address*}))");
		assertTrue(evalB(ctx,"(trust/trusted? [did id] *address*)"));
		
		// Basic trust failures
		assertFalse(evalB(ctx,"(trust/trusted? [did id] nil)"));
		assertFalse(evalB(ctx,"(trust/trusted? [did id] #6666)"));
		
		// Remove authorisation
		ctx=step(ctx,"(call did (authorise id #{}))");
		assertFalse(evalB(ctx,"(trust/trusted? [did id] *address*)"));

	}

	
	@Test public void testUpdateMonitor() {
		Context ctx=context();
		
		// Set up DDO controlled by HERO (current *address*)
		ctx=exec(ctx,"(def id (call did (create)))");
		CVMLong id=(CVMLong) ctx.getResult();
		AString ddo=Strings.create("{}");
		ctx=exec(ctx,"(call did (update "+id+" "+RT.print(ddo)+"))");
		

		// Switch to VILLAIN
		ctx=ctx.forkWithAddress(VILLAIN);
		ctx=exec(ctx,"(import convex.did :as did)");
		
		// Attempt to change DDO
		ctx=step(ctx,"(call did (update "+id+" \"PWND\"))");
		Assertions.assertError(ctx);
		
		// Original DDO should be unchanged
		ctx=exec(ctx,"(call did (read "+id+"))");
		assertEquals(ddo,ctx.getResult()); 
	}
	
	@Test public void testAuthorisedMonitor() {
		Context ctx=context();
		ctx=exec(ctx,"(import convex.did :as did)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		
		// Set up DDO controlled by HERO
		ctx=exec(ctx,"(def id (call did (create)))");
		
		// Nobody is initially trusted
		assertFalse(evalB(ctx,"(trust/trusted? [did id] *address*)"));
		
		// Add HERO address to authorised set
		ctx=step(ctx,"(call [did id] (authorise #{*address*}))");
		AVector<?> v=(AVector<?>) ctx.getResult();
		assertNotNull(v.get(4));

		// Check we are now fully authorised
		assertTrue(evalB(ctx,"(trust/trusted? did *address* nil id)"));
		assertTrue(evalB(ctx,"(trust/trusted? [did id] *address*)"));
		
		// Clear authorised set
		ctx=step(ctx,"(call did (authorise id nil))");

		// Check authorisation is revoked
		assertFalse(evalB(ctx,"(trust/trusted? [did id] *address*)"));

	}
	
	@Test public void testDeactivate() {
		Context ctx=step("(import convex.did :as did)");
		ctx=exec(ctx,"(import convex.trust :as trust)");
		
		// Set up DDO controlled by HERO
		ctx=exec(ctx,"(def id (call did (create)))");
		CVMLong id=(CVMLong) ctx.getResult();
		AString ddo=Strings.create("{}");
		ctx=step(ctx,"(call did (update id "+RT.print(ddo)+"))");
		
		// DDO should exist and be equal to value set
		assertEquals(ddo,eval(ctx,"(call did (read "+id+"))"));
		
		// Authorise an account
		ctx=step(ctx,"(call did (authorise id #{*address*}))");
		assertTrue(evalB(ctx,"(trust/trusted? [did id] *address*)"));
		
		// Deactivate
		ctx=step(ctx,"(call did (deactivate "+id+"))");
		
		assertFalse(evalB(ctx,"(trust/trusted? [did id] *address*)"));

		// DDO should be cleared
		ctx=step(ctx,"(call did (read "+id+"))");
		assertNull(ctx.getResult()); 
	}
	


}
