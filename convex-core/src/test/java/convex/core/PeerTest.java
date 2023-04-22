package convex.core;

import static convex.test.Assertions.assertNobodyError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountKey;
import convex.core.data.PeerStatus;
import convex.core.data.RecordTest;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.Samples;

public class PeerTest {
	static State STATE=InitTest.STATE;


	@Test
	public void testInitial() throws BadSignatureException {
		Peer p = Peer.create(InitTest.FIRST_PEER_KEYPAIR, STATE);

		// initial checks
		long timestamp = p.getTimeStamp();
		assertEquals(timestamp, Constants.INITIAL_TIMESTAMP);
		assertEquals(STATE, p.getConsensusState());

		// Belief check
		AccountKey peerKey = p.getPeerKey();
		Belief b = p.getBelief();
		assertNotNull(b.getOrder(peerKey));

		// check adding a block
		assertEquals(0, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		Block bl0 = Block.of(timestamp);
		p = p.proposeBlock(bl0);

		assertEquals(1, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		// Run a query
		assertEquals(RT.cvm(3L), (p.executeQuery(Reader.read("(+ 1 2)")).getResult()));
		
	}

	@Test
	public void testQuery() throws BadSignatureException {
		Peer p = Peer.create(InitTest.FIRST_PEER_KEYPAIR, STATE);

		assertEquals(RT.cvm(3L),p.executeQuery(Reader.read("(+ 1 2)")).getResult());
		assertEquals(InitTest.HERO,p.executeQuery(Reader.read("*address*"),InitTest.HERO).getResult());

		assertNobodyError(p.executeQuery(Reader.read("(+ 2 3)"),Samples.BAD_ADDRESS));
	}

	@Test
	public void testStakeAccess() {
		// use peer address from first peer for testing
		AccountKey pa = InitTest.FIRST_PEER_KEY;
		PeerStatus ps = STATE.getPeer(pa);
		
		long initialStake = ps.getPeerStake();
		assertEquals(initialStake, ps.getTotalStake());

		assertEquals(0, ps.getDelegatedStake(InitTest.HERO));

		// add a delegated stake
		PeerStatus ps2 = ps.withDelegatedStake(InitTest.HERO, 1234);
		assertEquals(1234L, ps2.getDelegatedStake(InitTest.HERO));
		assertEquals(initialStake + 1234, ps2.getTotalStake());
		assertEquals(initialStake, ps2.getPeerStake());
		
		RecordTest.doRecordTests(ps);
		RecordTest.doRecordTests(ps2);
	}



}
