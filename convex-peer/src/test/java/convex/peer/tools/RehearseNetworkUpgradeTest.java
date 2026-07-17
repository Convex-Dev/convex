package convex.peer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Migrations;

/** Fast offline regression for the standalone multi-peer rehearsal main. */
public class RehearseNetworkUpgradeTest {

	@Test
	public void testDeterministicActivationAndAbortRehearsals() throws Exception {
		RehearseNetworkUpgrade.RehearsalResult first = RehearseNetworkUpgrade.rehearseActivation();
		RehearseNetworkUpgrade.RehearsalResult second = RehearseNetworkUpgrade.rehearseActivation();
		assertEquals(first, second);
		assertEquals(4L, first.finalPosition());
		assertNotEquals(first.genesisHash(), first.finalHash());
		assertNotEquals(first.finalHash(), RehearseNetworkUpgrade.rehearseAbort());
		assertEquals(1L, Migrations.MAX_VERSION);
	}
}
