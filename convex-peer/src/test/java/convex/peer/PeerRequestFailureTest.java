package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.AArrayBlob;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.UpgradeError;
import convex.core.init.Init;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;

/**
 * Tests that a peer contains the failure of one external request, whatever is thrown,
 * unless the JVM itself has failed. Each failure is simulated: no test exhausts real
 * memory or stack.
 */
public class PeerRequestFailureTest {
	static final AKeyPair LOCAL=InvalidPeerDataTest.LOCAL;
	static final AKeyPair REMOTE_A=InvalidPeerDataTest.REMOTE_A;
	static final AKeyPair REMOTE_B=InvalidPeerDataTest.REMOTE_B;

	static SignedData<Block> block(AKeyPair kp, long timestamp) {
		return InvalidPeerDataTest.block(kp,timestamp);
	}

	@SafeVarargs
	static SignedData<Order> order(AKeyPair kp, long timestamp, SignedData<Block>... blocks) {
		return InvalidPeerDataTest.order(kp,timestamp,blocks);
	}

	static long blockTime(Server peer) {
		return InvalidPeerDataTest.blockTime(peer);
	}
	static final Address ORIGIN=Init.GENESIS_ADDRESS;

	/** Failures of one request that a peer contains: the JVM itself has not failed. */
	static final List<Error> CONTAINED=List.of(new StackOverflowError("simulated"),
		new OutOfMemoryError("simulated"),new NoClassDefFoundError("simulated"),
		new AssertionError("simulated"));

