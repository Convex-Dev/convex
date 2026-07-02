package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.UpgradeError;
import convex.core.init.Init;

/**
 * Tests the peer-level response to a required upgrade this release cannot apply:
 * a full consensus freeze that leaves the server alive. See UPGRADE.md.
 *
 * These launch an isolated single-peer network (never the shared TestNetwork,
 * since halting the peer would break other tests).
 */
public class UpgradeWithdrawalTest {

	static final AKeyPair PEER_KP = AKeyPair.createSeeded(101);
	static final Address GENESIS = Address.create(Init.GENESIS_ADDRESS);

	private Server launchIsolatedPeer() throws Exception {
		State genesis = Init.createState(List.of(PEER_KP.getAccountKey()));
		List<Server> servers = API.launchLocalPeers(List.of(PEER_KP), genesis);
		return servers.get(0);
	}

	@Test
	public void testHaltConsensusFlag() throws Exception {
		Server s = launchIsolatedPeer();
		try {
			assertFalse(s.isConsensusHalted());
			assertNull(s.getConsensusHalt());

			s.haltConsensus(UpgradeError.missing(5));
			assertTrue(s.isConsensusHalted());
			assertEquals(5L, s.getConsensusHalt().getVersion());

			// Idempotent: the first halt is recorded, later halts do not overwrite it
			s.haltConsensus(UpgradeError.missing(9));
			assertEquals(5L, s.getConsensusHalt().getVersion());
		} finally {
			s.close();
		}
	}

	@Test
	public void testHaltedPeerFreezesButServesQueries() throws Exception {
		Server s = launchIsolatedPeer();
		try {
			Convex convex = Convex.connect(s, GENESIS, PEER_KP);

			// Consensus works before the halt
			Result r = convex.transactSync("(def a 1)");
			assertFalse(r.isError(), () -> "Setup transaction failed: " + r);

			// Freeze consensus exactly as the executor would on an unsupported upgrade
			s.haltConsensus(UpgradeError.missing(2));
			assertTrue(s.isConsensusHalted());

			// Server stays alive: queries still resolve against the frozen state
			Result q = convex.querySync("a");
			assertFalse(q.isError());
			assertEquals(CVMLong.create(1), q.getValue());

			// Consensus is frozen: a transaction submitted after the halt never
			// confirms. The freeze is a hard latch (both executor and propagator
			// gate on it), so this is deterministic, not a race — the future never
			// completes and the bounded wait always times out.
			CompletableFuture<Result> f = convex.transact("(def b 2)");
			assertThrows(TimeoutException.class, () -> f.get(2, TimeUnit.SECONDS));

			// State did not advance: b was never defined
			Result q2 = convex.querySync("b");
			assertTrue(q2.isError());
		} finally {
			s.close();
		}
	}
}
