package convex.lib;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.data.Address;
import convex.core.lang.ACVMTest;

public class DIDTest extends ACVMTest {

	@Test public void testLibrary() {
		Address did=eval("(import convex.did)");
		assertNotNull(did);
	}
}
