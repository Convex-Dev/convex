package convex.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;
import convex.core.lang.RT;
import convex.test.Assertions;

public class DIDTest extends ACVMTest {

	@Test public void testLibrary() {
		Address did=eval("(import convex.did)");
		assertNotNull(did);
	}
	
	@Test public void testResolveNotThere() {
		Context<ACell> ctx=step("(import convex.did :as did)");
		assertNull(eval(ctx,"(call did (read 5875875865))"));
	}
	
	@Test public void testCreate() {
		Context<ACell> ctx=step("(import convex.did :as did)");
		
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
		
	}
	
	@Test public void testUpdateMonitor() {
		Context<ACell> ctx=step("(import convex.did :as did)");
		
		// Set up DDO controlled by HERO
		ctx=step(ctx,"(call did (create))");
		CVMLong id=(CVMLong) ctx.getResult();
		AString ddo=Strings.create("{}");
		ctx=step(ctx,"(call did (update "+id+" "+RT.print(ddo)+"))");
		
		// Switch to VILLAIN
		ctx=ctx.forkWithAddress(VILLAIN);
		ctx=step(ctx,"(import convex.did :as did)");
		
		// Attempt to change DDO
		ctx=step(ctx,"(call did (update "+id+" \"PWND\"))");
		Assertions.assertError(ctx);
		
		// Original DDO should be unchanged
		ctx=step(ctx,"(call did (read "+id+"))");
		assertEquals(ddo,ctx.getResult()); 
	}
	
	@Test public void testAuthorisedMonitor() {
		Context<ACell> ctx=context();
		ctx=step(ctx,"(import convex.did :as did)");
		ctx=step(ctx,"(import convex.trust :as trust)");
		
		// Set up DDO controlled by HERO
		ctx=step(ctx,"(def id (call did (create)))");
		
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
		Context<ACell> ctx=step("(import convex.did :as did)");
		
		// Set up DDO controlled by HERO
		ctx=step(ctx,"(call did (create))");
		CVMLong id=(CVMLong) ctx.getResult();
		AString ddo=Strings.create("{}");
		ctx=step(ctx,"(call did (update "+id+" "+RT.print(ddo)+"))");
		
		// DDO should exist
		assertNotNull(eval(ctx,"(call did (read "+id+"))"));
		
		// Deactivate
		ctx=step(ctx,"(call did (deactivate "+id+"))");
		
		// Original DDO should be unchanged
		ctx=step(ctx,"(call did (read "+id+"))");
		assertNull(ctx.getResult()); 
	}


}
