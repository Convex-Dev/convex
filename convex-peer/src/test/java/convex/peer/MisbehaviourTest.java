package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.api.ConvexRemote;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.Init;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.core.store.NullStore;
import convex.net.IncomingHandler;
import convex.net.PeerRefusedException;
import convex.net.impl.netty.NettyServer;
import convex.peer.PeerRequestFailureTest.ReplyConnection;

/**
 * Tests that false input from a remote, such as a signature that does not verify,
 * closes its connection, while malformed input keeps it, and a local connection, the
 * process's own client, stays open.
 */
public class MisbehaviourTest {
	static final AKeyPair LOCAL=InvalidPeerDataTest.LOCAL;
	static final AKeyPair REMOTE_A=InvalidPeerDataTest.REMOTE_A;
	static final AKeyPair REMOTE_B=InvalidPeerDataTest.REMOTE_B;
	static final Address ORIGIN=Init.GENESIS_ADDRESS;

	/** A transaction with a signature that does not verify against its own key. */
	static SignedData<ATransaction> badlySigned(String source) {
		return SignedData.create(LOCAL.getAccountKey(),Ed25519Signature.ZERO,Invoke.create(ORIGIN,1,source).getRef());
	}

	/** An Order of a staked peer, with a signature that does not verify. */
	static SignedData<Order> badlySignedOrder(Server peer) {
		SignedData<Order> good=InvalidPeerDataTest.order(REMOTE_A,1,InvalidPeerDataTest.block(REMOTE_A,InvalidPeerDataTest.blockTime(peer)));
		return SignedData.create(REMOTE_A.getAccountKey(),Ed25519Signature.ZERO,good.getValue().getRef());
	}

