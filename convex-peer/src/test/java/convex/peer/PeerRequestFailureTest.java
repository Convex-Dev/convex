package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.AArrayBlob;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.UpgradeError;
import convex.core.init.Init;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;

/**
 * Tests that a peer contains the failure of one external request, whatever is thrown,
 * unless the JVM itself has failed. Each failure is simulated: no test exhausts real
 * memory or stack.
 */
public class PeerRequestFailureTest {
	static final AKeyPair LOCAL=InvalidPeerDataTest.LOCAL;
	static final Address ORIGIN=Init.GENESIS_ADDRESS;

	/** Failures of one request that a peer contains: the JVM itself has not failed. */
	static final List<Error> CONTAINED=List.of(new StackOverflowError("simulated"),
		new OutOfMemoryError("simulated"),new NoClassDefFoundError("simulated"),
		new AssertionError("simulated"));

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

	/** The code a client gets for a transaction whose intake fails with the given failure: never FORMAT. */
	static Keyword intakeCode(Throwable failure) {
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
				assertEquals(intakeCode(failure),rejected.getErrorCode(),failure+": "+rejected);
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

	/** A key pair that fails once with a chosen failure the next time it signs an Order, and otherwise signs as another. */
	static final class OrderFailingKeyPair extends AKeyPair {
		final AKeyPair kp;
		final AtomicReference<Throwable> failure=new AtomicReference<>();

		OrderFailingKeyPair(AKeyPair kp) {
			this.kp=kp;
		}

		@Override
		public <R extends ACell> SignedData<R> signData(R value) {
			// Only an Order: Block production signs Blocks with the same key pair
			Throwable t=(value instanceof Order)?failure.getAndSet(null):null;
			if (t!=null) throw Utils.<RuntimeException>sneakyThrow(t);
			return kp.signData(value);
		}

		@Override public AccountKey getAccountKey() { return kp.getAccountKey(); }
		@Override public boolean equals(AKeyPair other) { return kp.equals(other); }
		@Override public ASignature sign(AArrayBlob message) { return kp.sign(message); }
		@Override public Blob getSeed() { return kp.getSeed(); }
	}

	/**
	 * A Block that cannot be proposed fails its transactions' clients, and the failure
	 * still reaches the caller: running out of memory is answered as load and any other
	 * failure as a peer error. The client can then resubmit the same transaction.
	 */
	@Test
	public void aBlockThatCannotBeProposedFailsItsTransactions() throws Exception {
		OrderFailingKeyPair kp=new OrderFailingKeyPair(LOCAL);
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STATE,Init.createState(List.of(LOCAL.getAccountKey())));
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,new MemoryStore());
		// Each round produces a Block at once, however soon after the last
		config.put(Keywords.MIN_BLOCK_TIME,0);
		Server peer=Server.create(config);
		// Halted before launch, so the peer's own propagator never takes a transaction
		// from the queue: Block production is left to this test thread
		peer.haltConsensus(UpgradeError.missing(99));
		peer.launch();
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
