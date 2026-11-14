package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;

/**
 * A peer that receives data it cannot use, from a staked peer or any connection,
 * keeps running and keeps merging the data it can use.
 */
public class InvalidPeerDataTest {
	static final AKeyPair LOCAL=AKeyPair.createSeeded(140);
	static final AKeyPair REMOTE_A=AKeyPair.createSeeded(141);
	static final AKeyPair REMOTE_B=AKeyPair.createSeeded(142);

	/** Long enough that no fetch is dropped and no poll runs while a test waits. */
	static final int LONG_POLL_DELAY=600000;

	static final long WAIT=10;

	/**
	 * Launches a peer on a network of the given keys, all equally staked. It has no
	 * connections of its own, so it never polls.
	 */
	static Server launchPeer(AKeyPair kp, AKeyPair... others) throws Exception {
		return launchPeer(new MemoryStore(),kp,others);
	}

	/** Launches a peer as {@link #launchPeer(AKeyPair, AKeyPair...)} does, on the given store. */
	static Server launchPeer(AStore store, AKeyPair kp, AKeyPair... others) throws Exception {
		ArrayList<AccountKey> keys=new ArrayList<>();
		keys.add(kp.getAccountKey());
		for (AKeyPair o: others) keys.add(o.getAccountKey());
		State genesis=Init.createState(keys);
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STATE,genesis);
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,store);
		config.put(Keywords.POLL_DELAY,LONG_POLL_DELAY);
		return API.launchPeer(config);
	}

	/** Timestamp for Blocks: valid against the genesis State. */
	static long blockTime(Server peer) {
		return peer.getPeer().getGenesisState().getTimestamp().longValue()+1;
	}

	static SignedData<Block> block(AKeyPair kp, long timestamp) {
		return kp.signData(Block.create(timestamp,List.<SignedData<ATransaction>>of()));
	}

	@SafeVarargs
	static SignedData<Order> order(AKeyPair kp, long timestamp, SignedData<Block>... blocks) {
		Order order=Order.create();
		for (SignedData<Block> b: blocks) order=order.append(b);
		return kp.signData(order.withTimestamp(timestamp));
	}

	/** Collects this peer's Belief each time a merge changes its own Order. */
	static BlockingQueue<Belief> ownOrderUpdates(Server peer) {
		LinkedBlockingQueue<Belief> updates=new LinkedBlockingQueue<>();
		peer.getBeliefPropagator().setBeliefUpdateObserver(updates::add);
		return updates;
	}

	static LocalConnection connect(Server peer) {
		return MissingBeliefDataTest.connect(peer,m -> true);
	}

	static Message wire(Message m) {
		return MissingBeliefDataTest.wire(m);
	}

	/**
	 * Sends a Belief with a staked peer's unusable value beside B's good Order, and
	 * checks that the peer merges the good Order and keeps running.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	static void checkGoodOrderMergesBeside(Server peer, ACell unusable) throws Exception {
		BlockingQueue<Belief> updates=ownOrderUpdates(peer);
		SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
		Belief received=Belief.create(good,(SignedData)unusable);

		connect(peer).sendMessage(wire(Message.createBelief(received)));

		Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
		assertNotNull(merged,"the good Order is merged");
		assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
		assertTrue(peer.isRunning());
	}

	/** A staked peer's signed value that is not an Order. */
	@Test
	public void aSignedValueThatIsNotAnOrder() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			checkGoodOrderMergesBeside(peer,REMOTE_A.signData(CVMLong.ONE));
		} finally {
			peer.close();
		}
	}

	/**
	 * A finalised Block whose transactions are not all signed transactions is executed
	 * as an invalid Block: the State is unchanged and the peer keeps running.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	@Test
	public void aFinalisedBlockWithUnsignedTransactionsIsInvalid() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			Block block=Block.create(blockTime(peer),(AVector)Vectors.of(CVMLong.ONE));
			SignedData<Block> signed=REMOTE_A.signData(block);
			LocalConnection senderEnd=connect(peer);

			// Each newer copy of the remote Order lets the local Order advance one level
			long finality=0;
			for (long t=1; (finality<1)&&(t<=10); t++) {
				Order claim=Order.create().append(signed).withConsensusPoints(new long[] {1,1,1,1}).withTimestamp(t);
				senderEnd.sendMessage(wire(Message.createBelief(Belief.create(REMOTE_A.signData(claim)))));
				Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
				assertNotNull(merged,"each newer remote Order is merged");
				finality=merged.getOrder(LOCAL.getAccountKey()).getConsensusPoint(CPoSConstants.CONSENSUS_LEVEL_FINALITY);
			}
			assertEquals(1,finality);

			Peer executed=peer.awaitStatePosition(1).get(WAIT,TimeUnit.SECONDS);
			assertEquals(executed.getGenesisState(),executed.getConsensusState());
			assertTrue(executed.getBlockResult(0).isError(0));
			assertTrue(peer.isRunning());
		} finally {
			peer.close();
		}
	}

	/**
	 * A staked peer's Order with part of a Block never sent is merged once the peer has
	 * fetched that part from the sender, on a peer with an in-memory store. A good Order
	 * from another peer in the same update is merged meanwhile.
	 */
	@Test
	public void anOrderMissingPartOfABlockMergesOnceFetched() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			MemoryStore senderStore=new MemoryStore();
			long ts=blockTime(peer);
			// A Block's transactions are a branch of the Block, so they can be left out
			List<SignedData<ATransaction>> txs=new ArrayList<>();
			for (int i=1; i<=8; i++) txs.add(REMOTE_A.signData(Invoke.create(Init.GENESIS_ADDRESS,i,"*address*")));
			Block block=Block.create(ts,txs);
			AVector<SignedData<ATransaction>> leftOut=block.getTransactions();
			assertTrue(!leftOut.isEmbedded());
			// Announced before, so the next update leaves it out
			MissingBeliefDataTest.update(leftOut,senderStore);
			SignedData<Order> incomplete=order(REMOTE_A,1,REMOTE_A.signData(block));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,ts));
			List<Message> sent=MissingBeliefDataTest.update(Belief.create(incomplete,good),senderStore);

			LocalConnection senderEnd=MissingBeliefDataTest.connect(peer,MissingBeliefDataTest.answeringFrom(senderStore));
			for (Message m: sent) senderEnd.sendMessage(wire(m));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			while (!incomplete.equals(merged.getOrders().get(REMOTE_A.getAccountKey()))) {
				merged=updates.poll(WAIT,TimeUnit.SECONDS);
				assertNotNull(merged,"the completed Order is merged");
			}
			assertTrue(peer.isRunning());
		} finally {
			peer.close();
		}
	}

	/** Nesting depth of the deep values these tests send */
	static final int DEEP=10000;

	/**
	 * Builds vectors nested to a depth, each holding the next.
	 *
	 * @param depth Nesting depth
	 * @param branches Receives the cells that are not embedded, innermost first
	 * @return Outermost vector
	 */
	static ACell nested(int depth, List<ACell> branches) {
		ACell nested=CVMLong.ONE;
		for (int i=0; i<depth; i++) {
			nested=Vectors.of(nested);
			if (!nested.isEmbedded()) {
				nested.getHash(); // bottom up, so no hash is computed recursively
				branches.add(nested);
			}
		}
		return nested;
	}

	/**
	 * Deeply nested DATA that a peer with an in-memory store cannot store is not
	 * stored, and a good Order sent after it is merged.
	 */
	@Test
	public void deeplyNestedDataIsNotStored() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			ArrayList<ACell> branches=new ArrayList<>();
			ACell top=nested(DEEP,branches);
			// Outermost first, so storing it stores everything beneath it in one call
			Collections.reverse(branches);
			Message data=Message.createDataMessage(branches,(int)CPoSConstants.MAX_MESSAGE_LENGTH);

			LocalConnection senderEnd=connect(peer);
			senderEnd.sendMessage(wire(data));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
			senderEnd.sendMessage(wire(Message.createBelief(Belief.create(good))));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertNull(peer.getStore().refForHash(top.getHash()));
		} finally {
			peer.close();
		}
	}

	/**
	 * A staked peer's Order holding deeply nested data that the peer cannot store does
	 * not stop the merge of a good Order beside it.
	 */
	@Test
	public void anOrderWithDeeplyNestedData() throws Exception {
		anOrderWithDeeplyNestedData(new MemoryStore());
	}

	@Test
	public void anOrderWithDeeplyNestedDataOnEtch() throws Exception {
		try (EtchStore store=EtchStore.createTemp()) {
			anOrderWithDeeplyNestedData(store);
		}
	}

	private void anOrderWithDeeplyNestedData(AStore store) throws Exception {
		Server peer=launchPeer(store,LOCAL,REMOTE_A,REMOTE_B);
		try {
			ACell deep=nested(DEEP,new ArrayList<>());
			SignedData<ATransaction> tx=REMOTE_A.signData(Invoke.create(Init.GENESIS_ADDRESS,1,deep));
			Block block=Block.create(blockTime(peer),List.of(tx));
			checkGoodOrderMergesBeside(peer,order(REMOTE_A,1,REMOTE_A.signData(block)));
		} finally {
			peer.close();
		}
	}

	/**
	 * A deeply nested transaction that the peer cannot store is rejected as malformed,
	 * and later transactions are still processed.
	 */
	@Test
	public void aDeeplyNestedTransactionIsRejected() throws Exception {
		aDeeplyNestedTransactionIsRejected(new MemoryStore());
	}

	@Test
	public void aDeeplyNestedTransactionIsRejectedOnEtch() throws Exception {
		try (EtchStore store=EtchStore.createTemp()) {
			aDeeplyNestedTransactionIsRejected(store);
		}
	}

	private void aDeeplyNestedTransactionIsRejected(AStore store) throws Exception {
		Server peer=launchPeer(store,LOCAL);
		try {
			Convex client=Convex.connect(peer,Init.GENESIS_ADDRESS,LOCAL);
			ACell deep=nested(DEEP,new ArrayList<>());
			SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,deep));
			Result rejected=client.transact(tx).get(WAIT,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.FORMAT,rejected.getErrorCode());

			SignedData<ATransaction> next=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,"(+ 1 2)"));
			Result ok=client.transact(next).get(WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3),ok.getValue(),ok.toString());
		} finally {
			peer.close();
		}
	}
}
