package convex.peer.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Migrations;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.cvm.Symbols;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.SignedData;
import convex.core.data.Symbol;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.Core;
import convex.core.util.JSON;

/**
 * Fast, deterministic, entirely local multi-peer rehearsal of protocol v0 to v1.
 *
 * <p>Three real {@link Peer} state machines establish CPoS consensus over signed
 * governance and user transactions. The rehearsal covers scheduling, ordinary
 * traffic immediately before and at activation, post-activation traffic, exact
 * cross-peer convergence, and reconstruction from genesis plus the final belief.
 * A separate fork schedules and then unschedules the upgrade to exercise the
 * pre-activation abort path.</p>
 *
 * <p>No sockets, wall clock, sleeps, random keys or remote peers are used. Nothing
 * can be submitted to Protonet. Any failed invariant throws and makes the process
 * exit non-zero.</p>
 *
 * <p>Usage: {@code java -cp convex.jar convex.peer.tools.RehearseNetworkUpgrade}</p>
 */
public final class RehearseNetworkUpgrade {

	static final int PEER_COUNT = 3;
	static final int MAX_CONSENSUS_ROUNDS = 20;
	static final Symbol PHASE = Symbol.create("rehearsal-phase");

	private RehearseNetworkUpgrade() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 0) throw new IllegalArgumentException("This local rehearsal takes no arguments");
		RehearsalResult first = rehearseActivation();
		RehearsalResult second = rehearseActivation();
		require(first.equals(second), "identical rehearsals produced different results");
		Hash abortHash = rehearseAbort();

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("protocolBefore", 0L);
		summary.put("protocolAfter", Migrations.MAX_VERSION);
		summary.put("peerCount", PEER_COUNT);
		summary.put("genesisHash", first.genesisHash());
		summary.put("scheduledHash", first.scheduledHash());
		summary.put("preBoundaryHash", first.preBoundaryHash());
		summary.put("boundaryHash", first.boundaryHash());
		summary.put("finalHash", first.finalHash());
		summary.put("finalPosition", first.finalPosition());
		summary.put("abortHash", abortHash);
		summary.put("deterministic", true);
		summary.put("passed", true);
		System.out.println(JSON.toStringPretty(summary));
	}

	/** Runs the complete activation path and reconstructs every peer from genesis. */
	static RehearsalResult rehearseActivation() throws Exception {
		Fixture f = new Fixture();
		Peer[] peers = f.newPeers();
		long initial = f.genesis.getTimestamp().longValue();
		long activation = initial + 1_000;

		// Governance scheduling is a real signed transaction from #6. Before v1 the
		// native cell is embedded directly because no core binding exists yet.
		SignedData<ATransaction> schedule = f.governance.signData(Invoke.create(
				Init.GOVERNANCE_ADDRESS, 1,
				Lists.of(Core.SCHEDULE_UPGRADE, CVMLong.create(activation))));
		peers = proposeAndConverge(peers, 0, Block.of(initial + 100, schedule), 1);
		State scheduled = commonState(peers);
		require(scheduled.getProtocolVersion() == 0, "scheduling advanced the protocol early");
		require(scheduled.getUpgradeVector().count() == 1, "upgrade was not scheduled");

		// Ordinary v0 traffic in the last possible pre-boundary block.
		SignedData<ATransaction> preTraffic = f.user.signData(Invoke.create(
				Init.GENESIS_ADDRESS, 1, "(def rehearsal-phase :pre)"));
		peers = proposeAndConverge(peers, 1, Block.of(activation - 1, preTraffic), 2);
		State before = commonState(peers);
		require(before.getProtocolVersion() == 0, "upgrade fired before its activation timestamp");
		require(Keyword.intern("pre").equals(before.getAccount(Init.GENESIS_ADDRESS)
				.getEnvironmentValue(PHASE)), "pre-boundary traffic did not execute");

		// At exactly the activation timestamp migration v1 runs before this ordinary
		// transaction, exercising the real State.applyBlock transition order.
		SignedData<ATransaction> boundaryTraffic = f.user.signData(Invoke.create(
				Init.GENESIS_ADDRESS, 2, "(def rehearsal-phase :boundary)"));
		peers = proposeAndConverge(peers, 2, Block.of(activation, boundaryTraffic), 3);
		State boundary = commonState(peers);
		require(boundary.getProtocolVersion() == Migrations.MAX_VERSION,
				"activation did not advance to the latest protocol version");
		require(boundary.getAccount(Core.CORE_ADDRESS).getEnvironmentValue(Symbols.CAT) == Core.CAT,
				"v1 core bindings were not installed");
		require(Keyword.intern("boundary").equals(boundary.getAccount(Init.GENESIS_ADDRESS)
				.getEnvironmentValue(PHASE)), "boundary traffic did not execute under v1");

		SignedData<ATransaction> postTraffic = f.user.signData(Invoke.create(
				Init.GENESIS_ADDRESS, 3, "(def rehearsal-phase :post)"));
		peers = proposeAndConverge(peers, 0, Block.of(activation + 1, postTraffic), 4);
		State fin = commonState(peers);
		require(Keyword.intern("post").equals(fin.getAccount(Init.GENESIS_ADDRESS)
				.getEnvironmentValue(PHASE)), "post-upgrade traffic did not execute");
		require(fin.computeTotalBalance() == f.genesis.computeTotalBalance(),
				"rehearsal changed the total coin supply");

		// Recreate each local peer from genesis and its final acquired belief, then
		// force the exact startup replay path. Local calculation must be definitive.
		for (int i = 0; i < peers.length; i++) {
			Peer restarted = Peer.create(f.peers.get(i), f.genesis, peers[i].getBelief());
			restarted = restarted.recalcState(0, peers[i].getStatePosition());
			require(fin.equals(restarted.getConsensusState()),
					"peer " + i + " restart replay diverged: "
							+ restarted.getConsensusState().getHash() + " != " + fin.getHash());
		}

		return new RehearsalResult(f.genesis.getHash(), scheduled.getHash(), before.getHash(),
				boundary.getHash(), fin.getHash(), peers[0].getStatePosition());
	}

	/** Exercises the governance abort path on an independent deterministic fork. */
	static Hash rehearseAbort() throws Exception {
		Fixture f = new Fixture();
		Peer[] peers = f.newPeers();
		long initial = f.genesis.getTimestamp().longValue();
		long activation = initial + 1_000;
		SignedData<ATransaction> schedule = f.governance.signData(Invoke.create(
				Init.GOVERNANCE_ADDRESS, 1,
				Lists.of(Core.SCHEDULE_UPGRADE, CVMLong.create(activation))));
		peers = proposeAndConverge(peers, 0, Block.of(initial + 100, schedule), 1);
		SignedData<ATransaction> unschedule = f.governance.signData(Invoke.create(
				Init.GOVERNANCE_ADDRESS, 2,
				Lists.of(Core.UNSCHEDULE_UPGRADE, CVMLong.create(1))));
		peers = proposeAndConverge(peers, 1, Block.of(initial + 200, unschedule), 2);
		State aborted = commonState(peers);
		require(aborted.getUpgradeVector().isEmpty(), "unschedule did not remove the pending upgrade");
		peers = proposeAndConverge(peers, 2, Block.of(activation), 3);
		State afterBoundary = commonState(peers);
		require(afterBoundary.getProtocolVersion() == 0, "an unscheduled upgrade still activated");
		return afterBoundary.getHash();
	}

	static Peer[] proposeAndConverge(Peer[] peers, int proposer, Block block, long position)
			throws Exception {
		Peer[] next = peers.clone();
		for (int i = 0; i < next.length; i++) {
			next[i] = next[i].updateTimestamp(block.getTimeStamp());
		}
		next[proposer] = next[proposer].proposeBlock(block);
		for (int round = 0; round < MAX_CONSENSUS_ROUNDS; round++) {
			Belief[] beliefs = new Belief[next.length];
			for (int i = 0; i < next.length; i++) beliefs[i] = next[i].getBelief();
			Peer[] merged = next.clone();
			for (int i = 0; i < next.length; i++) {
				merged[i] = next[i].mergeBeliefs(beliefs).updateState();
			}
			next = merged;
			if (converged(next, position)) return next;
		}
		StringBuilder positions = new StringBuilder();
		for (Peer peer : next) positions.append(' ').append(peer.getStatePosition());
		throw new IllegalStateException("peers did not converge at state position " + position
				+ "; actual positions:" + positions);
	}

	static boolean converged(Peer[] peers, long position) {
		State expected = peers[0].getConsensusState();
		for (Peer peer : peers) {
			if (peer.getStatePosition() < position || !expected.equals(peer.getConsensusState())) return false;
		}
		return true;
	}

	static State commonState(Peer[] peers) {
		State state = peers[0].getConsensusState();
		for (int i = 1; i < peers.length; i++) {
			require(state.equals(peers[i].getConsensusState()), "peer states are not identical");
		}
		return state;
	}

	static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}

	record RehearsalResult(Hash genesisHash, Hash scheduledHash, Hash preBoundaryHash,
			Hash boundaryHash, Hash finalHash, long finalPosition) {
	}

	static final class Fixture {
		final AKeyPair governance = AKeyPair.createSeeded(8_901);
		final AKeyPair user = AKeyPair.createSeeded(8_902);
		final List<AKeyPair> peers = List.of(AKeyPair.createSeeded(8_911),
				AKeyPair.createSeeded(8_912), AKeyPair.createSeeded(8_913));
		final State genesis;

		Fixture() {
			List<AccountKey> peerKeys = peers.stream().map(AKeyPair::getAccountKey).toList();
			genesis = Init.createState(governance.getAccountKey(), user.getAccountKey(), peerKeys);
			require(genesis.getProtocolVersion() == 0, "rehearsal genesis must be protocol v0");
		}

		Peer[] newPeers() {
			Peer[] result = new Peer[peers.size()];
			for (int i = 0; i < result.length; i++) result[i] = Peer.create(peers.get(i), genesis);
			return result;
		}
	}
}
