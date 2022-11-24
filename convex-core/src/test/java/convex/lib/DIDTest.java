package convex.lib;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class DIDTest extends ACVMTest {

	@Test public void testLibrary() {
		Address did=eval("(import convex.did)");
		assertNotNull(did);
	}
	
	@Test public void testResolveNotThere() {
		Context<ACell> ctx=step("(import convex.did :as did)");
		assertNull(eval(ctx,"(did/resolve 5875875865)"));
	}
	
	@Test public void testCreate() {
		Context<ACell> ctx=step("(import convex.did :as did)");
		
		// create an id, should be entered in registry
		ctx=step(ctx,"(did/create *address*)");
		CVMLong id=(CVMLong) ctx.getResult();
		assertNotNull(id);
		assertTrue(evalB(ctx,"(contains-key? did/dids "+id+")"));
		
		// check another DID created is different
		assertNotEquals(id,eval(ctx,"(did/create)"));
		
		ctx=step(ctx,"(did/resolve "+id.longValue()+")");
		assertSame(Strings.EMPTY,ctx.getResult()); // should be initially empty
		
	}

	private void assertNotEquals(CVMLong id, ACell eval) {
		// TODO Auto-generated method stub
		
	}
}
