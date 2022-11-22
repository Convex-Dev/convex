package convex.lib;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.lang.ACVMTest;
import convex.core.lang.Context;

public class DIDTest extends ACVMTest {

	@Test public void testLibrary() {
		Address did=eval("(import convex.did)");
		assertNotNull(did);
	}
	
	@Test public void testResolve() {
		Context<ACell> ctx=step("(import convex.did :as did)");
		assertNull(eval(ctx,"(did/resolve *address*)"));
	}
}
