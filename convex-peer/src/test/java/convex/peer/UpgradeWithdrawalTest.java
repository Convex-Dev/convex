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
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.AccountKey;
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
	public void testLeavesAnotherViablePeer() {
		// #597 guard: the never-last-viable-peer check underpinning best-efforts withdrawal.
		AccountKey k1 = PEER_KP.getAccountKey();
		AccountKey k2 = AKeyPair.createSeeded(202).getAccountKey();

		// Single peer: withdrawing it would leave no viable peer
		State single = Init.createState(List.of(k1));
		assertFalse(TransactionHandler.leavesAnotherViablePeer(single, k1));

		// Two peers: withdrawing either still leaves the other viable
		State two = Init.createState(List.of(k1, k2));
		assertTrue(TransactionHandler.leavesAnotherViablePeer(two, k1));
		assertTrue(TransactionHandler.leavesAnotherViablePeer(two, k2));

		// An unrelated key (controls no peer) sees both peers as "other viable"
		assertTrue(TransactionHandler.leavesAnotherViablePeer(two, AKeyPair.createSeeded(303).getAccountKey()));
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
		// injected clock — no wall-clock waiting. The genesis is the fully upgraded
		// state (all migrations applied, at MAX_VERSION) with one further upgrade
		// pending in the future; that version (MAX_VERSION+1) has no migration in this
		// release, so crossing it must freeze.
		long supported = Migrations.MAX_VERSION;
		long unsupported = supported + 1;
		State upgraded = Migrations.applyAll(Init.createState(List.of(PEER_KP.getAccountKey())));
		long t0 = upgraded.getTimestamp().longValue();
		long activation = t0 + 100_000;
		State genesis = upgraded.withProtocolGlobals(supported,
				upgraded.getUpgradeVector().conj(CVMLong.create(activation)));

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
			Migrations.UpgradeWarning warn = s.getUpgradeWarning();
			assertEquals(unsupported, warn.version);
			assertEquals(activation, warn.activation);

			// Normal operation before the boundary: a transaction confirms, version unchanged
			Result r = convex.transactSync("(def a 1)");
			assertFalse(r.isError(), () -> "pre-boundary transaction failed: " + r);
			assertEquals(supported, s.getPeer().getConsensusState().getProtocolVersion());
			assertFalse(s.isConsensusHalted());

			// Advance the clock past the activation. The next block is timestamped at
			// or beyond the activation, so applying it fires the (missing) upgrade
			// first — deterministically, from the controlled timestamp.
			clock.set(activation + 1);
			convex.transact("(def b 2)"); // fire-and-forget: this block never commits

			// Wait on the real halt signal (guaranteed to occur)
			UpgradeError halt = s.awaitConsensusHalt().get(20, TimeUnit.SECONDS);
			assertEquals(unsupported, halt.getVersion());
			assertTrue(s.isConsensusHalted());

			// The peer stayed at the supported version and never applied the boundary
			assertEquals(supported, s.getPeer().getConsensusState().getProtocolVersion());

			// Still alive for queries against the frozen state; b was never defined
			assertFalse(convex.querySync("a").isError());
			assertTrue(convex.querySync("b").isError());
		} finally {
			s.close();
		}
	}

	@Test
	public void testLastPeerDoesNotWithdraw() throws Exception {
		// #597: a single peer is the last viable peer, so the never-last-peer guard must
		// prevent auto-withdrawal even in the pre-activation window with AUTO_MANAGE on.
		// The peer keeps its stake (no self-destruction) and still freezes at the boundary.
		// This drives the full withdrawal code path deterministically (single peer confirms
		// alone); the guard's positive branch is covered by testLeavesAnotherViablePeer.
		long supported = Migrations.MAX_VERSION;
		long unsupported = supported + 1;
		AccountKey myKey = PEER_KP.getAccountKey();
		State upgraded = Migrations.applyAll(Init.createState(List.of(myKey)));
		long t0 = upgraded.getTimestamp().longValue();
		long activation = t0 + 60L*60*1000; // 60 min ahead, so the 5-10 min pre-window fits
		State genesis = upgraded.withProtocolGlobals(supported, upgraded.getUpgradeVector().conj(CVMLong.create(activation)));

		List<Server> servers = API.launchLocalPeers(List.of(PEER_KP), genesis);
		Server s = servers.get(0);
		try {
			java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(t0 + 1000);
			s.setTimeSource(clock::get);
			long stake0 = s.getPeer().getConsensusState().getPeer(myKey).getPeerStake();
			assertTrue(stake0 > 0);

			Convex convex = Convex.connect(s, GENESIS, PEER_KP);
			// Move deep into the withdrawal window (past any randomised instant, before the boundary)
			clock.set(activation - 60*1000);
			Result r = convex.transactSync("(def a 1)");
			assertFalse(r.isError(), () -> "in-window transaction failed: " + r);

			// The last viable peer did NOT withdraw: still fully staked (a withdrawal would
			// zero the stake; peer rewards may only increase it), and still operating.
			assertTrue(s.getPeer().getConsensusState().getPeer(myKey).getPeerStake() >= stake0,
					"last viable peer must not have withdrawn its stake");
			assertFalse(s.isConsensusHalted());

			// It still freezes at the boundary
			clock.set(activation + 1);
			convex.transact("(def b 2)");
			UpgradeError halt = s.awaitConsensusHalt().get(20, TimeUnit.SECONDS);
			assertEquals(unsupported, halt.getVersion());
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
