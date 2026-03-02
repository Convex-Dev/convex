package convex.core.cvm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.cpos.Belief;
import convex.core.cpos.BeliefSnapshotTest;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.SignedData;

/**
 * Tests a consensus state produced by applying a live Belief snapshot
 * to the genesis state.
 *
 * Uses genesis.cad3 and belief snapshot from test resources (loaded once
 * by GenesisStateTest and BeliefSnapshotTest respectively).
 */
@TestInstance(Lifecycle.PER_CLASS)
public class SnapshotStateTest {

	private static final AccountKey PEER_KEY = AccountKey.fromHex("d6ef2d429b73ef1c78d9e46d87feb9d9535a991b8102099f54ed243f1e557d42");
	// Previous: 77b0446d11ba2550abc533e16be90d380c08daab81491ad4cd166d4833cd5da9
	// Changed by fix to PeerStatus.withDelegatedStake which was silently losing peer metadata
	// after decode (lazy null sentinel passed instead of getMetadata()). Any peer that had a
	// delegated stake change during block application now correctly retains its metadata.
	private static final Hash EXPECTED_STATE = Hash.fromHex("9359348383b11973856e100abf0ab5785fcb8ea6184e011361fc9138b7ba81a8");

	private static State cachedState;

	/**
	 * Returns the consensus state produced by applying belief blocks to genesis.
	 * Computed once and cached for reuse across tests.
	 */
	public static synchronized State getConsensusState() {
		if (cachedState != null) return cachedState;
		try {
			State genesis = GenesisStateTest.getGenesisState();
			Belief belief = BeliefSnapshotTest.getBelief();
			Order order = belief.getOrders().get(PEER_KEY).getValue();
			long consensusPoint = order.getConsensusPoint();
			AVector<SignedData<Block>> blocks = order.getBlocks();
			State s = genesis;
			for (long i = 0; i < consensusPoint; i++) {
				s = s.applyBlock(blocks.get(i)).getState();
			}
			cachedState = s;
		} catch (Exception e) {
			throw new RuntimeException("Failed to compute consensus state", e);
		}
		return cachedState;
	}

	private State state;
	private Belief belief;
	private Order order;

	@BeforeAll
	void computeConsensusState() throws Exception {
		state = getConsensusState();
		assertNotNull(state);

		belief = BeliefSnapshotTest.getBelief();
		assertTrue(belief.getOrders().count() > 0, "Belief should have at least one order");

		order = belief.getOrders().get(PEER_KEY).getValue();
		assertNotNull(order, "Order should exist");
		assertEquals(728, order.getConsensusPoint());
	}

	@Test
	public void testConsensusState() {
		assertEquals(EXPECTED_STATE,state.getHash());

		StateTest.doStateTests(state);
	}

	@Test
	public void testAccounts() {
		AVector<AccountStatus> accts=state.getAccounts();
		assertEquals(14303,accts.count()); // observed account count
	}

	@Test
	public void testConsensusAdvanced() {
		State genesis = GenesisStateTest.getGenesisState();
		// Consensus state should differ from genesis after applying blocks
		assertNotEquals(genesis.getHash(), state.getHash(),
		    "Consensus state should differ from genesis after applying belief blocks");
	}
}
