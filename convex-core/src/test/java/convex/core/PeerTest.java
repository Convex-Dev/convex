package convex.core;

import static convex.test.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Index;
import convex.core.data.RecordTest;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
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
		long timestamp = p.getTimestamp();
		assertEquals(timestamp, Constants.INITIAL_TIMESTAMP);
		assertSame(STATE, p.getConsensusState());
		assertSame(STATE, p.getGenesisState());

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

		assertNobodyError(p.executeQuery(Reader.read("(+ 2 3)"),Samples.BAD_ADDRESS).context);
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
	
	@Test
	public void testForkRecovery() {
		State ST=STATE.withTimestamp(0);
		AKeyPair kp=InitTest.FIRST_PEER_KEYPAIR;
		Address addr=InitTest.FIRST_PEER_ADDRESS;
		AccountKey peerKey=kp.getAccountKey();
		Peer p=Peer.create(kp, ST);
		
		Belief b=p.getBelief();
		assertEquals(0,b.getOrder(peerKey).getBlockCount());
		
		Block b1=Block.of(0, kp.signData(Invoke.create(addr, 1,"(def foo 13)")));
		Order o1=Order.create().append(kp.signData(b1));
		o1=o1.withConsensusPoints(new long[] {1,1,1,1});
		SignedData<Order> so1=kp.signData(o1);
		b=b.withOrders(Index.create(peerKey, so1));
		
		p=p.updateBelief(b);
		assertEquals(ST,p.getConsensusState());
		p=p.updateState();
		assertEquals(CVMLong.create(13),p.executeQuery(Reader.read("foo"), addr).getResult());

		Block b2=Block.of(0, kp.signData(Invoke.create(addr, 1,"(def bar 17)")));
		Order o2=Order.create().append(kp.signData(b2));
		o2=o2.withConsensusPoints(new long[] {1,1,1,1});
		SignedData<Order> so2=kp.signData(o2);
		b=b.withOrders(Index.create(peerKey, so2));

		p=p.updateBelief(b);
		assertUndeclaredError(p.executeQuery(Reader.read("bar"), addr).context);
		
		// Beyond this point, we need to assume fork recovery is enabled
		assumeTrue(CPoSConstants.ENABLE_FORK_RECOVERY);
		
		p=p.updateState();
		assertEquals(CVMLong.create(17),p.executeQuery(Reader.read("bar"), addr).getResult());
		assertUndeclaredError(p.executeQuery(Reader.read("foo"), addr).context);
	}



}
