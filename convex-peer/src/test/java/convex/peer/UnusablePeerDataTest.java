package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import convex.node.NodeServerTest;

/**
 * A peer that receives data it cannot use, from a staked peer or any connection,
 * drops that data and keeps taking part in consensus (CAD051: malformed data fails
 * closed, and parsing and merge paths never throw into the peer loop).
 */
public class UnusablePeerDataTest {
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
	 * A signed value that is not an Order, from a staked peer, is dropped. A good Order
	 * from another peer in the same Belief is still merged.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	@Test
	public void aSignedValueThatIsNotAnOrderIsDropped() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
			SignedData notAnOrder=REMOTE_A.signData(CVMLong.ONE);
			Belief received=Belief.create(good,notAnOrder);

			connect(peer).sendMessage(wire(Message.createBelief(received)));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertFalse(merged.getOrders().containsKey(REMOTE_A.getAccountKey()));
		} finally {
			peer.close();
		}
	}

	/**
	 * A staked peer's Order that the merge cannot use, here because a Block's
	 * transactions are not a vector, is dropped. A good Order from another peer in the
	 * same Belief is still merged.
	 */
	@Test
	public void anOrderTheMergeCannotUseIsDropped() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			long ts=blockTime(peer);
			Block malformed=Block.create(Vectors.of(CVMLong.create(ts),CVMLong.ONE));
			SignedData<Order> unusable=order(REMOTE_A,1,REMOTE_A.signData(malformed));
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
	 * A staked peer's Order that cannot be read is dropped on its own: another peer's
	 * good Order in the same Belief is still merged.
	 */
	@Test
	public void anUnreadableOrderIsDroppedOnItsOwn() throws Exception {
		Server peer=launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			long ts=blockTime(peer);
			LocalConnection senderEnd=connect(peer);
			SignedData<Order> first=order(REMOTE_A,1,block(REMOTE_A,ts));
			senderEnd.sendMessage(wire(Message.createBelief(Belief.create(first))));
			assertNotNull(updates.poll(WAIT,TimeUnit.SECONDS),"the first Order is merged");

			// Same timestamp, so comparing it with the first reads its Blocks, which are not a vector
			Order unreadable=Order.create(Vectors.of(CVMLong.create(1),Vectors.createLongs(1,0,0,0),CVMLong.ONE));
			SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,ts));
			senderEnd.sendMessage(wire(Message.createBelief(Belief.create(REMOTE_A.signData(unreadable),good))));

			Belief merged=updates.poll(WAIT,TimeUnit.SECONDS);
			assertNotNull(merged,"the good Order is merged");
			assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()));
			assertEquals(first,merged.getOrders().get(REMOTE_A.getAccountKey()));
		} finally {
			peer.close();
		}
	}

	/**
	 * A staked peer's Order with part of a Block never sent is not merged until that
	 * part is fetched, on a peer with an in-memory store. A good Order from another
	 * peer in the same update is still merged.
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
	 * Deeply nested DATA from any connection that the peer cannot store is dropped. A
	 * good Order sent after it is still merged.
	 */
	@Test
	public void deeplyNestedDataIsDropped() throws Exception {
		deeplyNestedDataIsDropped(new MemoryStore());
	}

	@Test
	public void deeplyNestedDataIsDroppedOnEtch() throws Exception {
		try (EtchStore store=EtchStore.createTemp()) {
			deeplyNestedDataIsDropped(store);
		}
	}

	private void deeplyNestedDataIsDropped(AStore store) throws Exception {
		Server peer=launchPeer(store,LOCAL,REMOTE_A,REMOTE_B);
		try {
			BlockingQueue<Belief> updates=ownOrderUpdates(peer);
			ArrayList<ACell> branches=new ArrayList<>();
			ACell nested=nested(DEEP,branches);
			ACell top=Vectors.of(nested,nested,nested,nested,nested);
			branches.add(top);
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

	/**
	 * A deeply nested query gets a result, and the peer still answers queries after it.
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
	 * An Error that is not fatal to the JVM, such as a stack overflow that has unwound,
	 * leaves a component and its peer running.
	 */
	@Test
	public void aComponentRunsOnAfterANonFatalError() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			for (Error error: List.of(new StackOverflowError(),new Error("Unexpected"))) {
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

	/** A JVM failure such as running out of memory in a component closes the peer. */
	@Test
	public void aFatalErrorClosesThePeer() throws Exception {
		Server peer=launchPeer(LOCAL);
		try {
			throwingOnce(peer,new OutOfMemoryError("Test"),new CompletableFuture<>()).start();
			CompletableFuture<Void> shutdown=CompletableFuture.runAsync(() -> {
				try {
					peer.waitForShutdown();
				} catch (InterruptedException e) {
					throw new CompletionException(e);
				}
			});
			shutdown.get(WAIT,TimeUnit.SECONDS);
			assertFalse(peer.isRunning());
		} finally {
			peer.close();
		}
	}
}
