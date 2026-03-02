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
import convex.core.data.AArrayBlob;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.lang.RT;

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
	// Previous: 9359348383b11973856e100abf0ab5785fcb8ea6184e011361fc9138b7ba81a8 (728 blocks)
	// Updated for belief-1772448810106.cad3 (730 blocks, consensus point 730)
	private static final Hash EXPECTED_STATE = Hash.fromHex("a7a4b718dd3be10671af398938016c95ff6d6b275368e8c9b8d73f8ea7edf628");

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
		assertEquals(730, order.getConsensusPoint());
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
	public void testPeers() {
		Index<AArrayBlob, PeerStatus> peers = state.getPeers();
		assertTrue(peers.count() > 0, "Should have at least one peer");

		int withURL = 0;
		for (var entry : peers.entrySet()) {
			AccountKey key = RT.ensureAccountKey(entry.getKey());
			PeerStatus ps = entry.getValue();
			assertNotNull(ps, "PeerStatus should not be null for " + key);
			assertTrue(ps.getPeerStake() >= 0, "Peer stake should be non-negative");
			assertTrue(ps.getBalance() >= 0, "Peer balance should be non-negative");

			AString hostname = ps.getHostname();
			if (hostname != null) {
				String url = hostname.toString();
				assertFalse(url.isEmpty(), "URL should not be empty for " + key);
				// Valid URLs should contain :// scheme or be legacy host:port
				assertTrue(url.contains("://") || url.contains(":"),
					"URL should have scheme or port: " + url + " for " + key);
				withURL++;
			}
		}
		assertTrue(withURL > 0, "At least one peer should have a URL");
	}

	@Test
	public void testConsensusAdvanced() {
		State genesis = GenesisStateTest.getGenesisState();
		// Consensus state should differ from genesis after applying blocks
		assertNotEquals(genesis.getHash(), state.getHash(),
		    "Consensus state should differ from genesis after applying belief blocks");
	}
}
