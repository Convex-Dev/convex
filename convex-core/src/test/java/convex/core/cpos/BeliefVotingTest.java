package convex.core.cpos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;

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
	
	
	static final long TS=0;
	final State initialState=Init.createState(List.of(keys)).withTimestamp(TS);
	
	@Test
	public void testComputeVote() {
		assertEquals(100.0, BeliefMerge.computeTotalVote(Maps.hashMapOf(1, 50.0, 0, 50.0)), 0.000001);
		assertEquals(0.0, BeliefMerge.computeTotalVote(Maps.hashMapOf()), 0.000001);
	}
	
	@Test
	public void testEmptyMerge() throws BadSignatureException, InvalidDataException {
		Belief b=Belief.create(kps[0],Order.create());
		
		BeliefMerge mc=BeliefMerge.create(b, kps[0], TS+5, initialState);
		Belief b2=mc.merge(b);
		assertSame(b,b2);
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testTieBreak() throws BadSignatureException, InvalidDataException {
		SignedData<Block> A=bl(0);
		SignedData<Block> B=bl(1);
		SignedData<Order> o0=or(0,TS,0,0,A,B);
		SignedData<Order> o1=or(1,TS,0,0,B,A);
		Belief b0=Belief.create(kps[0],o0.getValue());
		Belief b1=Belief.create(kps[1],o1.getValue());
		
		BeliefMerge mc0=BeliefMerge.create(b0, kps[0], TS+5, initialState);
		Belief b0m=mc0.merge(b1);
		
		BeliefMerge mc1=BeliefMerge.create(b1, kps[1], TS+5, initialState);
		Belief b1m=mc1.merge(b0);
		
		Order o0m = b0m.getOrder(kps[0].getAccountKey());
		Order o1m = b1m.getOrder(kps[1].getAccountKey());
		
		assertTrue(o0m.consensusEquals(o1m));
		// Different timestamp? One Peer didn't change order so no update?
		//assertEquals(o0m,o1m);

	}

	
	@SuppressWarnings("unchecked")
	@Test public void testBasicMerges() throws BadSignatureException, InvalidDataException {
		SignedData<Block> A=bl(1);
		
		
		assertTrue(initialState.getPeers().get(keys[0]).getTotalStakeShares()>0);
		
		Order o0=Order.create().withTimestamp(TS);
		Belief b0=Belief.create(kps[0], o0);

		// check trivial merges are idempotent
		BeliefMerge baseMC=BeliefMerge.create(b0, kps[0], TS, initialState);
		assertSame(b0,baseMC.mergeOrders(b0));
		Belief b00=baseMC.merge(b0);
		assertSame(b0,b00);
		assertSame(b0,baseMC.merge(b0,b0));
		
		long ATIME=A.getValue().getTimeStamp();
		Order p1o=Order.create(0, 0, A).withTimestamp(ATIME);
		Belief b1=Belief.create(kps[1], p1o);
		
		// Shouldn't change Belief, since incoming order is from future
		Belief b0present=baseMC.merge(b1);
		assertSame(b0,b0present);
		
		// Updated merge context should allow Belief merge with new Block
		BeliefMerge mc=BeliefMerge.create(b0, kps[0], TS+1, initialState);
		Belief b2=mc.merge(b1);
		Order o2=b2.getOrder(keys[0]);
		assertEquals(p1o.getBlocks(),o2.getBlocks());
		assertEquals(0,o2.getConsensusPoint(1));
		assertEquals(0,o2.getConsensusPoint(2));
		assertEquals(TS+1,o2.getTimestamp());
		 
		// Beliefs from other Peers, enough for Proposal
		Belief br2=Belief.create(kps[2], p1o);
		Belief br3=Belief.create(kps[3], p1o);
		Belief br4=Belief.create(kps[4], p1o);
		Belief br5=Belief.create(kps[5], p1o);
		
		// Merge new Beliefs
		Belief b3=mc.merge(b2,br2,br3,br4,br5);
		Order o3=b3.getOrder(keys[0]);
		assertEquals(p1o.getBlocks(),o3.getBlocks());
		assertEquals(1,o3.getConsensusPoint(1));
		assertEquals(0,o3.getConsensusPoint(2));
		
		mc=BeliefMerge.create(b3, kps[0], TS+1, initialState);
		// Future merges should be idempotent
		assertSame(b3,mc.merge(br2,br3,br4,br5));
		BeliefMerge mc3=BeliefMerge.create(b3, kps[0], TS+10, initialState);
		assertSame(b3,mc3.merge(br2,br3,br4,br5));
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
		State s=this.initialState;

		{
			SignedData<Order> o0=or(0, TS, 0,0,A);
			SignedData<Order> o1=or(1, TS, 0,0,A,B);
			SignedData<Order> o2=or(2, TS, 0,0,B);
			SignedData<Order> o3=or(3, TS, 0,0,B,A);
			SignedData<Order> o4=or(4, TS, 0,0,B,A,C,G); // should win, hash based?
			SignedData<Order> o5=or(5, TS, 0,0,B,A,E,F,D); 
			
			Belief b=Belief.create(o0,o1,o2,o3,o4,o5);
			BeliefMerge mc=BeliefMerge.create(b, kps[0], TS, s);
			Belief b2=mc.merge();
			SignedData<Order> so=b2.getOrders().get(keys[0]);
			Order order=so.getValue();
			assertEquals(7,order.getBlockCount());
			assertEquals(B,order.getBlock(0));
			assertEquals(G,order.getBlock(3));
			assertEquals(0,order.getProposalPoint()); // 66.66..% just short of proposal threshold
			assertEquals(0,order.getConsensusPoint());
			// Note C,G not in winning Order so sorted by timestamp order
			
			assertEquals(Vectors.of(2,1,3,7,4,5,6),order.getBlocks().map(sb->CVMLong.create(sb.getValue().getTimeStamp())));
			assertEquals(Vectors.of(B,A,C,G,D,E,F),order.getBlocks());
		}
		
		{
			SignedData<Order> o0=or(0, TS, 0,0,A);
			SignedData<Order> o1=or(1, TS, 0,0,B,A);
			SignedData<Order> o2=or(2, TS, 0,0,B);
			SignedData<Order> o3=or(3, TS, 1,0,B,A);
			SignedData<Order> o4=or(4, TS, 1,0,B,A,C,D);
			SignedData<Order> o5=or(5, TS, 1,0,B,A,E,F,G);
			
			Belief b=Belief.create(o0,o1,o2,o3,o4,o5);
			BeliefMerge mc=BeliefMerge.create(b, kps[0], TS, s);
			Belief b2=mc.merge();
			SignedData<Order> so=b2.getOrders().get(keys[0]);
			Order order=so.getValue();
			assertEquals(7,order.getBlockCount());
			assertEquals(B,order.getBlock(0));
			assertEquals(1,order.getConsensusPoint(1)); // Enough for proposal
			assertEquals(0,order.getConsensusPoint(2));
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
			BeliefMerge mc=BeliefMerge.create(b, kps[0], TS, s);
			Belief b2=mc.merge();
			SignedData<Order> so=b2.getOrders().get(keys[0]);
			Order order=so.getValue();
			assertEquals(2,order.getBlockCount());
			assertEquals(B,order.getBlock(0));
			assertEquals(1,order.getConsensusPoint(1)); // Enough for proposal level 1
			assertEquals(1,order.getConsensusPoint(2)); // Enough for consensus level 2
			assertEquals(Vectors.of(B,A),order.getBlocks());
		}
		
		{
			// "Everybody wants to be my enemy"
			SignedData<Order> o0=or(0, TS, 1,0,A);
			SignedData<Order> o1=or(1, TS, 1,0,B);
			SignedData<Order> o2=or(2, TS, 1,0,B);
			SignedData<Order> o3=or(3, TS, 1,0,B);
			SignedData<Order> o4=or(4, TS, 1,0,B);
			SignedData<Order> o5=or(5, TS, 1,0,B);
			
			Belief b=Belief.create(o0,o1,o2,o3,o4,o5);
			BeliefMerge mc=BeliefMerge.create(b, kps[0], TS+1, s);
			Belief b2=mc.merge();
			SignedData<Order> so=b2.getOrders().get(keys[0]);
			assertEquals(o0,so); // Shouldn't have changed
			Order order=so.getValue();
			assertEquals(1,order.getBlockCount());
			assertEquals(A,order.getBlock(0)); // didn't switch
			assertEquals(1,order.getConsensusPoint(1)); // Kept proposal
			assertEquals(0,order.getConsensusPoint(2)); // No change in my consensus level 2

			// After enough time, Peer should be willing to switch proposal
			BeliefMerge mc3=BeliefMerge.create(b, kps[0], TS+1+CPoSConstants.KEEP_PROPOSAL_TIME, s);
			Belief b3=mc3.merge();
			SignedData<Order> so3=b3.getOrders().get(keys[0]);
			Order order3=so3.getValue();
			assertEquals(2,order3.getBlockCount());
			assertEquals(B,order3.getBlock(0)); // didn't switch
			assertEquals(A,order3.getBlock(1)); // Kept own block
			assertEquals(1,order3.getConsensusPoint(1)); // Updated proposal 
			assertEquals(1,order3.getConsensusPoint(2)); // New consensus
		}
	}

	@SuppressWarnings("unchecked")
	@Test public void testForwardBlockClamp() throws BadSignatureException, InvalidDataException {
		// #595 stage (i): a peer must not finalise a Block dated beyond its own clock plus
		// MAX_BLOCK_FORWARD, even when the stake vote would otherwise confirm it.
		SignedData<Block> near = bl(1);          // timestamp 1 — within the clock horizon
		SignedData<Block> far  = bl(1_000_000);  // timestamp 1_000_000 — far in the future

		// All six equal-stake peers fully agree on [near, far] (proposal and consensus = 2)
		SignedData<Order>[] os = new SignedData[6];
		for (int i = 0; i < 6; i++) os[i] = or(i, TS, 2, 2, near, far);
		Belief b = Belief.create(os);

		// Control: with a peer clock past both timestamps, the vote finalises BOTH Blocks —
		// so it is the clamp, not the vote, that holds finality back below.
		Order full = BeliefMerge.create(b, kps[0], 2_000_000, initialState).merge().getOrder(keys[0]);
		assertEquals(2, full.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));

		// Peer clock at TS=0: the far Block is beyond wallClock + MAX_BLOCK_FORWARD, so
		// finality is clamped to the near Block only, though both Blocks stay in the ordering.
		Order clamped = BeliefMerge.create(b, kps[0], TS, initialState).merge().getOrder(keys[0]);
		assertEquals(2, clamped.getBlockCount());
		assertEquals(1, clamped.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));

		// Just short of the far Block's timestamp: still clamped to the near Block.
		Order still = BeliefMerge.create(b, kps[0], 1_000_000 - CPoSConstants.MAX_BLOCK_FORWARD - 1,
				initialState).merge().getOrder(keys[0]);
		assertEquals(1, still.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));

		// Once the clock reaches the far Block's timestamp, both Blocks finalise.
		Order advanced = BeliefMerge.create(b, kps[0], 1_000_000, initialState).merge().getOrder(keys[0]);
		assertEquals(2, advanced.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));
	}

	@SuppressWarnings("unchecked")
	@Test public void testForwardWedgeResolves() throws Exception {
		// #595 stage (ii) end-to-end: a far-future Block F must not wedge a later in-horizon
		// Block N. Peers start proposing [F, N]; through merge, F is demoted behind N so N
		// finalises while F waits (at TS=0, F is out-of-horizon).
		SignedData<Block> nBlk = bl(1);          // in-horizon
		SignedData<Block> fBlk = bl(1_000_000);  // far future
		SignedData<Order>[] os = new SignedData[6];
		for (int i = 0; i < 6; i++) os[i] = or(i, TS, 0, 0, fBlk, nBlk);
		Belief belief = Belief.create(os);

		// Sequential merge rounds across all peers, clock fixed at TS (F stays out of horizon)
		for (int round = 0; round < 8; round++) {
			for (int i = 0; i < 6; i++) {
				belief = BeliefMerge.create(belief, kps[i], TS, initialState).merge(belief);
			}
		}
		Order o = belief.getOrder(keys[0]);
		assertEquals(Vectors.of(nBlk, fBlk), o.getBlocks(), "N reordered ahead of F");
		assertEquals(1, o.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY), "N finalised, F waits");
	}

	@Test public void testDemoteFutureBlocks() {
		// #595 stage (ii): a stable partition — far-future Blocks moved to the back, the
		// order of in-horizon Blocks preserved (never a timestamp sort).
		SignedData<Block> a  = bl(10);
		SignedData<Block> b2 = bl(20);
		SignedData<Block> c  = bl(30);
		SignedData<Block> f1 = bl(1_000_000);
		SignedData<Block> f2 = bl(2_000_000);
		long limit = 100;

		// No future Blocks: the same vector is returned (no allocation)
		AVector<SignedData<Block>> plain = Vectors.of(a, b2, c);
		assertSame(plain, BeliefMerge.demoteFutureBlocks(plain, 0, limit));

		// Future Blocks already a clean suffix: unchanged
		AVector<SignedData<Block>> suffix = Vectors.of(a, b2, f1, f2);
		assertSame(suffix, BeliefMerge.demoteFutureBlocks(suffix, 0, limit));

		// The wedge case: [F, N] -> [N, F]
		assertEquals(Vectors.of(a, f1), BeliefMerge.demoteFutureBlocks(Vectors.of(f1, a), 0, limit));

		// Interleaved: in-horizon order (a,b2,c) and future order (f1,f2) each preserved
		assertEquals(Vectors.of(a, b2, c, f1, f2),
				BeliefMerge.demoteFutureBlocks(Vectors.of(a, f1, b2, f2, c), 0, limit));

		// Floor fixes the agreed prefix: with floor 2, a and b2 stay; only [f1, c] reorders
		assertEquals(Vectors.of(a, b2, c, f1),
				BeliefMerge.demoteFutureBlocks(Vectors.of(a, b2, f1, c), 2, limit));

		// A future Block below the floor is left in place (treated as agreed)
		AVector<SignedData<Block>> belowFloor = Vectors.of(a, f1, b2);
		assertSame(belowFloor, BeliefMerge.demoteFutureBlocks(belowFloor, 2, limit));
	}

	@SuppressWarnings("unchecked")
	@Test public void testVariableConsensusLevelsInMerge() throws Exception {
		SignedData<Block> block=bl(1);

		// Missing levels contribute no confirmation at those levels. These Orders
		// provide raw ordering and proposal, so consensus can advance but finality cannot.
		SignedData<Order>[] shortOrders=new SignedData[6];
		for (int i=0; i<shortOrders.length; i++) {
			shortOrders[i]=or(i,TS,new long[] {1,1},block);
		}
		Belief shortBelief=Belief.create(shortOrders);
		Order shortResult=BeliefMerge.create(shortBelief,kps[0],TS,initialState).merge().getOrder(keys[0]);
		assertEquals(1,shortResult.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_PROPOSAL));
		assertEquals(1,shortResult.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_CONSENSUS));
		assertEquals(0,shortResult.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));

		// Surplus levels do not extend the configured main-network consensus depth.
		SignedData<Order>[] extraOrders=new SignedData[6];
		for (int i=0; i<extraOrders.length; i++) {
			extraOrders[i]=or(i,TS,new long[] {1,1,1,1,0,0},block);
		}
		Belief extraBelief=Belief.create(extraOrders);
		Order extraResult=BeliefMerge.create(extraBelief,kps[0],TS,initialState).merge().getOrder(keys[0]);
		assertEquals(1,extraResult.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));
		assertEquals(6,extraResult.getConsensusPoints().length);
	}

	/**
	 * CAD051: a peer's confirmed consensus points MUST never retreat. Recomputing
	 * levels from the current voting set previously moved consensus and finality
	 * DOWNWARD whenever a lagging copy of another peer's Order supplied the stake
	 * tipping the 2/3 threshold. The peer then signed and published the receded
	 * Order and truncated already-executed state, transiently un-reporting
	 * transactions whose results had been delivered to clients.
	 */
	@SuppressWarnings("unchecked")
	@Test public void testConsensusPointsNeverRetreat() throws Exception {
		SignedData<Block>[] blocks=new SignedData[9];
		for (int i=0; i<blocks.length; i++) blocks[i]=bl(i+1);
		SignedData<Block>[] laggingBlocks=java.util.Arrays.copyOf(blocks,7);

		// Peers 0-3 hold a fresh Order: 9 blocks, proposal 9, consensus 7, finality 6.
		// Peers 4-5 are known only through lagging copies: 7 blocks, minimal confirmation.
		SignedData<Order>[] orders=new SignedData[6];
		for (int i=0; i<4; i++) orders[i]=or(i,TS,new long[] {9,9,7,6},blocks);
		for (int i=4; i<6; i++) orders[i]=or(i,TS,new long[] {7,5,5,0},laggingBlocks);

		Belief belief=Belief.create(orders);
		Order merged=BeliefMerge.create(belief,kps[0],TS+100,initialState).merge().getOrder(keys[0]);

		// The lagging Orders tip the stake threshold at every level, but confirmed
		// levels are ratchets: consensus and finality must not move backwards.
		assertEquals(9,merged.getBlockCount());
		assertEquals(7,merged.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_CONSENSUS));
		assertEquals(6,merged.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY));
		// Proposal may legitimately recede to the stake-supported prefix
		assertEquals(7,merged.getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_PROPOSAL));
	}

	@SuppressWarnings("unchecked")
	private SignedData<Order> or(int peer, long ts, int pp, int cp, SignedData<Block>... blks) {
		Order o=Order.create(pp, cp, blks).withTimestamp(TS);
		return kps[peer].signData(o);
	}

	@SafeVarargs
	private final SignedData<Order> or(int peer, long ts, long[] consensusPoints, SignedData<Block>... blks) {
		Order o=Order.create(Vectors.of(
				CVMLong.create(ts),
				Vectors.createLongs(consensusPoints),
				Vectors.create(blks)));
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
