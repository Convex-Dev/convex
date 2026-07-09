package convex.peer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.init.Init;

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
}
