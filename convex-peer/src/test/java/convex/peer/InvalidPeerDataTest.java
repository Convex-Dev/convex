package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.MissingDataException;
import convex.core.init.Init;
import convex.core.message.AConnection;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.EtchStore;
import convex.node.NodeServerTest;

/**
 * A peer that receives data it cannot use, from a staked peer or any connection,
 * drops that data, keeps running and keeps merging the data it can use (CAD051:
 * malformed data fails closed, and parsing and merge paths never throw into the peer
 * loop). A peer that lacks data it must hold itself, or suffers a JVM failure, closes.
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
	 * Sends a Belief with a staked peer's value beside B's good Order, and checks that
	 * the peer merges the good Order and keeps running.
	 *
	 * @return The peer's Belief after the merge
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	static Belief checkGoodOrderMergesBeside(Server peer, ACell value) throws Exception {
		SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
		return checkGoodOrderMerges(peer,good,Belief.create(good,(SignedData)value));
	}

	/**
	 * Sends a Belief with A's unusable value beside B's good Order, and checks that the
	 * peer merges the good Order, drops A's value and keeps running.
	 */
	static void checkDroppedBeside(Server peer, ACell unusable) throws Exception {
		Belief merged=checkGoodOrderMergesBeside(peer,unusable);
		assertFalse(merged.getOrders().containsKey(REMOTE_A.getAccountKey()),"the unusable value is dropped");
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
			checkDroppedBeside(peer,REMOTE_A.signData(CVMLong.ONE));
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
	 * A local client, as the REST API uses, acquires a value the peer has persisted,
	 * and is refused at once, with nothing written, a value the peer has only stored,
	 * as DATA from any connection leaves it.
	 */
	@Test
	public void acquiringAValueNotPersisted() throws Exception {
		checkAcquireNotPersisted(new MemoryStore());
		try (EtchStore store=EtchStore.createTemp()) {
			checkAcquireNotPersisted(store);
		}
	}

	private void checkAcquireNotPersisted(AStore store) throws Exception {
		Server peer=launchPeer(store,LOCAL);
		try {
			Convex client=Convex.connect(peer);
			Blob part=Blob.createRandom(new Random(1234),200);
			Cells.persist(part,store);

			AVector<ACell> persisted=Cells.persist(Vectors.of(part),store);
			assertEquals(persisted,client.acquire(persisted.getHash()).get(WAIT,TimeUnit.SECONDS));

			// Only stored, although here the peer happens to hold all of it
			AVector<ACell> stored=Vectors.of(part,CVMLong.ONE);
			Cells.store(stored,store);
			checkAcquireRefused(client,stored.getHash());
			assertEquals(Ref.STORED,store.refForHash(stored.getHash()).getStatus());

			// Only stored, with a branch in no store
			Blob branch=Blob.createRandom(new Random(5678),200);
			AVector<ACell> partial=Vectors.of(branch).updateRefs(r->Ref.forHash(r.getHash(),store).markEmbedded(false));
			Cells.store(partial,store);
			checkAcquireRefused(client,partial.getHash());
		} finally {
			peer.close();
		}
	}

	private static void checkAcquireRefused(Convex client, Hash hash) {
		ExecutionException e=assertThrows(ExecutionException.class,()->client.acquire(hash).get(WAIT,TimeUnit.SECONDS));
		assertEquals(hash,assertInstanceOf(MissingDataException.class,e.getCause()).getMissingHash());
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
			assertTrue(peer.isLive());
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

	/**
	 * A staked peer's Order that fails the structure check, here because a Block's
	 * transactions are a vector tree whose last leaf is short, is dropped. A good Order
	 * from another peer in the same Belief is still merged.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	@Test
	public void anOrderWithInvalidStructureIsDropped() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			long ts=blockTime(peer);
			Block invalid=Block.create(ts,(AVector)NodeServerTest.invalidVectorTree());
			SignedData<Order> unusable=order(REMOTE_A,1,REMOTE_A.signData(invalid));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,ts));

			connect(peer).sendMessage(wire(Message.createBelief(Belief.create(unusable,good))));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertFalse(merged.getOrders().containsKey(REMOTE_A.getAccountKey()));
		} finally {
			peer.close();
		}
	}

	/** A store that throws a chosen Error once, the first time it is asked to store a chosen cell. */
	static final class FaultingStore extends MemoryStore {
		volatile Hash failing;
		volatile Error error;

		void failOnce(Hash hash, Error e) {
			error=e;
			failing=hash;
		}

		@Override
		public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) {
			Hash target=failing;
			if ((target!=null)&&target.equals(ref.getHash())) {
				failing=null;
				throw error;
			}
			return super.storeTopRef(ref,status,noveltyHandler);
		}
	}

	/** A client connection that records results, or throws a chosen Error on every reply. */
	static final class ReplyConnection extends AConnection {
		final Error error;
		final ConcurrentHashMap<ACell,Result> results=new ConcurrentHashMap<>();
		volatile boolean closed;

		ReplyConnection(Error error) {
			this.error=error;
		}

		Result result(long id) {
			return results.get(CVMLong.create(id));
		}

		@Override public boolean sendMessage(Message msg) {
			return trySendMessage(msg);
		}

		@Override public boolean trySendMessage(Message msg) {
			if (error!=null) throw error;
			if (msg.getType()==MessageType.RESULT) {
				Result r=msg.toResult();
				results.put(r.getID(),r);
			}
			return true;
		}

		@Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("192.0.2.3",30000); }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed=true; }
		@Override public long getReceivedCount() { return 0; }
	}

	/**
	 * A query whose handling overflows or runs out of memory fails alone: its
	 * connection is kept, since the failure may be the peer's own, and the next query
	 * in the same batch is answered.
	 */
	@Test
	public void aQueryThatFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			for (Error error: List.of(new StackOverflowError("simulated"),new OutOfMemoryError("simulated"))) {
				QueryHandler handler=new QueryHandler(peer);
				ReplyConnection failing=new ReplyConnection(error);
				ReplyConnection healthy=new ReplyConnection(null);
				assertTrue(handler.offerQuery(Message.createQuery(1,"(+ 1 2)",Init.GENESIS_ADDRESS).withConnection(failing)));
				assertTrue(handler.offerQuery(Message.createQuery(2,"(+ 2 3)",Init.GENESIS_ADDRESS).withConnection(healthy)));
				handler.loop();
				assertEquals(CVMLong.create(5),healthy.result(2).getValue(),error.toString());
				assertFalse(failing.isClosed(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A data request whose reply overflows or runs out of memory fails alone: the next
	 * request in the same batch is answered.
	 */
	@Test
	public void aDataRequestThatFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			for (Error error: List.of(new StackOverflowError("simulated"),new OutOfMemoryError("simulated"))) {
				QueryHandler handler=new QueryHandler(peer);
				ReplyConnection failing=new ReplyConnection(error);
				ReplyConnection healthy=new ReplyConnection(null);
				Hash held=peer.getBelief().getHash();
				assertTrue(handler.offerQuery(Message.createDataRequest(CVMLong.create(1),held).withConnection(failing)));
				assertTrue(handler.offerQuery(Message.createQuery(2,"(+ 2 3)",Init.GENESIS_ADDRESS).withConnection(healthy)));
				handler.loop();
				assertEquals(CVMLong.create(5),healthy.result(2).getValue(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A staked peer's Order that runs out of memory as it is stored is dropped. A good
	 * Order from another peer in the same Belief is still merged.
	 */
	@Test
	public void anOrderThatRunsOutOfMemoryIsDropped() throws Exception {
		FaultingStore store=new FaultingStore();
		Server peer=launchPeer(store,LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			long ts=blockTime(peer);
			SignedData<Order> unusable=order(REMOTE_A,1,block(REMOTE_A,ts));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,ts));
			store.failOnce(unusable.getHash(),new OutOfMemoryError("simulated"));

			connect(peer).sendMessage(wire(Message.createBelief(Belief.create(unusable,good))));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertFalse(merged.getOrders().containsKey(REMOTE_A.getAccountKey()));
			assertTrue(peer.isLive());
		} finally {
			peer.close();
		}
	}

	/**
	 * An update from a staked peer whose Order lacks part of a Block, sent with a good
	 * Order from another peer.
	 */
	private static List<Message> updateMissingABlock(long ts, int firstSequence, SignedData<Order> good,
			MemoryStore senderStore) throws Exception {
		List<SignedData<ATransaction>> txs=new ArrayList<>();
		for (int i=0; i<8; i++) {
			txs.add(REMOTE_A.signData(Invoke.create(Init.GENESIS_ADDRESS,firstSequence+i,"*address*")));
		}
		Block block=Block.create(ts,txs);
		// Announced before, so the next update leaves it out and the peer must fetch it
		MissingBeliefDataTest.update(block.getTransactions(),senderStore);
		SignedData<Order> incomplete=order(REMOTE_A,ts,REMOTE_A.signData(block));
		return MissingBeliefDataTest.update(Belief.create(incomplete,good),senderStore);
	}

	/**
	 * Fetching a staked peer's missing Order data that runs out of memory drops that
	 * fetch alone: a good Order from another peer in the same update is still merged,
	 * and missing data in a later update is fetched.
	 */
	@Test
	public void aFetchThatRunsOutOfMemoryIsDropped() throws Exception {
		FaultingStore store=new FaultingStore();
		Server peer=launchPeer(store,LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			MemoryStore senderStore=new MemoryStore();
			long ts=blockTime(peer);
			SignedData<Order> good=order(REMOTE_B,ts,block(REMOTE_B,ts));
			List<Message> sent=updateMissingABlock(ts,1,good,senderStore);
			for (Message m: sent) {
				// Staging the update for its fetch is the first time the peer stores it
				if (m.getType()==MessageType.BELIEF) store.failOnce(m.getPayload().getHash(),new OutOfMemoryError("simulated"));
			}
			LocalConnection senderEnd=connect(peer);
			for (Message m: sent) senderEnd.sendMessage(wire(m));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertNull(store.failing,"the fetch ran out of memory");
			assertNull(peer.getBeliefPropagator().getMissingDataFetch(),"no fetch started");

			SignedData<Order> later=order(REMOTE_B,ts+1,block(REMOTE_B,ts),block(REMOTE_B,ts+1));
			LocalConnection laterEnd=connect(peer);
			for (Message m: updateMissingABlock(ts+1,9,later,senderStore)) laterEnd.sendMessage(wire(m));
			merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the later good Order is merged");
			assertNotNull(peer.getBeliefPropagator().getMissingDataFetch(),"later missing data is fetched");
			assertTrue(peer.isLive());
		} finally {
			peer.close();
		}
	}

	/** DATA that runs out of memory as it is staged is dropped, and a good Order sent after it is merged. */
	@Test
	public void dataThatRunsOutOfMemoryIsDropped() throws Exception {
		FaultingStore store=new FaultingStore();
		Server peer=launchPeer(store,LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			Blob cell=Blobs.createRandom(400);
			store.failOnce(cell.getHash(),new OutOfMemoryError("simulated"));
			LocalConnection senderEnd=connect(peer);
			senderEnd.sendMessage(wire(Message.createDataMessage(List.of(cell),(int)CPoSConstants.MAX_MESSAGE_LENGTH)));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
			senderEnd.sendMessage(wire(Message.createBelief(Belief.create(good))));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertNull(store.failing,"staging the DATA ran out of memory");
			assertTrue(peer.isLive());
		} finally {
			peer.close();
		}
	}

	/**
	 * A Belief merge that overflows or runs out of memory reading a received Order drops
	 * that Order alone: the merge returns, and a good Order from another peer in the same
	 * Belief is still merged.
	 */
	@Test
	public void aMergeThatFailsDropsOnlyTheOrderItFailsOn() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			long ts=blockTime(peer);
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,ts));
			SignedData<Order> signed=order(REMOTE_A,1,block(REMOTE_A,ts));
			for (Error error: List.of(new StackOverflowError("simulated"),new OutOfMemoryError("simulated"))) {
				// The merge reads this Order's content from a store that fails
				AStore failing=new MemoryStore() {
					@Override
					public <T extends ACell> Ref<T> refForHash(Hash hash) {
						throw error;
					}
				};
				Ref<Order> unreadableRef=RefSoft.createForHash(signed.getValueRef().getHash(),failing);
				SignedData<Order> unreadable=SignedData.create(REMOTE_A.getAccountKey(),signed.getSignature(),unreadableRef);

				// Not started: this test thread runs the merge
				BeliefPropagator merger=new BeliefPropagator(peer);
				merger.belief=peer.getBelief();
				merger.maybeMergeBeliefs(Belief.create(unreadable,good));

				assertEquals(good,merger.belief.getOrders().get(REMOTE_B.getAccountKey()),error.toString());
				assertFalse(merger.belief.getOrders().containsKey(REMOTE_A.getAccountKey()),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A peer merges only Orders it holds in full, so data missing from its store during a
	 * Belief merge is fatal: the propagator closes the peer and does nothing more.
	 */
	@Test
	public void aMergeMissingDataClosesThePeer() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			// This peer's own Order, as if its store had lost the content
			SignedData<Order> own=order(LOCAL,1,block(LOCAL,blockTime(peer)));
			Ref<Order> missingRef=RefSoft.createForHash(own.getValueRef().getHash(),new MemoryStore());
			SignedData<Order> hollow=SignedData.create(LOCAL.getAccountKey(),own.getSignature(),missingRef);
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));

			// Not started: this test thread runs one iteration, which merges the good Order
			BeliefPropagator merger=new BeliefPropagator(peer);
			merger.belief=Belief.create(hollow);
			assertTrue(merger.queueBelief(Belief.create(good)));
			merger.loop();

			awaitShutdown(peer);
			assertFalse(peer.isRunning());
		} finally {
			peer.close();
		}
	}

	/**
	 * A transaction that fails the structure check is rejected as malformed, and later
	 * transactions are still processed.
	 */
	@Test
	public void aTransactionWithInvalidStructureIsRejected() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			Convex client=Convex.connect(peer,Init.GENESIS_ADDRESS,LOCAL);
			SignedData<ATransaction> tx=LOCAL.signData(
				Invoke.create(Init.GENESIS_ADDRESS,1,NodeServerTest.invalidVectorTree()));
			Result rejected=client.transact(tx).get(WAIT,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.FORMAT,rejected.getErrorCode());

			SignedData<ATransaction> next=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,"(+ 1 2)"));
			Result ok=client.transact(next).get(WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(3),ok.getValue(),ok.toString());
		} finally {
			peer.close();
		}
	}

	/**
	 * A staked peer's Order with part of a Block never sent is not merged until that
	 * part is fetched, here from a sender that never answers, on a peer with an
	 * in-memory store. A good Order from another peer in the same update is still
	 * merged.
	 */
	@Test
	public void anOrderMissingItsBlocksWaitsForThem() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			MemoryStore senderStore=new MemoryStore();
			long ts=blockTime(peer);
			// A Block's transactions are a branch of the Block, so they can be left out
			List<SignedData<ATransaction>> txs=new ArrayList<>();
			for (int i=1; i<=8; i++) txs.add(REMOTE_A.signData(Invoke.create(Init.GENESIS_ADDRESS,i,"*address*")));
			Block block=Block.create(ts,txs);
			SignedData<Block> signed=REMOTE_A.signData(block);
			AVector<SignedData<ATransaction>> absent=block.getTransactions();
			assertFalse(absent.isEmbedded());
			// Announced before, so the next update leaves it out; the sender never answers a fetch
			MissingBeliefDataTest.update(absent,senderStore);
			SignedData<Order> incomplete=order(REMOTE_A,1,signed);
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,ts));
			List<Message> sent=MissingBeliefDataTest.update(Belief.create(incomplete,good),senderStore);

			LocalConnection senderEnd=connect(peer);
			for (Message m: sent) senderEnd.sendMessage(wire(m));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertFalse(merged.getOrders().containsKey(REMOTE_A.getAccountKey()));
		} finally {
			peer.close();
		}
	}

	/**
	 * A deeply nested query form passes the structure check and gets a result, and the
	 * peer still answers queries after it.
	 */
	@Test
	public void aDeeplyNestedQueryIsAnswered() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			Convex client=Convex.connect(peer,Init.GENESIS_ADDRESS,LOCAL);
			ACell deep=nested(DEEP,new ArrayList<>());
			assertNotNull(client.query(deep).get(WAIT,TimeUnit.SECONDS));
			assertEquals(CVMLong.create(3),client.query("(+ 1 2)").get(WAIT,TimeUnit.SECONDS).getValue());
		} finally {
			peer.close();
		}
	}

	/**
	 * Transaction results are reported only for Blocks the peer has executed, so a peer
	 * whose State lags its finality point reports nothing for the Blocks in between.
	 */
	@Test
	public void resultsAreReportedOnlyForExecutedBlocks() throws Exception {
		Server server=launchPeer(LOCAL);
		try {
			Peer p=Peer.create(LOCAL,server.getPeer().getGenesisState());
			SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,"(+ 1 2)"));
			p=p.proposeBlock(Block.of(p.getTimestamp(),tx));
			p=p.mergeBeliefs().mergeBeliefs().mergeBeliefs().mergeBeliefs();
			assertEquals(1,p.getFinalityPoint());
			assertEquals(0,p.getStatePosition());

			// Not started, so only this test reports through it
			TransactionHandler handler=new TransactionHandler(server);
			handler.maybeReportTransactions(p);
			assertEquals(0,handler.reportedConsensusPoint);
			handler.maybeReportTransactions(p.updateState());
			assertEquals(1,handler.reportedConsensusPoint);
		} finally {
			server.close();
		}
	}

	/** A component whose first loop throws an Error, and whose later loops wait to be closed. */
	static AThreadedComponent throwingOnce(Server server, Error error, CompletableFuture<Void> secondLoop) {
		AtomicBoolean thrown=new AtomicBoolean();
		return new AThreadedComponent(server) {
			@Override
			protected void loop() throws InterruptedException {
				if (!thrown.getAndSet(true)) throw error;
				secondLoop.complete(null);
				new LinkedBlockingQueue<>().take();
			}

			@Override
			protected String getThreadName() {
				return "Test component";
			}
		};
	}

	/**
	 * An Error that is not a failure of the JVM itself, such as running out of memory, a
	 * stack overflow that has unwound or a linkage error, leaves a component and its peer
	 * running.
	 */
	@Test
	public void aComponentRunsOnAfterANonFatalError() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			for (Error error: List.of(new StackOverflowError(),new OutOfMemoryError("Test"),
					new NoClassDefFoundError("Test"),new Error("Unexpected"))) {
				CompletableFuture<Void> secondLoop=new CompletableFuture<>();
				AThreadedComponent component=throwingOnce(peer,error,secondLoop);
				component.start();
				secondLoop.get(WAIT,TimeUnit.SECONDS);
				component.close();
			}
			assertTrue(peer.isRunning());
		} finally {
			peer.close();
		}
	}

	/** A failure of the JVM itself in a component closes the peer. */
	@Test
	public void aFatalErrorClosesThePeer() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			throwingOnce(peer,new InternalError("Test"),new CompletableFuture<>()).start();
			awaitShutdown(peer);
			assertFalse(peer.isRunning());
		} finally {
			peer.close();
		}
	}

	/**
	 * A peer holds all of every Block it agrees, so a Block missing from its store when
	 * it computes its State is fatal and closes the peer.
	 */
	@Test
	public void aFinalisedBlockMissingFromTheStoreClosesThePeer() throws Exception {
		Server peer=launchPeer(LOCAL);
		try (MemoryStore hollowStore=new MemoryStore()) {
			SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(Init.GENESIS_ADDRESS,1,"*address*"));
			SignedData<Block> signed=LOCAL.signData(Block.of(blockTime(peer),tx));
			assertFalse(signed.isEmbedded());
			Order order=Order.create().append(signed).withConsensusPoints(new long[] {1,1,1,1}).withTimestamp(1);
			// Decoded alone, the Order holds its Block by hash only
			Order hollow=hollowStore.decode(order.getEncoding());
			assertClosedByBelief(peer,Belief.create(LOCAL.signData(hollow)));
		} finally {
			peer.close();
		}
	}

	/** A peer that cannot compute its State from the Belief it holds cannot proceed, so it closes. */
	@Test
	public void aStateThatCannotBeComputedClosesThePeer() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A);
		try {
			// Without an Order of its own the peer has no finality point to compute to
			assertClosedByBelief(peer,Belief.create(order(REMOTE_A,1,block(REMOTE_A,blockTime(peer)))));
		} finally {
			peer.close();
		}
	}

	/**
	 * Has a peer's CVM executor compute its State from only the given Belief, and checks
	 * the peer closes.
	 */
	static void assertClosedByBelief(Server peer, Belief belief) throws Exception {
		// Stop Belief updates, so the executor computes only the Belief given here
		BeliefPropagator propagator=peer.getBeliefPropagator();
		propagator.close();
		propagator.awaitStopped();
		peer.getCVMExecutor().queueUpdate(belief);
		awaitShutdown(peer);
		assertFalse(peer.isRunning());
	}

	/** Waits for a peer's shutdown sequence to complete. */
	static void awaitShutdown(Server peer) throws Exception {
		CompletableFuture<Void> shutdown=CompletableFuture.runAsync(() -> {
			try {
				peer.waitForShutdown();
			} catch (InterruptedException e) {
				throw new CompletionException(e);
			}
		});
		shutdown.get(WAIT,TimeUnit.SECONDS);
	}
}
