package convex.core;

import static convex.test.Assertions.assertNobodyError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.AccountKey;
import convex.core.data.PeerStatus;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitConfigTest;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.Samples;

public class PeerTest {
	static State STATE=InitTest.STATE;


	@Test
	public void testInitial() throws BadSignatureException {
		Peer p = Peer.create(InitConfigTest.FIRST_PEER_KEYPAIR, STATE);

		// initial checks
		long timestamp = p.getTimeStamp();
		assertEquals(timestamp, Constants.INITIAL_TIMESTAMP);
		assertSame(STATE, p.getConsensusState());

		// Belief check
		AccountKey peerKey = p.getPeerKey();
		Belief b = p.getBelief();
		assertNotNull(b.getOrder(peerKey));

		// check adding a block
		assertEquals(0, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		Block bl0 = Block.of(timestamp,peerKey);
		p = p.proposeBlock(bl0);

		assertEquals(1, p.getPeerOrder().getBlockCount());
		assertEquals(0, p.getPeerOrder().getConsensusPoint());

		// Run a query
		assertEquals(RT.cvm(3L), (p.executeQuery(Reader.read("(+ 1 2)")).getResult()));
	}

	@Test
	public void testNullPeers() {
		assertNull(STATE.getPeer(InitConfigTest.HERO_KEYPAIR.getAccountKey())); // hero not a peer in initial state
	}

	@Test
	public void testQuery() throws BadSignatureException {
		Peer p = Peer.create(InitConfigTest.FIRST_PEER_KEYPAIR, STATE);

		assertEquals(RT.cvm(3L),p.executeQuery(Reader.read("(+ 1 2)")).getResult());
		assertEquals(InitConfigTest.HERO_ADDRESS,p.executeQuery(Reader.read("*address*"),InitConfigTest.HERO_ADDRESS).getResult());

		assertNobodyError(p.executeQuery(Reader.read("(+ 2 3)"),Samples.BAD_ADDRESS));
	}

	@Test
	public void testStakeAccess() {
		// use peer address from first peer for testing
		AccountKey pa = InitConfigTest.FIRST_PEER_KEY;
		PeerStatus ps = STATE.getPeer(pa);
		long initialStake = ps.getPeerStake();
		assertEquals(initialStake, ps.getTotalStake());

		assertEquals(0, ps.getDelegatedStake(InitConfigTest.HERO_ADDRESS));

		// add a delegated stake
		PeerStatus ps2 = ps.withDelegatedStake(InitConfigTest.HERO_ADDRESS, 1234);
		assertEquals(1234L, ps2.getDelegatedStake(InitConfigTest.HERO_ADDRESS));
		assertEquals(initialStake + 1234, ps2.getTotalStake());
		assertEquals(initialStake, ps2.getPeerStake());
	}

	@Test
	public void testAsOf() {
		Peer p = Peer.create(InitConfigTest.FIRST_PEER_KEYPAIR, STATE);

		CVMLong timestamp = p.getStates().get(0).getTimeStamp();

		// Exact match.
		assertNotNull(p.asOf(timestamp));

		// Approximate match.
		assertNotNull(p.asOf(CVMLong.create(timestamp.longValue() + 1)));

		// No match; timestamp is too old.
		assertNull(p.asOf(CVMLong.create(timestamp.longValue() - 1)));
	}

	@Test
	public void testAsOfRange() {
		Peer p = Peer.create(InitConfigTest.FIRST_PEER_KEYPAIR, STATE);

		CVMLong initialTimestamp = p.getStates().get(0).getTimeStamp();

		assertEquals(0, p.asOfRange(CVMLong.create(0), 0, 0).count());
		assertEquals(0, p.asOfRange(initialTimestamp, 0, 0).count());
		assertEquals(1, p.asOfRange(initialTimestamp, 0, 1).count());

		// It's important to notice that timestamp can be in the future,
		// and that is fine because 'asOf' returns the leftmost value.
		//
		// Peer 'p' has a single State but we are asking to query every minute (5x):
		// timestamp, timestamp + 1 min, timestamp + 2 min, timestamp + 3 min, timestamp + 4 min.
		assertEquals(5, p.asOfRange(initialTimestamp, 1000 * 60, 5).count());
	}

}
