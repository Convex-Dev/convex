package convex.core;

import static convex.test.Assertions.assertNobodyError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.PeerStatus;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.test.Samples;

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
		AccountKey addr = p.getPeerKey();
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
		assertEquals(3L, (Long) (p.executeQuery(Reader.read("(+ 1 2)")).getResult()));
	}

	@Test
	public void testNullPeers() {
		assertNull(Init.STATE.getPeer(Init.HERO_KP.getAccountKey())); // hero not a peer in initial state
	}
	
	@Test
	public void testQuery() throws BadSignatureException {
		AKeyPair PEER0 = Init.KEYPAIRS[0];
		Peer p = Peer.create(PEER0, TestState.INITIAL);
		
		assertEquals(3L,p.executeQuery(Reader.read("(+ 1 2)")).getResult());
		assertEquals(Init.HERO,p.executeQuery(Reader.read("*address*"),Init.HERO).getResult());
		
		assertNobodyError(p.executeQuery(Reader.read("(+ 2 3)"),Samples.BAD_ADDRESS));
	}

	@Test
	public void testStakeAccess() {
		// use peer address from first peer for testing
		AccountKey pa = Init.FIRST_PEER;
		PeerStatus ps = Init.STATE.getPeer(pa);
		long initialStake = ps.getOwnStake();
		assertEquals(initialStake, ps.getTotalStake());

		assertEquals(0, ps.getDelegatedStake(Init.HERO));

		// add a delegated stake
		PeerStatus ps2 = ps.withDelegatedStake(Init.HERO, 1234);
		assertEquals(1234L, ps2.getDelegatedStake(Init.HERO));
		assertEquals(initialStake + 1234, ps2.getTotalStake());
		assertEquals(initialStake, ps2.getOwnStake());
	}
}
