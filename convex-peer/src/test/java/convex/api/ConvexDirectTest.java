package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;

/**
 * Tests for Convex Direct client
 */
public class ConvexDirectTest {
	static final AKeyPair peerKey=AKeyPair.createSeeded(5675675);

	@Test public void testSetup() throws InterruptedException {
		State state=Init.createTestState(List.of(peerKey.getAccountKey()));
		ConvexDirect convex=ConvexDirect.create(peerKey,state);
		Address addr=convex.getAddress();
		
		assertTrue(convex.isConnected());
		assertEquals(Init.GENESIS_PEER_ADDRESS,addr);
		
		assertEquals(addr,convex.query("*address*").join().getValue());
		
		Result r=convex.transactSync("(+ 1 2)");
		assertFalse(r.isError(),()->"Expected no error but got: "+r);
		assertEquals(CVMLong.create(3),r.getValue());
	}
}
