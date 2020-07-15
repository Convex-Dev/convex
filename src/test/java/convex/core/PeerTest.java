package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.PeerStatus;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.Reader;
import convex.core.lang.TestState;

public class PeerTest {

	@Test
	public void testInitial() throws BadSignatureException {
		AKeyPair PEER0 = Init.KEYPAIRS[0];
		Peer p = Peer.create(PEER0, TestState.INITIAL);

		// initial checks
		long timestamp = p.getTimeStamp();
		assertEquals(timestamp, Constants.INITIAL_TIMESTAMP);
		assertSame(TestState.INITIAL, p.getConsensusState());

		// Belief check
		Address addr = p.getAddress();
		Belief b = p.getBelief();
		assertNotNull(b.getOrder(addr));

		// check adding a block
		assertEquals(0, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		Block bl0 = Block.of(timestamp);
		p = p.proposeBlock(bl0);

		assertEquals(1, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		// Run a query
		assertEquals(3L, (Long) (p.executeQuery(Reader.read("(+ 1 2)"), null).getResult()));
	}

	@Test
	public void testNullPeers() {
		assertNull(Init.INITIAL_STATE.getPeer(Init.HERO)); // hero not a peer in initial state
	}

	@Test
	public void testStakeAccess() {
		// use peer address from first peer for testing
		Address pa = Init.FIRST_PEER;
		PeerStatus ps = Init.INITIAL_STATE.getPeer(pa);
		long initialStake = ps.getOwnStake();
		assertEquals(initialStake, ps.getTotalStake());

		assertEquals(0, ps.getDelegatedStake(pa));
		assertEquals(0, ps.getDelegatedStake(Init.HERO));

		// checks for bad staking
		assertThrows(IllegalArgumentException.class, () -> ps.withDelegatedStake(Init.HERO, -1));
		assertThrows(IllegalArgumentException.class, () -> ps.withDelegatedStake(Init.HERO, Amount.MAX_AMOUNT + 1));

		// add a delegated stake
		PeerStatus ps2 = ps.withDelegatedStake(Init.HERO, 1234);
		assertEquals(1234L, ps2.getDelegatedStake(Init.HERO));
		assertEquals(initialStake + 1234, ps2.getTotalStake());
		assertEquals(initialStake, ps2.getOwnStake());
	}
}