	/** A remote that sends a transaction with a bad signature gets SIGNATURE, and is disconnected. */
	@Test
	public void aBadTransactionSignatureClosesARemoteConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			// Not started: this test thread runs it
			TransactionHandler handler=new TransactionHandler(peer);
			ReplyConnection remote=new ReplyConnection(null);
			assertTrue(handler.offerTransaction(Message.createTransaction(1,badlySigned("(+ 1 2)")).withConnection(remote)));
			handler.loop();
			assertEquals(ErrorCodes.SIGNATURE,remote.result(1).getErrorCode());
			assertTrue(remote.isClosed());
		} finally {
			peer.close();
		}
	}

	/**
	 * A local client that sends a transaction with a bad signature gets SIGNATURE, and its
	 * connection stays open: it relays for others it must handle itself.
	 */
	@Test
	public void aBadTransactionSignatureKeepsALocalConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			Convex client=Convex.connect(peer,ORIGIN,LOCAL);
			Result bad=client.transact(badlySigned("(+ 1 2)")).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertEquals(ErrorCodes.SIGNATURE,bad.getErrorCode(),bad.toString());
			Result good=client.transact(LOCAL.signData(Invoke.create(ORIGIN,1,"(+ 2 3)"))).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertEquals(CVMLong.create(5),good.getValue(),good.toString());
			assertTrue(client.isConnected());
		} finally {
			peer.close();
		}
	}

	/** The connection that sends an Order with a bad signature is disconnected, whoever the Order names. */
	@Test
	public void aBadOrderSignatureClosesTheSendingConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			// Not started: this test thread runs it
			BeliefPropagator propagator=new BeliefPropagator(peer);
			ReplyConnection remote=new ReplyConnection(null);
			Message m=Message.createBelief(Belief.create(badlySignedOrder(peer))).withConnection(remote);
			assertFalse(propagator.mergeBeliefMessage(new HashMap<>(),m));
			assertTrue(remote.isClosed());
		} finally {
			peer.close();
		}
	}

	/** A polled peer whose Belief holds an Order with a bad signature is disconnected. */
	@Test
	public void aBadOrderSignatureInAPolledBeliefClosesThePolledConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			Belief polled=Belief.create(badlySignedOrder(peer));
			AtomicBoolean closed=new AtomicBoolean();
			Convex polledPeer=new ConvexLocal(peer,ORIGIN,LOCAL) {
				@Override
				public Result requestStatusSync(long timeout) {
					return Result.value(Maps.of(Keywords.BELIEF,polled.getHash()));
				}

				@SuppressWarnings("unchecked")
				@Override
				public <T extends ACell> CompletableFuture<T> acquire(Hash hash) {
					return CompletableFuture.completedFuture((T)polled);
				}

				@Override
				public void close() {
					closed.set(true);
					super.close();
				}
			};
			peer.getConnectionManager().pollBelief(REMOTE_A.getAccountKey(),polledPeer);
			assertTrue(closed.get());
		} finally {
			peer.close();
		}
	}

	/** A challenge with a bad signature gets TRUST and is disconnected; a good one is answered and kept. */
	@Test
	public void aBadChallengeSignatureClosesItsConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			ReplyConnection remote=new ReplyConnection(null);
			SignedData<ACell> bad=SignedData.create(REMOTE_A.getAccountKey(),Ed25519Signature.ZERO,
				Vectors.of(Hash.EMPTY_HASH,LOCAL.getAccountKey()).getRef());
			peer.processMessage(Message.createChallenge(1,bad).withConnection(remote));
			assertEquals(ErrorCodes.TRUST,remote.result(1).getErrorCode());
			assertTrue(remote.isClosed());

			ReplyConnection honest=new ReplyConnection(null);
			SignedData<ACell> good=Message.signChallenge(REMOTE_A,Hash.EMPTY_HASH,LOCAL.getAccountKey(),null);
			peer.processMessage(Message.createChallenge(2,good).withConnection(honest));
			assertFalse(honest.result(2).isError());
			assertFalse(honest.isClosed());
		} finally {
			peer.close();
		}
	}

	/** A key a forging remote claims, without holding its private key. */
	static final AccountKey CLAIMED=REMOTE_B.getAccountKey();

	/**
	 * Launches a remote that answers each request with what the given function gives
	 * for it, or not at all for null.
	 *
	 * @param answer Answer to each request
	 * @param disconnected Completed when a connection to the remote closes
	 */
	static NettyServer fakeRemote(Function<Message,Result> answer, CompletableFuture<Void> disconnected) throws Exception {
		NettyServer remote=new NettyServer(0);
		remote.setConnectionHandler(connection -> new IncomingHandler() {
			@Override
			public Predicate<Message> handle(Message m) {
				try {
					m.getPayload(NullStore.INSTANCE);
				} catch (BadFormatException e) {
					return null;
				}
				Result r=answer.apply(m);
				if (r!=null) m.returnResult(r);
				return null;
			}

			@Override
			public void close() {
				disconnected.complete(null);
			}
		});
		remote.launch();
		return remote;
	}

	/** Answers a challenge with the given reply, and names {@link #CLAIMED} in its status. */
	static Function<Message,Result> challengeAnswer(Result reply) {
		return m -> switch (m.getType()) {
			case CHALLENGE -> reply;
			case STATUS -> Result.value(Maps.of(Keywords.PEER,CLAIMED));
			default -> null;
		};
	}

	/** A remote that answers a challenge with a forged signature claiming {@link #CLAIMED}. */
	static NettyServer forgingRemote(CompletableFuture<Void> disconnected) throws Exception {
		return fakeRemote(challengeAnswer(Result.value(SignedData.create(CLAIMED,Ed25519Signature.ZERO,
			Vectors.of(Hash.EMPTY_HASH,LOCAL.getAccountKey()).getRef()))),disconnected);
	}

	/** Verifying a remote whose challenge reply is forged fails with that evidence. */
	@Test
	public void aForgedChallengeReplyFailsVerification() throws Exception {
		try (NettyServer remote=forgingRemote(new CompletableFuture<>());
				ConvexRemote client=ConvexRemote.connect(remote.getHostAddress())) {
			client.setKeyPair(LOCAL);
			ExecutionException e=assertThrows(ExecutionException.class,
				() -> client.verifyPeer(null).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS));
			assertInstanceOf(BadSignatureException.class,e.getCause());
			assertNull(client.getVerifiedPeer());
		}
	}

	/**
	 * A peer connecting to a remote whose challenge reply is forged refuses it, rather than
	 * taking the key its status names, and disconnects.
	 */
	@Test
	public void aForgedChallengeReplyRefusesThePeer() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		CompletableFuture<Void> disconnected=new CompletableFuture<>();
		try (NettyServer remote=forgingRemote(disconnected)) {
			ConnectionManager manager=peer.getConnectionManager();
			ExecutionException e=assertThrows(ExecutionException.class,
				() -> manager.connectToPeer(remote.getHostAddress()).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS));
			PeerRefusedException refused=assertInstanceOf(PeerRefusedException.class,e.getCause());
			assertInstanceOf(BadSignatureException.class,refused.getCause());
			assertNull(manager.getConnection(CLAIMED));
			disconnected.get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
		} finally {
			peer.close();
		}
	}

	/**
	 * A malformed challenge reply is rejected without a disconnect: verification fails with
	 * the evidence, and a peer connecting falls back to the remote's status.
	 */
	@Test
	public void aMalformedChallengeReplyKeepsTheConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try (NettyServer remote=fakeRemote(challengeAnswer(Result.value(CVMLong.ONE)),new CompletableFuture<>());
				ConvexRemote client=ConvexRemote.connect(remote.getHostAddress())) {
			client.setKeyPair(LOCAL);
			ExecutionException e=assertThrows(ExecutionException.class,
				() -> client.verifyPeer(null).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS));
			assertInstanceOf(BadFormatException.class,e.getCause());
			assertTrue(client.isConnected());

			ConnectionManager manager=peer.getConnectionManager();
			Convex connected=manager.connectToPeer(remote.getHostAddress()).get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertSame(connected,manager.getConnection(CLAIMED));
			assertTrue(connected.isConnected());
		} finally {
			peer.close();
		}
	}

	/** Random cells, none of which is a cell requested. */
	static ACell[] wrongCells(int n) {
		ACell[] cells=new ACell[n];
		for (int i=0; i<n; i++) cells[i]=Blobs.createRandom(400);
		return cells;
	}

	/** The number of hashes a data request asks for. */
	static int requestedCount(Message request) {
		return (int)(((AVector<?>)request.getPayload()).count()-2);
	}

	/** A remote connection that answers each data request with the cells given for the number requested. */
	static final class DataReplyConnection extends AConnection {
		final IntFunction<ACell[]> answer;
		volatile boolean closed;

		DataReplyConnection(IntFunction<ACell[]> answer) {
			this.answer=answer;
		}

		@Override public boolean sendMessage(Message msg) {
			return trySendMessage(msg);
		}

		@Override public boolean trySendMessage(Message msg) {
			if (msg.getType()==MessageType.DATA_REQUEST) {
				Message reply=Message.createDataResponse(msg.getRequestID(),answer.apply(requestedCount(msg))).withConnection(this);
				try {
					completeRequest(reply);
				} catch (BadFormatException e) {
					throw new IllegalStateException(e);
				}
			}
			return true;
		}

		@Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("192.0.2.4",30000); }
		@Override public boolean isClosed() { return closed; }
		@Override public void close() { closed=true; }
		@Override public long getReceivedCount() { return 0; }
	}

	/** Has a peer fetch the data missing from an update sent on the connection, until the fetch is over. */
	static void fetchFrom(DataReplyConnection connection) throws Exception {
		MemoryStore senderStore=new MemoryStore();
		SignedData<Order> held=MissingBeliefDataTest.signedOrder(REMOTE_A,1000);
		MissingBeliefDataTest.update(Belief.create(held),senderStore);
		// Refers to the Order the sender announced before, which the peer does not hold
		List<Message> update=MissingBeliefDataTest.update(Belief.create(held,MissingBeliefDataTest.signedOrder(REMOTE_B,1001)),senderStore);
		Message m=MissingBeliefDataTest.wire(update.get(update.size()-1)).withConnection(connection);

		Server peer=InvalidPeerDataTest.launchPeer(LOCAL,REMOTE_A,REMOTE_B);
		try {
			// Not started: this test thread runs it
			BeliefPropagator propagator=new BeliefPropagator(peer);
			assertFalse(propagator.mergeBeliefMessage(new HashMap<>(),m));
			CompletableFuture<?> fetch=propagator.getMissingDataFetch();
			assertNotNull(fetch,"the missing data is fetched");
			fetch.get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
		} finally {
			peer.close();
		}
	}

	/** A sender that answers a fetch of missing data with cells other than those requested is disconnected. */
	@Test
	public void aWrongCellInAFetchedReplyClosesItsConnection() throws Exception {
		DataReplyConnection sender=new DataReplyConnection(MisbehaviourTest::wrongCells);
		fetchFrom(sender);
		assertTrue(sender.isClosed());
	}

	/** A sender that does not hold the data it is asked for keeps its connection: that is no fault. */
	@Test
	public void aMissingCellInAFetchedReplyKeepsItsConnection() throws Exception {
		DataReplyConnection sender=new DataReplyConnection(ACell[]::new);
		fetchFrom(sender);
		assertFalse(sender.isClosed());
	}

	/** Answers a status with the given Belief hash, and each data request with the cells given for the number requested. */
	static Function<Message,Result> dataAnswer(Hash belief, IntFunction<ACell[]> cells) {
		return m -> switch (m.getType()) {
			case STATUS -> Result.value(Maps.of(Keywords.BELIEF,belief));
			case DATA_REQUEST -> Result.value(Vectors.create(cells.apply(requestedCount(m))));
			default -> null;
		};
	}

	/** A polled peer that answers for its Belief with a cell other than the one requested is disconnected. */
	@Test
	public void aWrongCellInAPolledReplyClosesThePolledConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		CompletableFuture<Void> disconnected=new CompletableFuture<>();
		try (NettyServer remote=fakeRemote(dataAnswer(Blobs.createRandom(400).getHash(),MisbehaviourTest::wrongCells),disconnected);
				ConvexRemote polled=ConvexRemote.connect(remote.getHostAddress())) {
			polled.setStore(new MemoryStore());
			peer.getConnectionManager().pollBelief(REMOTE_A.getAccountKey(),polled);
			// First, as the client's own state may lag its close
			disconnected.get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS);
			assertFalse(polled.isConnected());
		} finally {
			peer.close();
		}
	}

	/** A polled peer that does not hold the data it advertises keeps its connection. */
	@Test
	public void aMissingCellInAPolledReplyKeepsThePolledConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try (NettyServer remote=fakeRemote(dataAnswer(Blobs.createRandom(400).getHash(),ACell[]::new),new CompletableFuture<>());
				ConvexRemote polled=ConvexRemote.connect(remote.getHostAddress())) {
			polled.setStore(new MemoryStore());
			peer.getConnectionManager().pollBelief(REMOTE_A.getAccountKey(),polled);
			assertTrue(polled.isConnected());
		} finally {
			peer.close();
		}
	}

	/**
	 * DATA that breaks its required shape, here by holding an embedded cell, is rejected
	 * and its connection kept: malformed input may be an honest mistake.
	 */
	@Test
	public void badlyShapedDataKeepsItsConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			BeliefPropagator propagator=peer.getBeliefPropagator();
			ReplyConnection remote=new ReplyConnection(null);
			Message bad=Message.create(MessageType.DATA,Vectors.of(MessageTag.DATA,CVMLong.ONE)).withConnection(remote);
			assertThrows(BadFormatException.class,() -> peer.stageData(bad));
			// Staged after the bad one, in queue order: once it is, the bad one has been handled
			Message good=Message.createDataMessage(List.of(Blobs.createRandom(400)),1024).withConnection(remote);
			CompletableFuture<Message> staged=new CompletableFuture<>();
			propagator.setDataStageObserver(staged::complete);
			assertTrue(propagator.queuePropagation(bad));
			assertTrue(propagator.queuePropagation(good));
			assertEquals(good,staged.get(InvalidPeerDataTest.WAIT,TimeUnit.SECONDS));
			assertFalse(remote.isClosed());
		} finally {
			peer.close();
		}
	}
}
