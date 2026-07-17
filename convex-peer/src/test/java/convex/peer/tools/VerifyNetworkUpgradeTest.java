package convex.peer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cpos.Block;
import convex.core.cvm.Migrations;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.init.Init;
import convex.core.util.JSON;

/**
 * Tests the offline phases of {@link VerifyNetworkUpgrade} (migration application
 * and boundary rehearsal) against a locally created genesis. The sync/replay phases
 * need a live network and are exercised by running the tool itself.
 */
public class VerifyNetworkUpgradeTest {

	@Test
	public void testMigrateAndBoundaryPhases() {
		State genesis = Init.createState(List.of(AKeyPair.generate().getAccountKey()));
		assertEquals(0L, genesis.getProtocolVersion());

		int before = VerifyNetworkUpgrade.failures;

		State upgraded = VerifyNetworkUpgrade.verifyMigration(genesis);
		assertNotNull(upgraded, "migration phase must produce an upgraded state");
		assertEquals(Migrations.MAX_VERSION, upgraded.getProtocolVersion());

		VerifyNetworkUpgrade.verifyBoundary(genesis, upgraded);

		assertEquals(before, VerifyNetworkUpgrade.failures,
				"verification phases must not report failures on a clean genesis");
	}

	@Test
	public void testAlreadyUpgradedIsNoop() {
		State genesis = Init.createState(List.of(AKeyPair.generate().getAccountKey()));
		State upgraded = Migrations.applyAll(genesis);

		int before = VerifyNetworkUpgrade.failures;
		assertEquals(upgraded, VerifyNetworkUpgrade.verifyMigration(upgraded));
		VerifyNetworkUpgrade.verifyBoundary(upgraded, upgraded);
		assertEquals(before, VerifyNetworkUpgrade.failures);
	}

	@Test
	public void testV1MigrationFootprintFailsClosed() {
		State genesis = Init.createState(List.of(AKeyPair.createSeeded(123).getAccountKey()));
		State upgraded = Migrations.applyAll(genesis);
		assertNull(VerifyNetworkUpgrade.migrationFootprintError(genesis, upgraded));

		State unrelated = upgraded.putAccount(Init.GENESIS_ADDRESS,
				upgraded.getAccount(Init.GENESIS_ADDRESS)
						.withBalance(upgraded.getAccount(Init.GENESIS_ADDRESS).getBalance() - 1));
		assertTrue(VerifyNetworkUpgrade.migrationFootprintError(genesis, unrelated)
				.contains("unapproved account"));

		State coreBalance = upgraded.putAccount(Init.CORE_ADDRESS,
				upgraded.getAccount(Init.CORE_ADDRESS)
						.withBalance(upgraded.getAccount(Init.CORE_ADDRESS).getBalance() + 1));
		assertTrue(VerifyNetworkUpgrade.migrationFootprintError(genesis, coreBalance)
				.contains("outside environment/metadata"));
	}

	@Test
	public void testMachineReportIsStrictJSON() {
		VerifyNetworkUpgrade.Report r = new VerifyNetworkUpgrade.Report();
		r.put("stateHash", Init.createState(
				List.of(AKeyPair.createSeeded(999).getAccountKey())).getHash());
		r.record("pass", "deterministic check");
		ACell parsed = JSON.parse(r.toJSON());
		assertNotNull(parsed);
	}

	@Test
	public void testExactPeerReplayPath() throws Exception {
		AKeyPair keyPair=AKeyPair.createSeeded(424242);
		State genesis=Init.createState(List.of(keyPair.getAccountKey()));
		Peer peer=Peer.create(keyPair,genesis);
		Block block=Block.of(peer.getTimestamp(),keyPair.signData(
				Invoke.create(Init.GENESIS_ADDRESS,1,"*address*")));
		peer=peer.proposeBlock(block)
				.mergeBeliefs().mergeBeliefs().mergeBeliefs().mergeBeliefs()
				.updateState();

		int before=VerifyNetworkUpgrade.failures;
		State replayed=VerifyNetworkUpgrade.replay(genesis,peer.getBelief(),keyPair.getAccountKey(),
				peer.getConsensusState(),peer.getConsensusState().getHash(),peer.getStatePosition());
		assertEquals(peer.getConsensusState(),replayed);
		assertEquals(before,VerifyNetworkUpgrade.failures);

		State legacyReplay=VerifyNetworkUpgrade.replay(genesis,peer.getBelief(),keyPair.getAccountKey(),
				peer.getConsensusState(),peer.getConsensusState().getHash(),null);
		assertEquals(peer.getConsensusState(),legacyReplay);
		assertEquals(before,VerifyNetworkUpgrade.failures);
	}
}