	/** A client connection that records results, or throws a chosen Error on every reply. */
	static final class ReplyConnection extends AConnection {
		final Error error;
		final ConcurrentHashMap<ACell,Result> results=new ConcurrentHashMap<>();
		/** Every result received, in order, for waiting on one sent from another thread. */
		final LinkedBlockingQueue<Result> received=new LinkedBlockingQueue<>();
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
				// Only by ID: a reply to a message that could not be decoded has none
				if (r.getID()!=null) results.put(r.getID(),r);
				received.add(r);
			}
			return true;
		}

		@Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("192.0.2.3",30000); }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed=true; }
		@Override public long getReceivedCount() { return 0; }
	}

	/**
	 * A query whose handling fails, here as its result is sent, fails alone: its
	 * connection is closed and the next query in the same batch is answered.
	 */
	@Test
	public void aQueryThatFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			for (Error error: CONTAINED) {
				// Not started: this test thread runs it
				QueryHandler handler=new QueryHandler(peer);
				ReplyConnection failing=new ReplyConnection(error);
				ReplyConnection healthy=new ReplyConnection(null);
				assertTrue(handler.offerQuery(Message.createQuery(1,"(+ 1 2)",ORIGIN).withConnection(failing)));
				assertTrue(handler.offerQuery(Message.createQuery(2,"(+ 2 3)",ORIGIN).withConnection(healthy)));
				handler.loop();
				assertEquals(CVMLong.create(5),healthy.result(2).getValue(),error.toString());
				assertTrue(failing.isClosed(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A data request whose handling fails, here as its response is sent, fails alone:
	 * its connection stays open and the next request in the same batch is answered.
	 */
	@Test
	public void aDataRequestThatFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			for (Error error: CONTAINED) {
				QueryHandler handler=new QueryHandler(peer);
				ReplyConnection failing=new ReplyConnection(error);
				ReplyConnection healthy=new ReplyConnection(null);
				Hash held=peer.getBelief().getHash();
				assertTrue(handler.offerQuery(Message.createDataRequest(CVMLong.create(1),held).withConnection(failing)));
				assertTrue(handler.offerQuery(Message.createQuery(2,"(+ 2 3)",ORIGIN).withConnection(healthy)));
				handler.loop();
				assertEquals(CVMLong.create(5),healthy.result(2).getValue(),error.toString());
				assertFalse(failing.isClosed(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/** Failures of the peer's own handling of client transactions, each answered as load or a peer error. */
	static final List<Throwable> FAILURES=List.of(new OutOfMemoryError("simulated"),new StackOverflowError("simulated"),
		new NoClassDefFoundError("simulated"),new AssertionError("simulated"),new IllegalStateException("simulated"));

	/** A store that throws a chosen failure once, the first time it is asked to store a chosen kind of cell. */
	static final class FaultingStore extends MemoryStore {
		final AtomicReference<Predicate<Ref<?>>> failing=new AtomicReference<>();
		volatile Throwable failure;

		void failOnce(Hash hash, Throwable e) {
			failOnce(ref -> hash.equals(ref.getHash()),e);
		}

		void failOnce(Predicate<Ref<?>> which, Throwable e) {
			failure=e;
			failing.set(which);
		}

		boolean fired() {
			return failing.get()==null;
		}

		@Override
		public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> noveltyHandler) {
			Predicate<Ref<?>> which=failing.get();
			// compareAndSet: fails once even if two threads store a matching cell together
			if ((which!=null)&&which.test(ref)&&failing.compareAndSet(which,null)) {
				throw Utils.<RuntimeException>sneakyThrow(failure);
			}
			return super.storeTopRef(ref,status,noveltyHandler);
		}
	}

	static boolean isSignedBlock(Ref<?> ref) {
		return (ref.getValue() instanceof SignedData<?> sd)&&(sd.getValue() instanceof Block);
	}

	/** The code a client gets for a request whose handling fails with the given untyped failure: never FORMAT. */
	static Keyword failureCode(Throwable failure) {
		if (failure instanceof OutOfMemoryError) return ErrorCodes.LOAD;
		if (failure instanceof StackOverflowError) return ErrorCodes.LIMIT;
		return ErrorCodes.PEER;
	}

	/**
	 * A transaction whose intake fails is rejected alone: running out of memory is
	 * answered as load, a stack overflow as a limit and any other failure as a peer
	 * error, never as bad format. Later transactions are still executed.
	 */
	@Test
	public void aTransactionThatFailsIsRejectedWithItsCause() throws Exception {
		FaultingStore store=new FaultingStore();
		Server peer=InvalidPeerDataTest.launchPeer(store,LOCAL);
		try {
			Convex client=Convex.connect(peer,ORIGIN,LOCAL);
			int i=0;
			for (Throwable failure: FAILURES) {
				SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 "+(i++)+")"));
				// The handler persists each transaction before queueing it
				store.failOnce(tx.getHash(),failure);
				Result rejected=client.transact(tx).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
				assertEquals(failureCode(failure),rejected.getErrorCode(),failure+": "+rejected);
				assertTrue(store.fired(),failure+" fired");
			}
			SignedData<ATransaction> next=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 2 3)"));
			Result ok=client.transact(next).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(5),ok.getValue(),ok.toString());
		} finally {
			peer.close();
		}
	}

	/**
	 * A Block that cannot be produced fails its transactions alone: running out of memory
	 * is answered as load and any other failure as a peer error. Later transactions are
	 * still executed.
	 */
	@Test
	public void aBlockThatCannotBeProducedFailsItsTransactions() throws Exception {
		FaultingStore store=new FaultingStore();
		Server peer=InvalidPeerDataTest.launchPeer(store,LOCAL);
		try {
			Convex client=Convex.connect(peer,ORIGIN,LOCAL);
			int i=0;
			for (Throwable failure: FAILURES) {
				SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 "+(i++)+")"));
				// The peer persists each Block it produces, before proposing it
				store.failOnce(PeerRequestFailureTest::isSignedBlock,failure);
				Result rejected=client.transact(tx).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
				Keyword expected=(failure instanceof OutOfMemoryError)?ErrorCodes.LOAD:ErrorCodes.PEER;
				assertEquals(expected,rejected.getErrorCode(),failure+": "+rejected);
				assertTrue(store.fired(),failure+" fired");
			}
			SignedData<ATransaction> next=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 2 3)"));
			Result ok=client.transact(next).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(5),ok.getValue(),ok.toString());
		} finally {
			peer.close();
		}
	}

	/**
	 * A key pair that fails once with a chosen failure the next time it signs a chosen
	 * value, and otherwise signs as another. A proposal signs its Order through signData
	 * and a merge through sign.
	 */
	static final class FailingKeyPair extends AKeyPair {
		final AKeyPair kp;
		final Predicate<ACell> which;
		final AtomicReference<Throwable> failure=new AtomicReference<>();

		FailingKeyPair(AKeyPair kp, Predicate<ACell> which) {
			this.kp=kp;
			this.which=which;
		}

		@Override
		public <R extends ACell> SignedData<R> signData(R value) {
			// Only the chosen values: the peer signs other values with the same key pair
			Throwable t=which.test(value)?failure.getAndSet(null):null;
			if (t!=null) throw Utils.<RuntimeException>sneakyThrow(t);
			return kp.signData(value);
		}

		@Override public AccountKey getAccountKey() { return kp.getAccountKey(); }
		@Override public boolean equals(AKeyPair other) { return kp.equals(other); }
		@Override
		public ASignature sign(AArrayBlob message) {
			// Whatever the value: in these tests only the merge's own Order reaches this
			Throwable t=failure.getAndSet(null);
			if (t!=null) throw Utils.<RuntimeException>sneakyThrow(t);
			return kp.sign(message);
		}
		@Override public Blob getSeed() { return kp.getSeed(); }
	}

	/**
	 * Launches a single peer with the given key pair and its consensus halted before
	 * launch, so its own propagator never merges or takes a transaction from the queue:
	 * that work is left to the test thread.
	 */
	static Server launchHaltedPeer(AKeyPair kp) throws Exception {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STATE,Init.createState(List.of(kp.getAccountKey())));
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,new MemoryStore());
		// Each round produces a Block at once, however soon after the last
		config.put(Keywords.MIN_BLOCK_TIME,0);
		Server peer=Server.create(config);
		peer.haltConsensus(UpgradeError.missing(99));
		peer.launch();
		return peer;
	}

	/**
	 * A Block that cannot be proposed fails its transactions' clients, and the failure
	 * still reaches the caller: running out of memory is answered as load and any other
	 * failure as a peer error. The client can then resubmit the same transaction.
	 */
	@Test
	public void aBlockThatCannotBeProposedFailsItsTransactions() throws Exception {
		FailingKeyPair kp=new FailingKeyPair(LOCAL,v -> v instanceof Order);
		Server peer=launchHaltedPeer(kp);
		try {
			LinkedBlockingQueue<SignedData<ATransaction>> queued=new LinkedBlockingQueue<>();
			peer.getTransactionHandler().setRequestObserver(queued::add);
			Convex client=Convex.connect(peer,ORIGIN,LOCAL);
			// Not started: this test thread runs it
			BeliefPropagator proposer=new BeliefPropagator(peer);
			proposer.belief=peer.getBelief();
			int i=0;
			for (Throwable failure: FAILURES) {
				SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 "+(i++)+")"));
				CompletableFuture<Result> result=client.transact(tx);
				assertEquals(tx,queued.poll(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS));

				kp.failure.set(failure);
				assertThrows(failure.getClass(),() -> proposer.maybeUpdateBelief(null));

				Keyword expected=(failure instanceof OutOfMemoryError)?ErrorCodes.LOAD:ErrorCodes.PEER;
				Result rejected=result.get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
				assertEquals(expected,rejected.getErrorCode(),failure+": "+rejected);
				// Its interest is gone, so the same transaction is accepted again. It stays
				// queued and fails with the next round's Block: a proposal that succeeded
				// would change the Order, and the next merge would then sign it first.
				client.transact(tx);
				assertEquals(tx,queued.poll(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS),failure.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A Belief update observer that fails costs the update nothing: the Block is still
	 * proposed and the update returns as usual.
	 */
	@Test
	public void aFailingBeliefObserverCostsTheUpdateNothing() throws Exception {
		Server peer=launchHaltedPeer(LOCAL);
		try {
			LinkedBlockingQueue<SignedData<ATransaction>> queued=new LinkedBlockingQueue<>();
			peer.getTransactionHandler().setRequestObserver(queued::add);
			Convex client=Convex.connect(peer,ORIGIN,LOCAL);
			// Not started: this test thread runs it
			BeliefPropagator proposer=new BeliefPropagator(peer);
			proposer.belief=peer.getBelief();
			int i=0;
			for (Error error: CONTAINED) {
				proposer.setBeliefUpdateObserver(b -> {throw error;});
				SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 "+(i++)+")"));
				client.transact(tx);
				assertEquals(tx,queued.poll(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS),error.toString());
				assertTrue(proposer.maybeUpdateBelief(null),error+": the Block is proposed");
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A response observer that fails costs nothing else: each client still gets its
	 * transaction's result, and the peer keeps running.
	 */
	@Test
	public void aFailingResponseObserverCostsNothingElse() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			Convex client=Convex.connect(peer,ORIGIN,LOCAL);
			long seq=1;
			for (Throwable failure: FAILURES) {
				peer.getTransactionHandler().setResponseObserver((sd,r) -> {
					throw Utils.<RuntimeException>sneakyThrow(failure);
				});
				SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(ORIGIN,seq,"(+ 1 "+seq+")"));
				Result r=client.transact(tx).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
				assertEquals(CVMLong.create(1+seq),r.getValue(),failure+": "+r);
				seq++;
			}
			assertTrue(peer.isRunning());
		} finally {
			peer.close();
		}
	}

	/**
	 * A result that cannot be produced, here because the Block's results have no entry
	 * for the transaction, is answered as a peer error.
	 */
	@Test
	public void aResultThatCannotBeProducedIsAPeerError() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			// Not started: this test thread runs it
			TransactionHandler handler=new TransactionHandler(peer);
			ReplyConnection client=new ReplyConnection(null);
			SignedData<ATransaction> tx=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 2)"));
			assertTrue(handler.offerTransaction(Message.createTransaction(1,tx).withConnection(client)));
			handler.loop(); // registers the client's interest
			Block block=Block.of(InvalidPeerDataTest.blockTime(peer),tx);
			handler.reportTransactions(block,BlockResult.create(peer.getPeer().getConsensusState(),new Result[0]),0);
			Result r=client.received.poll(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertNotNull(r,"a result is sent");
			assertEquals(ErrorCodes.PEER,r.getErrorCode(),r.toString());
		} finally {
			peer.close();
		}
	}

	/**
	 * A result that cannot be sent costs no other client its result: the next client in
	 * the same Block still gets its own.
	 */
	@Test
	public void aResultThatCannotBeDeliveredLeavesTheOthers() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			SignedData<ATransaction> a=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 2)"));
			SignedData<ATransaction> b=LOCAL.signData(Invoke.create(ORIGIN,2,"(+ 2 3)"));
			Block block=Block.of(InvalidPeerDataTest.blockTime(peer),a,b);
			BlockResult br=BlockResult.create(peer.getPeer().getConsensusState(),
				new Result[] {Result.value(CVMLong.create(3)),Result.value(CVMLong.create(5))});
			for (Error error: CONTAINED) {
				TransactionHandler handler=new TransactionHandler(peer);
				ReplyConnection failing=new ReplyConnection(error);
				ReplyConnection healthy=new ReplyConnection(null);
				assertTrue(handler.offerTransaction(Message.createTransaction(1,a).withConnection(failing)));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,b).withConnection(healthy)));
				handler.loop(); // registers both interests, sending nothing
				handler.reportTransactions(block,br,0);
				Result r=healthy.received.poll(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
				assertNotNull(r,error+": the healthy client gets its result");
				assertEquals(CVMLong.create(5),r.getValue(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/** DATA whose staging fails is dropped alone: a good Order sent after it is still merged. */
	@Test
	public void dataThatFailsToStageIsDroppedAlone() throws Exception {
		for (Error error: CONTAINED) {
			Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
			try {
				peer.getBeliefPropagator().setDataStageObserver(m -> {
					throw error;
				});
				Message data=Message.createDataMessage(List.of(Blob.wrap(new byte[200])),(int)CPoSConstants.MAX_MESSAGE_LENGTH);
				InvalidPeerDataTest.connect(peer).sendMessage(InvalidPeerDataTest.wire(data));
				SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
				InvalidPeerDataTest.checkGoodOrderMerges(peer,good,Belief.create(good));
			} finally {
				peer.close();
			}
		}
	}

	/** A Belief whose one entry, for a staked peer, is bound to a store that throws the given Error when read. */
	@SuppressWarnings({"unchecked","rawtypes"})
	static Belief unreadableBelief(Error error) {
		SignedData<Order> signed=order(REMOTE_A,1,block(REMOTE_A,1));
		AStore failing=new MemoryStore() {
			@Override
			public <T extends ACell> Ref<T> refForHash(Hash hash) {
				throw error;
			}
		};
		Ref<SignedData<Order>> unreadable=RefSoft.createForHash(signed.getHash(),failing);
		return Belief.create((Index)Index.create(MapEntry.fromRefs(REMOTE_A.getAccountKey().getRef(),unreadable)));
	}

	/** A Belief message whose Orders cannot be read is rejected alone, and nothing is thrown. */
	@Test
	public void aBeliefMessageThatFailsIsRejectedAlone() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			for (Error error: CONTAINED) {
				// Not started: this test thread runs it
				BeliefPropagator propagator=new BeliefPropagator(peer);
				Message m=Message.createBelief(unreadableBelief(error)).withConnection(new ReplyConnection(null));
				assertFalse(propagator.mergeBeliefMessage(new HashMap<>(),m),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/** A polled Belief whose Orders cannot be read is dropped alone: a good Order is still merged. */
	@Test
	public void aPolledBeliefThatFailsIsDroppedAlone() throws Exception {
		for (Error error: CONTAINED) {
			Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
			try {
				assertTrue(peer.queueBelief(unreadableBelief(error)));
				SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
				InvalidPeerDataTest.checkGoodOrderMerges(peer,good,Belief.create(good));
			} finally {
				peer.close();
			}
		}
	}

	/** An Order whose processing fails is rejected alone: the other Order in the same Belief is merged. */
	@Test
	public void anOrderThatFailsIsRejectedAlone() throws Exception {
		for (Error error: CONTAINED) {
			Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
			try {
				peer.getBeliefPropagator().setOrderUpdateObserver(so -> {
					if (REMOTE_A.getAccountKey().equals(so.getAccountKey())) throw error;
				});
				SignedData<Order> good=order(REMOTE_B,1,block(REMOTE_B,blockTime(peer)));
				SignedData<Order> failing=order(REMOTE_A,1,block(REMOTE_A,blockTime(peer)));
				Belief merged=InvalidPeerDataTest.checkGoodOrderMerges(peer,good,Belief.create(good,failing));
				assertNull(merged.getOrders().get(REMOTE_A.getAccountKey()),error.toString());
			} finally {
				peer.close();
			}
		}
	}

	/**
	 * A merge that fails drops its batch and keeps the peer's Belief, and nothing is
	 * thrown. Here it fails signing the peer's own Order, which a merge does once a
	 * proposed Block moves consensus on.
	 */
	@Test
	public void aMergeThatFailsDropsItsBatch() throws Exception {
		FailingKeyPair kp=new FailingKeyPair(LOCAL,v -> v instanceof Order);
		Server peer=launchHaltedPeer(kp);
		try {
			// Signed with the key itself, so the failure is left for the merge
			Belief proposed=peer.getBelief().proposeBlock(LOCAL,LOCAL.signData(Block.of(blockTime(peer))));
			for (Error error: CONTAINED) {
				// Not started: this test thread runs it
				BeliefPropagator merger=new BeliefPropagator(peer);
				merger.belief=proposed;
				kp.failure.set(error);
				assertFalse(merger.maybeMergeBeliefs(proposed),error.toString());
				assertNull(kp.failure.get(),error+" fired");
				assertSame(proposed,merger.belief,error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * An update from a staked peer whose Order lacks part of a Block, sent with a good
	 * Order from another peer.
	 */
	@SuppressWarnings("unchecked")
	static List<Message> updateMissingABlock(long ts, int firstSequence, SignedData<Order> good,
			MemoryStore senderStore) throws Exception {
		List<SignedData<ATransaction>> txs=new ArrayList<>();
		for (int i=0; i<8; i++) {
			txs.add(REMOTE_A.signData(Invoke.create(ORIGIN,firstSequence+i,"*address*")));
		}
		Block block=Block.create(ts,txs);
		// Announced before, so the next update leaves it out and the peer must fetch it
		MissingBeliefDataTest.update(block.getTransactions(),senderStore);
		SignedData<Order> incomplete=order(REMOTE_A,ts,REMOTE_A.signData(block));
		return MissingBeliefDataTest.update(Belief.create(incomplete,good),senderStore);
	}

	/**
	 * A fetch of missing Order data that fails is dropped alone: a good Order in the same
	 * update is still merged, and missing data in a later update is fetched.
	 */
	@Test
	public void aFetchThatFailsIsDroppedAlone() throws Exception {
		for (Error error: CONTAINED) {
			FaultingStore store=new FaultingStore();
			Server peer=InvalidPeerDataTest.launchPeer(store,LOCAL,REMOTE_A,REMOTE_B);
			try {
				BlockingQueue<Belief> updates=InvalidPeerDataTest.ownOrderUpdates(peer);
				MemoryStore senderStore=new MemoryStore();
				long ts=blockTime(peer);
				SignedData<Order> good=order(REMOTE_B,ts,block(REMOTE_B,ts));
				List<Message> sent=updateMissingABlock(ts,1,good,senderStore);
				for (Message m: sent) {
					// Storing the update for its fetch is the first time the peer stores it
					if (m.getType()==MessageType.BELIEF) store.failOnce(m.getPayload().getHash(),error);
				}
				var senderEnd=InvalidPeerDataTest.connect(peer);
				for (Message m: sent) senderEnd.sendMessage(InvalidPeerDataTest.wire(m));

				Belief merged=updates.poll(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
				assertNotNull(merged,error+": the good Order is merged");
				assertEquals(good,merged.getOrders().get(REMOTE_B.getAccountKey()),error.toString());
				assertTrue(store.fired(),error+" fired");
				assertNull(peer.getBeliefPropagator().getMissingDataFetch(),error+": no fetch started");

				SignedData<Order> later=order(REMOTE_B,ts+1,block(REMOTE_B,ts),block(REMOTE_B,ts+1));
				var laterEnd=InvalidPeerDataTest.connect(peer);
				for (Message m: updateMissingABlock(ts+1,9,later,senderStore)) laterEnd.sendMessage(InvalidPeerDataTest.wire(m));
				assertNotNull(updates.poll(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS),error+": the later good Order is merged");
				assertNotNull(peer.getBeliefPropagator().getMissingDataFetch(),error+": later missing data is fetched");
			} finally {
				peer.close();
			}
		}
	}

	/** A Belief poll that fails is dropped alone, and nothing is thrown into the manager's loop. */
	@Test
	public void aBeliefPollThatFailsIsDroppedAlone() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			for (Error error: CONTAINED) {
				AtomicInteger polls=new AtomicInteger();
				Convex failing=new ConvexLocal(peer,ORIGIN,LOCAL) {
					@Override
					public Result requestStatusSync(long timeout) {
						polls.incrementAndGet();
						throw error;
					}
				};
				peer.getConnectionManager().pollBelief(REMOTE_A.getAccountKey(),failing);
				assertEquals(1,polls.get(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A connection attempt that fails fails its future alone: nothing is thrown to the
	 * caller, such as the connection manager's loop.
	 */
	@Test
	public void aConnectionThatFailsFailsItsFuture() throws Exception {
		// Once connected, verification signs a challenge vector with the peer's key pair
		FailingKeyPair kp=new FailingKeyPair(LOCAL,v -> v instanceof AVector);
		Server peer=launchHaltedPeer(kp);
		try {
			for (Error error: CONTAINED) {
				kp.failure.set(error);
				CompletableFuture<Convex> connecting=peer.getConnectionManager().connectToPeer(peer.getHostAddress());
				// Already complete: the failure is in the caller's thread
				assertTrue(connecting.isCompletedExceptionally(),error.toString());
				assertSame(error,connecting.exceptionNow());
			}
		} finally {
			peer.close();
		}
	}

	/** A challenge to the local peer from REMOTE_A, in no context. */
	static Message challenge(long id) {
		return Message.createChallenge(id,Message.signChallenge(REMOTE_A,Hash.EMPTY_HASH,LOCAL.getAccountKey(),null));
	}

	/**
	 * A message whose handling fails is answered alone, with the failure's code, and
	 * nothing is thrown: the next message is answered as usual. Here the peer's key pair
	 * fails in signing its answer to a challenge.
	 */
	@Test
	public void aMessageWhoseHandlingFailsIsAnsweredAlone() throws Exception {
		FailingKeyPair kp=new FailingKeyPair(LOCAL,v -> v instanceof AVector);
		Server peer=launchHaltedPeer(kp);
		try {
			for (Error error: CONTAINED) {
				kp.failure.set(error);
				ReplyConnection client=new ReplyConnection(null);
				assertNull(peer.processMessage(challenge(1).withConnection(client)),error.toString());
				assertEquals(failureCode(error),client.result(1).getErrorCode(),error.toString());

				ReplyConnection next=new ReplyConnection(null);
				peer.processMessage(challenge(2).withConnection(next));
				assertFalse(next.result(2).isError(),error+": the next challenge is answered");
			}
		} finally {
			peer.close();
		}
	}

	/** A failure of the JVM itself while handling a message is not contained. */
	@Test
	public void aJvmFailureInDispatchIsNotContained() throws Exception {
		FailingKeyPair kp=new FailingKeyPair(LOCAL,v -> v instanceof AVector);
		Server peer=launchHaltedPeer(kp);
		try {
			kp.failure.set(new InternalError("simulated"));
			Message m=challenge(1).withConnection(new ReplyConnection(null));
			assertThrows(InternalError.class,() -> peer.processMessage(m));
		} finally {
			peer.close();
		}
	}

	/** A message whose decoding fails is answered alone, with the failure's code, and nothing is thrown. */
	@Test
	public void aMessageThatFailsToDecodeIsAnsweredAlone() throws Exception {
		AtomicReference<Blob> failOn=new AtomicReference<>();
		AtomicReference<Throwable> failure=new AtomicReference<>();
		MemoryStore store=new MemoryStore() {
			@Override
			public <T extends ACell> T decodeMultiCell(Blob data) throws BadFormatException {
				// Only the test's message: the peer may decode others meanwhile
				if (data.equals(failOn.get())) throw Utils.<RuntimeException>sneakyThrow(failure.get());
				return super.decodeMultiCell(data);
			}
		};
		Server peer=InvalidPeerDataTest.launchPeer(store,LOCAL);
		try {
			for (Error error: CONTAINED) {
				ReplyConnection client=new ReplyConnection(null);
				Message m=InvalidPeerDataTest.wire(Message.createStatusRequest(1)).withConnection(client);
				failure.set(error);
				failOn.set(m.getMessageData());
				assertNull(peer.deliverMessage(m),error.toString());
				assertEquals(failureCode(error),client.received.poll().getErrorCode(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/** A receive observer that fails costs the message nothing: it is still answered. */
	@Test
	public void aFailingReceiveObserverCostsNothing() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			for (Error error: CONTAINED) {
				peer.setMessageReceiveObserver(m -> {throw error;});
				ReplyConnection client=new ReplyConnection(null);
				assertNull(peer.deliverMessage(Message.createStatusRequest(1).withConnection(client)),error.toString());
				assertFalse(client.result(1).isError(),error.toString());
			}
		} finally {
			peer.close();
		}
	}

	/** A transaction message whose third element is not a signed value is badly formatted. */
	@Test
	public void aTransactionMessageWithoutASignedValueIsBadlyFormatted() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			// Not started: this test thread runs it
			TransactionHandler handler=new TransactionHandler(peer);
			ReplyConnection client=new ReplyConnection(null);
			Message m=Message.create(MessageType.TRANSACT,Vectors.of(MessageTag.TRANSACT,CVMLong.create(1),CVMLong.ONE));
			assertTrue(handler.offerTransaction(m.withConnection(client)));
			handler.loop();
			assertEquals(ErrorCodes.FORMAT,client.result(1).getErrorCode());
		} finally {
			peer.close();
		}
	}

	/**
	 * A transaction whose rejection fails as it is sent, in the cheap checks or the
	 * signature check, fails alone: a good transaction in the same batch is still
	 * queued, with no error sent to its client.
	 */
	@Test
	public void aTransactionWhoseReplyFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			SignedData<ATransaction> good=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 2)"));
			// Fails the cheap checks: there is no such origin account
			SignedData<ATransaction> noOrigin=LOCAL.signData(Invoke.create(Address.create(999999),1,"(+ 1 2)"));
			// Fails the signature check: another transaction's signature
			SignedData<ATransaction> badSignature=SignedData.create(LOCAL.getAccountKey(),good.getSignature(),
				Invoke.create(ORIGIN,1,"(+ 2 2)").getRef());
			for (Error error: CONTAINED) {
				TransactionHandler handler=new TransactionHandler(peer);
				ReplyConnection healthy=new ReplyConnection(null);
				assertTrue(handler.offerTransaction(Message.createTransaction(1,noOrigin).withConnection(new ReplyConnection(error))));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,badSignature).withConnection(new ReplyConnection(error))));
				assertTrue(handler.offerTransaction(Message.createTransaction(3,good).withConnection(healthy)));
				handler.loop();
				assertTrue(healthy.results.isEmpty(),error+": "+healthy.results);
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A duplicate submission whose reply fails as it is sent fails alone: the first
	 * submission and a later transaction in the same batch are still queued.
	 */
	@Test
	public void aDuplicateTransactionWhoseReplyFailsLeavesTheRestOfItsBatch() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			SignedData<ATransaction> a=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 2)"));
			SignedData<ATransaction> b=LOCAL.signData(Invoke.create(ORIGIN,2,"(+ 2 3)"));
			for (Error error: CONTAINED) {
				// Not started: this test thread runs it
				TransactionHandler handler=new TransactionHandler(peer);
				ReplyConnection first=new ReplyConnection(null);
				ReplyConnection other=new ReplyConnection(null);
				assertTrue(handler.offerTransaction(Message.createTransaction(1,a).withConnection(first)));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,a).withConnection(new ReplyConnection(error))));
				assertTrue(handler.offerTransaction(Message.createTransaction(3,b).withConnection(other)));
				handler.loop();
				assertEquals(List.of(a,b),List.copyOf(handler.transactionQueue),error.toString());
				assertTrue(first.results.isEmpty(),error+": "+first.results);
				assertTrue(other.results.isEmpty(),error+": "+other.results);
			}
		} finally {
			peer.close();
		}
	}

	/**
	 * A request observer that fails costs nothing else: the transaction it observed and
	 * a later one in the same batch are both queued, and neither client gets an error.
	 */
	@Test
	public void aFailingRequestObserverLeavesTheRestOfItsBatch() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			SignedData<ATransaction> a=LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 1 2)"));
			SignedData<ATransaction> b=LOCAL.signData(Invoke.create(ORIGIN,2,"(+ 2 3)"));
			for (Error error: CONTAINED) {
				TransactionHandler handler=new TransactionHandler(peer);
				handler.setRequestObserver(sd -> {
					if (sd.equals(a)) throw error;
				});
				ReplyConnection first=new ReplyConnection(null);
				ReplyConnection other=new ReplyConnection(null);
				assertTrue(handler.offerTransaction(Message.createTransaction(1,a).withConnection(first)));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,b).withConnection(other)));
				handler.loop();
				assertEquals(List.of(a,b),List.copyOf(handler.transactionQueue),error.toString());
				assertTrue(first.results.isEmpty(),error+": "+first.results);
				assertTrue(other.results.isEmpty(),error+": "+other.results);
			}
		} finally {
			peer.close();
		}
	}

	/** A failure of the JVM itself during transaction intake is not contained. */
	@Test
	public void aJvmFailureDuringIntakeIsNotContained() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			TransactionHandler handler=new TransactionHandler(peer);
			SignedData<ATransaction> noOrigin=LOCAL.signData(Invoke.create(Address.create(999999),1,"(+ 1 2)"));
			ReplyConnection failing=new ReplyConnection(new InternalError("simulated"));
			assertTrue(handler.offerTransaction(Message.createTransaction(1,noOrigin).withConnection(failing)));
			assertThrows(InternalError.class,handler::loop);
		} finally {
			peer.close();
		}
	}

	/** A failure of the JVM itself while answering a query is not contained. */
	@Test
	public void aJvmFailureAnsweringAQueryIsNotContained() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			QueryHandler handler=new QueryHandler(peer);
			ReplyConnection failing=new ReplyConnection(new InternalError("simulated"));
			assertTrue(handler.offerQuery(Message.createQuery(1,"(+ 1 2)",ORIGIN).withConnection(failing)));
			assertThrows(InternalError.class,handler::loop);
		} finally {
			peer.close();
		}
	}
}
