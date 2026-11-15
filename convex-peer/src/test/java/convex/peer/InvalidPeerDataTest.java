package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
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
		SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
		checkGoodOrderMerges(peer,good,Belief.create(good,(SignedData)unusable));
	}

	/**
	 * Sends a Belief holding B's good Order, and checks that the peer merges the good
	 * Order and keeps running.
	 *
	 * @return The peer's Belief after the merge
	 */
	static Belief checkGoodOrderMerges(Server peer, SignedData<Order> good, Belief received) throws Exception {
		BlockingQueue<Belief> updates=ownOrderUpdates(peer);
		connect(peer).sendMessage(wire(Message.createBelief(received)));

		Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
		assertNotNull(merged,"the good Order is merged");
		assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
		assertTrue(peer.isRunning());
		return merged;
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

	/** A Belief whose entry for a staked peer is not a signed value. */
	@SuppressWarnings({"unchecked","rawtypes"})
	@Test
	public void aBeliefEntryThatIsNotASignedValue() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
			Index orders=Index.of(REMOTE_A.getAccountKey(),CVMLong.ONE,REMOTE_B.getAccountKey(),good);
			checkGoodOrderMerges(peer,good,Belief.create(orders));
		} finally {
			peer.close();
		}
	}

	/**
	 * An Error while processing one received Order, such as running out of memory,
	 * rejects only that Order: the other Order in the same Belief is merged.
	 */
	@Test
	public void anErrorProcessingAnOrderRejectsOnlyThatOrder() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			peer.getBeliefPropagator().setOrderUpdateObserver(so -> {
				if (REMOTE_A.getAccountKey().equals(so.getAccountKey())) throw new OutOfMemoryError("test");
			});
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
			SignedData<Order> failing=order(REMOTE_A,1,block(REMOTE_A,blockTime(peer)));
			Belief merged=checkGoodOrderMerges(peer,good,Belief.create(good,failing));
			assertNull(merged.getOrders().get(REMOTE_A.getAccountKey()));
		} finally {
			peer.close();
		}
	}

	/**
	 * An Error while staging a DATA message, such as running out of memory, drops only
	 * that message: a good Order sent after it is merged.
	 */
	@Test
	public void anErrorStagingDataDropsOnlyThatMessage() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			peer.getBeliefPropagator().setDataStageObserver(m -> {
				throw new OutOfMemoryError("test");
			});
			Message data=Message.createDataMessage(List.of(Blob.wrap(new byte[200])),(int)CPoSConstants.MAX_MESSAGE_LENGTH);
			connect(peer).sendMessage(wire(data));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
			checkGoodOrderMerges(peer,good,Belief.create(good));
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
	 * Deeply nested DATA is stored, and a good Order sent after it is merged.
	 */
	@Test
	public void deeplyNestedDataIsStored() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			ArrayList<ACell> branches=new ArrayList<>();
			nested(DEEP,branches);
			// Outermost first, so storing it stores everything beneath it in one call
			Collections.reverse(branches);
			ACell outermost=branches.get(0);
			Message data=Message.createDataMessage(branches,(int)CPoSConstants.MAX_MESSAGE_LENGTH);

			LocalConnection senderEnd=connect(peer);
			senderEnd.sendMessage(wire(data));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
			senderEnd.sendMessage(wire(Message.createBelief(Belief.create(good))));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertNotNull(peer.getStore().refForHash(outermost.getHash()));
		} finally {
			peer.close();
		}
	}

	/** The cells beneath a value that are not embedded, except one, found without recursion. */
	static ArrayList<ACell> branchCells(ACell root, Hash leaveOut) {
		ArrayList<ACell> result=new ArrayList<>();
		HashSet<Hash> seen=new HashSet<>();
		ArrayList<Ref<?>> stack=new ArrayList<>();
		Cells.visitBranchRefs(root,stack::add);
		while (!stack.isEmpty()) {
			Ref<?> r=stack.remove(stack.size()-1);
			Hash h=r.getHash();
			if (h.equals(leaveOut)||!seen.add(h)) continue;
			ACell c=r.getValue();
			result.add(c);
			Cells.visitBranchRefs(c,stack::add);
		}
		return result;
	}

	/**
	 * A staked peer's Order that leaves out part of a Block, beside another staked peer's
	 * Order holding deeply nested data, merges with it once the missing part is fetched,
	 * on a peer with an in-memory store.
	 */
	@Test
	public void anIncompleteOrderBesideDeeplyNestedData() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			long ts=blockTime(peer);
			List<SignedData<ATransaction>> txs=new ArrayList<>();
			for (int i=1; i<=8; i++) txs.add(REMOTE_A.signData(Invoke.create(Init.GENESIS_ADDRESS,i,"*address*")));
			Block incomplete=Block.create(ts,txs);
			Hash leftOut=incomplete.getTransactions().getHash();
			SignedData<Order> orderA=order(REMOTE_A,1,REMOTE_A.signData(incomplete));
			ACell deep=nested(DEEP,new ArrayList<>());
			SignedData<ATransaction> deepTx=REMOTE_B.signData(Invoke.create(Init.GENESIS_ADDRESS,1,deep));
			SignedData<Order> orderB=order(REMOTE_B,1,REMOTE_B.signData(Block.create(ts,List.of(deepTx))));
			Belief belief=Belief.create(orderA,orderB);
			ArrayList<ACell> cells=branchCells(belief,leftOut);
			cells.add(belief); // top cell last
			Message update=Message.create(MessageType.BELIEF,belief,Format.encodeDelta(cells));

			// The sender holds the part it left out, and answers the peer's fetch for it
			MemoryStore senderStore=new MemoryStore();
			Cells.persist(incomplete.getTransactions(),senderStore);
			MissingBeliefDataTest.connect(peer,MissingBeliefDataTest.answeringFrom(senderStore)).sendMessage(wire(update));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			while ((merged!=null)&&!(orderA.equals(merged.getOrders().get(REMOTE_A.getAccountKey()))
					&&orderB.equals(merged.getOrders().get(REMOTE_B.getAccountKey())))) {
				merged=updates.poll(WAIT,TimeUnit.SECONDS);
			}
			assertNotNull(merged,"both Orders are merged");
			assertTrue(peer.isRunning());
		} finally {
			peer.close();
		}
	}

	/**
	 * A staked peer's Order holding deeply nested data does not stop the merge of a good
	 * Order beside it.
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
	 * A query whose result its code nests far deeper than recursion allows does not stop
	 * the peer answering other clients' queries.
	 */
	@Test
	public void aQueryWithADeeplyNestedResult() throws Exception {
		Server peer=launchPeer(LOCAL);
		try (Convex remote=Convex.connect(peer.getHostAddress())) {
			String buildDeep="(loop [v 1 i 0] (if (< i "+DEEP+") (recur [v] (inc i)) v))";
			// Completes, normally or not, once the peer has dealt with the query
			remote.query(buildDeep).handle((r,e)->r).get(WAIT,TimeUnit.SECONDS);

			// From the network, so it takes the peer's query handler as the deep query did
			try (Convex other=Convex.connect(peer.getHostAddress())) {
				Result ok=other.query("(+ 1 2)").get(WAIT,TimeUnit.SECONDS);
				assertEquals(CVMLong.create(3),ok.getValue(),ok.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A data request whose handling fails is dropped, and the peer goes on answering the
	 * queries queued after it.
	 */
	@Test
	public void anErrorAnsweringADataRequestDropsOnlyThatRequest() throws Exception {
		Hash failing=Blob.fromHex("cafe").getHash(); // never stored
		MemoryStore store=new MemoryStore() {
			@Override
			public <T extends ACell> Ref<T> refForHash(Hash hash) {
				if ((hash!=null)&&hash.equals(failing)) throw new OutOfMemoryError("Test: out of memory reading requested data");
				return super.refForHash(hash);
			}
		};
		Server peer=launchPeer(store,LOCAL);
		try (Convex other=Convex.connect(peer.getHostAddress())) {
			// Queued before the query, on the same handler
			connect(peer).sendMessage(wire(Message.createDataRequest(CVMLong.ONE,failing)));

			// From the network, so it takes the peer's query handler as the request did
			Result ok=other.query("(+ 1 2)").get(WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3),ok.getValue(),ok.toString());
		} finally {
			peer.close();
		}
	}

	/**
	 * A transaction whose result its code nests far deeper than recursion allows is
	 * executed and stored, and the peer goes on executing the transactions after it.
	 */
	@Test
	public void aTransactionWithADeeplyNestedResult() throws Exception {
		aTransactionWithADeeplyNestedResult(new MemoryStore());
	}

	@Test
	public void aTransactionWithADeeplyNestedResultOnEtch() throws Exception {
		try (EtchStore store=EtchStore.createTemp()) {
			aTransactionWithADeeplyNestedResult(store);
		}
	}

	private void aTransactionWithADeeplyNestedResult(AStore store) throws Exception {
		Server peer=launchPeer(store,LOCAL);
		try {
			Convex client=Convex.connect(peer,Init.GENESIS_ADDRESS,LOCAL);
			String buildDeep="(loop [v 1 i 0] (if (< i "+DEEP+") (recur [v] (inc i)) v))";
			Result deep=client.transact(buildDeep).get(WAIT,TimeUnit.SECONDS);
			assertNull(deep.getErrorCode(),"the transaction succeeds");

			Result ok=client.transact("(+ 1 2)").get(WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3),ok.getValue(),"the next transaction runs");
		} finally {
			peer.close();
		}
	}

	/**
	 * A transaction holding deeply nested code is accepted and executed, failing on the
	 * CVM's depth limit, and later transactions are still processed.
	 */
	@Test
	public void aDeeplyNestedTransactionIsExecuted() throws Exception {
		aDeeplyNestedTransactionIsExecuted(new MemoryStore());
	}

	@Test
	public void aDeeplyNestedTransactionIsExecutedOnEtch() throws Exception {
		try (EtchStore store=EtchStore.createTemp()) {
			aDeeplyNestedTransactionIsExecuted(store);
		}
	}

	/**
	 * A transaction whose intake runs the peer out of memory is rejected as the peer
	 * being busy, and later transactions are still processed.
	 */
	@Test
	public void aTransactionThatRunsThePeerOutOfMemory() throws Exception {
		checkTransactionIntakeError(new OutOfMemoryError("Test: out of memory storing a transaction"),ErrorCodes.LOAD);
	}

	/**
	 * A transaction whose intake overflows the peer's stack is rejected as exceeding a
	 * limit, and later transactions are still processed.
	 */
	@Test
	public void aTransactionThatOverflowsThePeerStack() throws Exception {
		checkTransactionIntakeError(new StackOverflowError("Test: stack overflow storing a transaction"),ErrorCodes.LIMIT);
	}

	/**
	 * Sends a transaction whose storing at intake throws the given error, and checks the
	 * error code the client gets and that the next transaction runs.
	 */
	private void checkTransactionIntakeError(Error error, Keyword expectedCode) throws Exception {
		SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,"(+ 1 2)"));
		Hash failing=tx.getHash();
		MemoryStore store=new MemoryStore() {
			@Override
			public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) {
				if (ref.getHash().equals(failing)) throw error;
				return super.storeTopRef(ref,status,noveltyHandler);
			}
		};
		Server peer=launchPeer(store,LOCAL);
		try {
			Convex client=Convex.connect(peer,Init.GENESIS_ADDRESS,LOCAL);
			Result failed=client.transact(tx).get(WAIT,TimeUnit.SECONDS);
			assertEquals(expectedCode,failed.getErrorCode(),failed.toString());

			SignedData<ATransaction> next=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,"(+ 2 3)"));
			Result ok=client.transact(next).get(WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(5),ok.getValue(),ok.toString());
		} finally {
			peer.close();
		}
	}

	private void aDeeplyNestedTransactionIsExecuted(AStore store) throws Exception {
		Server peer=launchPeer(store,LOCAL);
		try {
			Convex client=Convex.connect(peer,Init.GENESIS_ADDRESS,LOCAL);
			ACell deep=nested(DEEP,new ArrayList<>());
			SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,deep));
			Result failed=client.transact(tx).get(WAIT,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.DEPTH,failed.getErrorCode());

			SignedData<ATransaction> next=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,2,"(+ 1 2)"));
			Result ok=client.transact(next).get(WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3),ok.getValue(),ok.toString());
		} finally {
			peer.close();
		}
	}
}
