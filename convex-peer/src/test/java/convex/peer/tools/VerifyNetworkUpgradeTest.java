package convex.peer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
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
	private final VerifyNetworkUpgrade verification=new VerifyNetworkUpgrade(new PrintStream(OutputStream.nullOutputStream()));

	@AfterEach
	void noVerificationFailures() {
		assertEquals(0,verification.failures,verification.report::toJSON);
		assertEquals(0,verification.warnings,verification.report::toJSON);
	}

	@Test
	public void testMigrateAndBoundaryPhases() {
		State genesis = Init.createState(List.of(AKeyPair.generate().getAccountKey()));
		assertEquals(0L, genesis.getProtocolVersion());

		State upgraded = verification.verifyMigration(genesis);
		assertNotNull(upgraded, "migration phase must produce an upgraded state");
		assertEquals(Migrations.MAX_VERSION, upgraded.getProtocolVersion());

		verification.verifyBoundary(genesis, upgraded);
	}

	@Test
	public void testAlreadyUpgradedIsNoop() {
		State genesis = Init.createState(List.of(AKeyPair.generate().getAccountKey()));
		State upgraded = Migrations.applyAll(genesis);

		assertEquals(upgraded, verification.verifyMigration(upgraded));
		verification.verifyBoundary(upgraded, upgraded);
	}

	@Test
	public void testV1MigrationFootprintFailsClosed() {
		State genesis = Init.createState(List.of(AKeyPair.createSeeded(123).getAccountKey()));
		State upgraded = Migrations.applyAll(genesis);
		assertNull(VerifyNetworkUpgrade.migrationFootprintError(genesis, upgraded));

		State unrelated = upgraded.putAccount(Init.GENESIS_ADDRESS,
				upgraded.getAccount(Init.GENESIS_ADDRESS)
						.withBalance(upgraded.getAccount(Init.GENESIS_ADDRESS).getBalance() - 1));
		assertNotNull(VerifyNetworkUpgrade.migrationFootprintError(genesis, unrelated));

		State coreBalance = upgraded.putAccount(Init.CORE_ADDRESS,
				upgraded.getAccount(Init.CORE_ADDRESS)
						.withBalance(upgraded.getAccount(Init.CORE_ADDRESS).getBalance() + 1));
		assertNotNull(VerifyNetworkUpgrade.migrationFootprintError(genesis, coreBalance));
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

		State replayed=verification.replay(genesis,peer.getBelief(),keyPair.getAccountKey(),
				peer.getConsensusState(),peer.getConsensusState().getHash(),peer.getStatePosition());
		assertEquals(peer.getConsensusState(),replayed);

		State legacyReplay=verification.replay(genesis,peer.getBelief(),keyPair.getAccountKey(),
				peer.getConsensusState(),peer.getConsensusState().getHash(),null);
		assertEquals(peer.getConsensusState(),legacyReplay);
	}
}
