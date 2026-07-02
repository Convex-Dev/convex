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
	public void testSelfFreezeAtBoundaryDeterministic() throws Exception {
		// End-to-end: a live peer freezes ITSELF when consensus crosses the activation
		// of an upgrade this release cannot apply. Driven deterministically by an
		// injected clock — no wall-clock waiting. The genesis is crafted post-bootstrap
		// (version 1) with a version-2 upgrade pending in the future; version 2 has no
		// migration in this release (MAX_VERSION == 1), so crossing it must freeze.
		State base = Init.createState(List.of(PEER_KP.getAccountKey()));
		long t0 = base.getTimestamp().longValue();
		long applied = t0;            // v1, already applied at/before genesis time
		long activation = t0 + 100_000; // v2, pending well in the future
		State genesis = base.withProtocolGlobals(1,
				convex.core.data.Vectors.of(CVMLong.create(applied), CVMLong.create(activation)));

		List<Server> servers = API.launchLocalPeers(List.of(PEER_KP), genesis);
		Server s = servers.get(0);
		try {
			// Take control of time BEFORE any block is produced. Start comfortably
			// before the activation so the peer operates normally.
			java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(t0 + 1000);
			s.setTimeSource(clock::get);

			Convex convex = Convex.connect(s, GENESIS, PEER_KP);

			// Early detection: the peer already knows an unsupported upgrade is
			// scheduled, well before the activation and before it withdraws.
			convex.core.cvm.Migrations.UpgradeWarning warn = s.getUpgradeWarning();
			assertEquals(2L, warn.version);
			assertEquals(activation, warn.activation);

			// Normal operation before the boundary: a transaction confirms, version stays 1
			Result r = convex.transactSync("(def a 1)");
			assertFalse(r.isError(), () -> "pre-boundary transaction failed: " + r);
			assertEquals(1L, s.getPeer().getConsensusState().getProtocolVersion());
			assertFalse(s.isConsensusHalted());

			// Advance the clock past the activation. The next block is timestamped at
			// or beyond the activation, so applying it fires the (missing) version-2
			// upgrade first — deterministically, from the controlled timestamp.
			clock.set(activation + 1);
			convex.transact("(def b 2)"); // fire-and-forget: this block never commits

			// Wait on the real halt signal (guaranteed to occur)
			UpgradeError halt = s.awaitConsensusHalt().get(20, TimeUnit.SECONDS);
			assertEquals(2L, halt.getVersion());
			assertTrue(s.isConsensusHalted());

			// The peer stayed at version 1 and never applied the version-2 boundary
			assertEquals(1L, s.getPeer().getConsensusState().getProtocolVersion());

			// Still alive for queries against the frozen state; b was never defined
			assertFalse(convex.querySync("a").isError());
			assertTrue(convex.querySync("b").isError());
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
