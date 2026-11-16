package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.data.prim.CVMLong;
import convex.peer.TestNetwork;

/**
 * Transactions refused for want of a usable origin account, and where each is refused.
 */
@Execution(ExecutionMode.CONCURRENT)
public class ConvexTest {

	/** A client's account key is its key pair's, and it has none without a key pair. */
	@Test
	public void testAccountKeyFollowsKeyPair() throws Exception {
		try (ConvexRemote convex = new ConvexRemote(null, null)) {
			assertNull(convex.getAccountKey());
			AKeyPair kp = AKeyPair.generate();
			convex.setKeyPair(kp);
			assertEquals(kp.getAccountKey(), convex.getAccountKey());
		}
	}

	/** A client without an address still queries, but cannot build a transaction, which needs an origin. */
	@Test
	public void testNullAddress() throws Exception {
		TestNetwork network = TestNetwork.getInstance();
		try (ConvexRemote convex = Convex.connect(network.SERVER.getHostAddress(), null, AKeyPair.generate())) {
			assertEquals(CVMLong.create(2), convex.querySync("(+ 1 1)").getValue());
			assertThrows(IllegalArgumentException.class, () -> convex.transactSync("(+ 1 2)"));
		}
	}

	/** An account without a key, such as #0, cannot originate a transaction: :STATE from the peer. */
	@Test
	public void testAccountWithoutKey() throws Exception {
		TestNetwork network = TestNetwork.getInstance();
		try (ConvexRemote convex = Convex.connect(network.SERVER.getHostAddress(), Address.ZERO, AKeyPair.generate())) {
			Result r = convex.transactSync("(+ 1 2)");
			assertEquals(ErrorCodes.STATE, r.getErrorCode(), r::toString);
			assertEquals(SourceCodes.PEER, r.getSource());
		}
	}

	/** A transaction signed with a key other than its account's: :SIGNATURE from the peer. */
	@Test
	public void testWrongKeyPair() throws Exception {
		TestNetwork network = TestNetwork.getInstance();
		// An account of its own, so no other transaction moves its sequence, which the peer checks first
		try (ConvexRemote convex = network.getClient()) {
			convex.setKeyPair(AKeyPair.generate());
			Result r = convex.transactSync("(+ 1 2)");
			assertEquals(ErrorCodes.SIGNATURE, r.getErrorCode(), r::toString);
			assertEquals(SourceCodes.PEER, r.getSource());
		}
	}
}
