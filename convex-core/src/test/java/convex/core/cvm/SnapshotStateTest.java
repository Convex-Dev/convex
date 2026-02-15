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
import convex.core.data.MapEntry;
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
	private static final Hash EXPECTED_STATE = Hash.fromHex("36eee6a881262ecbee97d78f29cf4842ea6eedee67906bf983954261acab79ea");
	private State state;
	private Belief belief;
	private Order order;

	@BeforeAll
	void computeConsensusState() throws Exception {
		State genesis = GenesisStateTest.getGenesisState();
		belief = BeliefSnapshotTest.getBelief();

		// Pick the first order from the belief
		assertTrue(belief.getOrders().count() > 0, "Belief should have at least one order");

		order = belief.getOrders().get(PEER_KEY).getValue();
		assertNotNull(order, "Order should exist");
		assertEquals(728, order.getConsensusPoint()); // observed consensus point

		// Apply consensus blocks from the order to the genesis state
		long consensusPoint = order.getConsensusPoint();
		AVector<SignedData<Block>> blocks = order.getBlocks();
		State s = genesis;
		for (long i = 0; i < consensusPoint; i++) {
			s = s.applyBlock(blocks.get(i)).getState();
		}
		state = s;
		assertNotNull(state);
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
