package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexLocal;
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
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.init.Init;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.peer.PeerRequestFailureTest.ReplyConnection;

/**
 * Tests that provable misbehaviour by a remote, such as a signature that does not
 * verify, closes its connection, while a local connection, the process's own client,
 * stays open.
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

	/** DATA that breaks its required shape, here by holding an embedded cell, is disconnected. */
	@Test
	public void badlyShapedDataClosesItsConnection() throws Exception {
		Server peer=InvalidPeerDataTest.launchPeer(LOCAL);
		try {
			ReplyConnection remote=new ReplyConnection(null);
			Message m=Message.create(MessageType.DATA,Vectors.of(MessageTag.DATA,CVMLong.ONE)).withConnection(remote);
			assertThrows(BadFormatException.class,() -> peer.stageData(m));
			assertTrue(remote.isClosed());
		} finally {
			peer.close();
		}
	}
}
