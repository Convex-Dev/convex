package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;

public class BeliefVotingTest {

	AKeyPair[] kps=new AKeyPair[] {
			AKeyPair.createSeeded(1),
			AKeyPair.createSeeded(2),
			AKeyPair.createSeeded(3),
			AKeyPair.createSeeded(4),
			AKeyPair.createSeeded(5),
			AKeyPair.createSeeded(6)
	};
	
	AccountKey[] keys=Stream.of(kps).map(kp->kp.getAccountKey()).toArray(AccountKey[]::new);
	
	@Test
	public void testComputeVote() {
		assertEquals(100.0, Belief.computeVote(Maps.hashMapOf(1, 50.0, 0, 50.0)), 0.000001);
		assertEquals(0.0, Belief.computeVote(Maps.hashMapOf()), 0.000001);
	}
	
	static final long TS=0;

	
	@SuppressWarnings("unchecked")
	@Test public void testBasicMerges() throws BadSignatureException, InvalidDataException {
		SignedData<Block> A=bl(1);
		
		State s=Init.createState(List.of(keys));
		assertTrue(s.getPeers().get(keys[0]).getTotalStake()>0);
		
		Order o0=Order.create().withTimestamp(TS);
		Belief b0=Belief.create(kps[0], o0).withTimestamp(TS);

		// check trivial merges are idempotent
		MergeContext baseMC=MergeContext.create(b0, kps[0], TS, s);
		assertSame(b0,b0.mergeOrders(baseMC,b0));
		Belief b00=b0.merge(baseMC);
		assertSame(b0,b00);
		assertSame(b0,b0.merge(baseMC,b0));
		assertSame(b0,b0.merge(baseMC,b0,b0));
		
		long ATIME=A.getValue().getTimeStamp();
		Order p1o=Order.create(0, 0, A).withTimestamp(ATIME);
		Belief b1=Belief.create(kps[1], p1o).withTimestamp(ATIME);
		
		// Shouldn't change Belief, since incoming order is from future
		Belief b0present=b0.merge(baseMC, b1);
		assertSame(b0,b0present);
		
		// Updated merge context should allow Belief merge with new Block
		MergeContext mc=MergeContext.create(b0, kps[0], TS+1, s);
		Belief b2=b0.merge(mc, b1);
		Order o2=b2.getOrder(keys[0]);
		assertEquals(p1o.getBlocks(),o2.getBlocks());
		assertEquals(0,o2.getProposalPoint());
		assertEquals(0,o2.getConsensusPoint());
		assertEquals(TS+1,o2.getTimestamp());
		
		// Beliefs from other Peers, enough for Proposal
		Belief br2=Belief.create(kps[2], p1o).withTimestamp(ATIME);
		Belief br3=Belief.create(kps[3], p1o).withTimestamp(ATIME);
		Belief br4=Belief.create(kps[4], p1o).withTimestamp(ATIME);
		Belief br5=Belief.create(kps[5], p1o).withTimestamp(ATIME);
		
		// Merge new Beliefs
		Belief b3=b2.merge(mc, br2,br3,br4,br5);
		Order o3=b3.getOrder(keys[0]);
		assertEquals(p1o.getBlocks(),o3.getBlocks());
		assertEquals(1,o3.getProposalPoint());
		assertEquals(0,o3.getConsensusPoint());
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testBlockVotes() throws BadSignatureException, InvalidDataException {
		SignedData<Block> A=bl(1);
		SignedData<Block> B=bl(2);
		SignedData<Block> C=bl(3);
		SignedData<Block> D=bl(4);
		SignedData<Block> E=bl(5);
		SignedData<Block> F=bl(6);
		SignedData<Block> G=bl(7);
		State s=Init.createState(List.of(keys));

		{
			SignedData<Order> o0=or(0, TS, 0,0,A);
			SignedData<Order> o1=or(1, TS, 0,0,A,B);
			SignedData<Order> o2=or(2, TS, 0,0,B);
			SignedData<Order> o3=or(3, TS, 0,0,B,A);
			SignedData<Order> o4=or(4, TS, 0,0,B,A,C,D);
			SignedData<Order> o5=or(5, TS, 0,0,B,A,E,F,G);
			
			Belief b=Belief.create(o0,o1,o2,o3,o4,o5);
			MergeContext mc=MergeContext.create(b, kps[0], TS, s);
			Belief b2=b.merge(mc);
			SignedData<Order> so=b2.getOrders().get(keys[0]);
			Order order=so.getValue();
			assertEquals(7,order.getBlockCount());
			assertEquals(B,order.getBlock(0));
			assertEquals(0,order.getProposalPoint()); // 66.66..% just short of proposal threshold
			assertEquals(0,order.getConsensusPoint());
			assertEquals(Vectors.of(B,A,C,D,E,F,G),order.getBlocks());
		}
		
		{
			SignedData<Order> o0=or(0, TS, 0,0,A);
			SignedData<Order> o1=or(1, TS, 0,0,B,A);
			SignedData<Order> o2=or(2, TS, 0,0,B);
			SignedData<Order> o3=or(3, TS, 1,0,B,A);
			SignedData<Order> o4=or(4, TS, 1,0,B,A,C,D);
			SignedData<Order> o5=or(5, TS, 1,0,B,A,E,F,G);
			
			Belief b=Belief.create(o0,o1,o2,o3,o4,o5);
			MergeContext mc=MergeContext.create(b, kps[0], TS, s);
			Belief b2=b.merge(mc);
			SignedData<Order> so=b2.getOrders().get(keys[0]);
			Order order=so.getValue();
			assertEquals(7,order.getBlockCount());
			assertEquals(B,order.getBlock(0));
			assertEquals(1,order.getProposalPoint()); // Enough for proposal
			assertEquals(0,order.getConsensusPoint());
			assertEquals(Vectors.of(B,A,C,D,E,F,G),order.getBlocks());
		}
		
		{
			SignedData<Order> o0=or(0, TS, 0,0,A);
			SignedData<Order> o1=or(1, TS, 1,0,B);
			SignedData<Order> o2=or(2, TS, 1,0,B);
			SignedData<Order> o3=or(3, TS, 1,0,B,A);
			SignedData<Order> o4=or(4, TS, 1,0,B,A);
			SignedData<Order> o5=or(5, TS, 1,0,B);
			
			Belief b=Belief.create(o0,o1,o2,o3,o4,o5);
			MergeContext mc=MergeContext.create(b, kps[0], TS, s);
			Belief b2=b.merge(mc);
			SignedData<Order> so=b2.getOrders().get(keys[0]);
			Order order=so.getValue();
			assertEquals(2,order.getBlockCount());
			assertEquals(B,order.getBlock(0));
			assertEquals(1,order.getProposalPoint()); // Enough for proposal
			assertEquals(1,order.getConsensusPoint()); // Enough for consensus
			assertEquals(Vectors.of(B,A),order.getBlocks());
		}

	}

	@SuppressWarnings("unchecked")
	private SignedData<Order> or(int peer, long ts, int pp, int cp, SignedData<Block>... blks) {
		Order o=Order.create(pp, cp, blks).withTimestamp(TS);
		return kps[peer].signData(o);
	}

	private SignedData<Block> bl(int i) {
		Block b=Block.of(i,tr(i),tr(i+1000000));
		return kps[i%kps.length].signData(b);
	}

	/**
	 * Create a unique dummy transaction for each seed value
	 * @param i
	 * @return
	 */
	private SignedData<ATransaction> tr(int i) {
		ATransaction t=Invoke.create(Address.create(i), i, CVMLong.create(i));
		return kps[i%kps.length].signData(t);
	}
	
}
