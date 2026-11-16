package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.message.AConnection;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;
import convex.net.Transports;

/**
 * A peer stores the cells pushed to it, as DATA or in a Belief, only from connections it
 * has verified as a staked peer's own outbound link to it, and verifies a connection
 * when it sends a Belief or DATA.
 */
public class InboundVerificationTest {
	static final AKeyPair LOCAL=AKeyPair.createSeeded(150);
	static final AKeyPair REMOTE_A=AKeyPair.createSeeded(151);
	static final AKeyPair REMOTE_B=AKeyPair.createSeeded(152);
	static final AKeyPair UNSTAKED=AKeyPair.createSeeded(153);

	static final State GENESIS=Init.createState(List.of(
		LOCAL.getAccountKey(),REMOTE_A.getAccountKey(),REMOTE_B.getAccountKey()));

	/** Long enough that no poll runs and no back-off ends while a test waits. */
	static final int LONG_POLL_DELAY=600000;

	static final long WAIT=10;

	/** Launches a peer on the shared genesis. It has no connections of its own. */
	static Server launch(AKeyPair kp) throws Exception {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STATE,GENESIS);
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,new MemoryStore());
		config.put(Keywords.POLL_DELAY,LONG_POLL_DELAY);
		return API.launchPeer(config);
	}

	static Message data(ACell... cells) {
		return MissingBeliefDataTest.wire(
			Message.createDataMessage(List.of(cells),(int)CPoSConstants.MAX_MESSAGE_LENGTH));
	}

	/** A connection to the peer whose far end handles what the peer sends with the given handler. */
	static LocalConnection connect(Server peer, Predicate<Message> sender) {
		return MissingBeliefDataTest.connect(peer,sender);
	}

	/** Completes with the first outcome the peer's verifier records. */
	static CompletableFuture<AConnection> nextOutcome(Server peer, boolean trusted) {
		CompletableFuture<AConnection> outcome=new CompletableFuture<>();
		peer.getInboundVerifier().setOutcomeObserver((conn,ok) -> {
			if (ok==trusted) outcome.complete(conn);
			else outcome.completeExceptionally(new AssertionError("verification "+(ok?"succeeded":"failed")));
		});
		return outcome;
	}

	/** DATA from an unverified connection is ignored; from a trusted one it is stored. */
	@Test
	public void onlyTrustedDataIsStored() throws Exception {
		Server peer=launch(LOCAL);
		try {
			Blob ignored=Blobs.createRandom(400);
			Blob stored=Blobs.createRandom(400);
			Message trustedData=data(stored);
			CompletableFuture<Message> staged=new CompletableFuture<>();
			peer.getBeliefPropagator().setDataStageObserver(m -> {
				if (trustedData.getMessageData().equals(m.getMessageData())) staged.complete(m);
			});

			// Delivered on this thread: ignored, or queued, before sendMessage returns
			connect(peer,m -> true).sendMessage(data(ignored));
			LocalConnection trusted=connect(peer,m -> true);
			trusted.getPaired().setTrustedKey(REMOTE_A.getAccountKey());
			trusted.sendMessage(trustedData);

			staged.get(WAIT,TimeUnit.SECONDS);
			assertNotNull(peer.getStore().refForHash(stored.getHash()));
			assertNull(peer.getStore().refForHash(ignored.getHash()));
		} finally {
			peer.close();
		}
	}

	/**
	 * A Belief from an unverified connection has only what the merge takes stored; one
	 * from a trusted connection has every cell it carries stored.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	@Test
	public void beliefCellsAreKeptOnlyFromTrustedConnections() throws Exception {
		Server peer=launch(LOCAL);
		try {
			LinkedBlockingQueue<SignedData<Order>> merged=new LinkedBlockingQueue<>();
			peer.getBeliefPropagator().setOrderUpdateObserver(merged::add);
			long ts=InvalidPeerDataTest.blockTime(peer);

			// Beside a good Order, so that its merge shows the whole Belief was handled
			SignedData<?> notKept=UNSTAKED.signData(Blobs.createRandom(1000));
			SignedData<Order> goodB=InvalidPeerDataTest.order(REMOTE_B,1,InvalidPeerDataTest.block(REMOTE_B,ts));
			connect(peer,m -> true).sendMessage(belief(Belief.create(goodB,(SignedData)notKept)));
			assertEquals(goodB,merged.poll(WAIT,TimeUnit.SECONDS));
			assertNull(peer.getStore().refForHash(notKept.getValue().getHash()));

			SignedData<?> kept=UNSTAKED.signData(Blobs.createRandom(1000));
			SignedData<Order> goodA=InvalidPeerDataTest.order(REMOTE_A,1,InvalidPeerDataTest.block(REMOTE_A,ts));
			LocalConnection trusted=connect(peer,m -> true);
			trusted.getPaired().setTrustedKey(REMOTE_A.getAccountKey());
			trusted.sendMessage(belief(Belief.create(goodA,(SignedData)kept)));
			assertEquals(goodA,merged.poll(WAIT,TimeUnit.SECONDS));
			assertNotNull(peer.getStore().refForHash(kept.getValue().getHash()));
		} finally {
			peer.close();
		}
	}

	static Message belief(Belief b) {
		return MissingBeliefDataTest.wire(Message.createBelief(b));
	}

	/**
	 * A peer's link to its launch source, which proves the source's key only after it is
	 * filed, is trusted by the source once proved, even after a first challenge failed:
	 * over the network, and in process when the source is a Server.
	 */
	@Test
	public void aLaunchSourceLinkIsTrustedOnceProved() throws Exception {
		checkLaunchSourceLinkTrusted(false);
		checkLaunchSourceLinkTrusted(true);
	}

	private void checkLaunchSourceLinkTrusted(boolean inProcess) throws Exception {
		Server source=launch(LOCAL);
		AtomicLong offset=new AtomicLong();
		source.setTimeSource(() -> Utils.getCurrentTimestamp()+offset.get());
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,REMOTE_A);
		config.put(Keywords.STORE,new MemoryStore());
		Server joiner=Server.create(config);
		Convex link=null;
		try {
			link=Server.connectToSource(inProcess?source:source.getHostAddress(),REMOTE_A);
			link.setStore(joiner.getStore());
			joiner.getCVMExecutor().setPeer(joiner.syncPeer(REMOTE_A,link));
			AccountKey sourceKey=LOCAL.getAccountKey();
			assertSame(link,joiner.getConnectionManager().getConnection(sourceKey));
			Message belief=Message.createBelief(source.getBelief());

			// Before the link has proved the source's key, its answer names no peer
			CompletableFuture<AConnection> refused=nextOutcome(source,false);
			assertTrue(link.trySend(belief));
			refused.get(WAIT,TimeUnit.SECONDS);

			Hash networkID=source.getPeer().getNetworkID();
			assertEquals(sourceKey,link.verifyPeer(sourceKey,networkID).get(WAIT,TimeUnit.SECONDS));
			joiner.getConnectionManager().verifyDueLinks();
			assertEquals(sourceKey,link.getDialledPeer());

			// Once the wait after the failure has passed, the source challenges again
			offset.addAndGet(source.getConnectionManager().getPollDelay());
			CompletableFuture<AConnection> trusted=nextOutcome(source,true);
			assertTrue(link.trySend(belief));
			assertEquals(REMOTE_A.getAccountKey(),trusted.get(WAIT,TimeUnit.SECONDS).getTrustedKey());
		} finally {
			if (link!=null) link.close();
			joiner.close();
			source.close();
		}
	}

	/**
	 * A staked peer that opened a connection to this peer is trusted once it sends a
	 * Belief, whether it dialled with this peer's key chosen or, as on a local network,
	 * without one.
	 */
	@Test
	public void aStakedPeerThatDialledIsTrusted() throws Exception {
		checkDialledPeerTrusted(true);
		checkDialledPeerTrusted(false);
	}

	private void checkDialledPeerTrusted(boolean keyChosen) throws Exception {
		Server receiver=launch(LOCAL);
		Server sender=launch(REMOTE_A);
		try {
			CompletableFuture<AConnection> trusted=nextOutcome(receiver,true);
			AccountKey chosen=keyChosen?LOCAL.getAccountKey():null;
			sender.getConnectionManager()
				.connectToPeer(Transports.endpoint(receiver.getHostAddress()),chosen)
				.get(WAIT,TimeUnit.SECONDS);

			sender.getConnectionManager().broadcast(Message.createBelief(sender.getBelief()));

			AConnection link=trusted.get(WAIT,TimeUnit.SECONDS);
			assertEquals(REMOTE_A.getAccountKey(),link.getTrustedKey());

			// DATA on the trusted link is now stored
			Blob value=Blobs.createRandom(400);
			Message sentData=Message.createDataMessage(List.of(value),(int)CPoSConstants.MAX_MESSAGE_LENGTH);
			CompletableFuture<Message> staged=new CompletableFuture<>();
			receiver.getBeliefPropagator().setDataStageObserver(m -> {
				if (sentData.getMessageData().equals(m.getMessageData())) staged.complete(m);
			});
			sender.getConnectionManager().broadcast(sentData);
			staged.get(WAIT,TimeUnit.SECONDS);
			assertNotNull(receiver.getStore().refForHash(value.getHash()));
		} finally {
			sender.close();
			receiver.close();
		}
	}

	/**
	 * A peer's outbound link answers for the peer it was opened to reach. An answer
	 * naming any other peer is refused, even if the link verified this one; a client
	 * that was opened to reach no particular peer answers plainly and is refused; and
	 * a correct answer signed by a key without stake is refused.
	 */
	@Test
	public void theAnswerMustNameThisPeerAndBeStaked() throws Exception {
		Server peer=launch(LOCAL);
		try {
			assertEquals(REMOTE_A.getAccountKey(),verifyClient(peer,REMOTE_A,LOCAL.getAccountKey(),true));
			assertNull(verifyClient(peer,REMOTE_A,REMOTE_B.getAccountKey(),false));
			assertNull(verifyClient(peer,REMOTE_A,null,false));
			assertNull(verifyClient(peer,UNSTAKED,LOCAL.getAccountKey(),false));
		} finally {
			peer.close();
		}
	}

	/** Has a client with the given key, which has verified the peer, send it a Belief. */
	private AccountKey verifyClient(Server peer, AKeyPair kp, AccountKey dialled, boolean expectTrusted) throws Exception {
		ConvexLocal client=Convex.connect(peer,null,kp);
		try {
			assertEquals(LOCAL.getAccountKey(),
				client.verifyPeer(LOCAL.getAccountKey(),peer.getPeer().getNetworkID()).get(WAIT,TimeUnit.SECONDS));
			client.setDialledPeer(dialled);
			CompletableFuture<AConnection> outcome=nextOutcome(peer,expectTrusted);
			client.message(Message.createBelief(peer.getBelief()));
			return outcome.get(WAIT,TimeUnit.SECONDS).getTrustedKey();
		} finally {
			client.close();
		}
	}

	/** Only the bound answer is accepted: a plain answer, even signed by a staked peer, is refused. */
	@Test
	public void aPlainAnswerIsRefused() throws Exception {
		Server peer=launch(LOCAL);
		Server other=launch(REMOTE_B);
		ConvexLocal signer=Convex.connect(other);
		try {
			CompletableFuture<Result> plain=new CompletableFuture<>();
			LocalConnection sender=connect(peer,m -> {
				if (m.getType()!=MessageType.CHALLENGE) return true;
				AVector<ACell> payload=m.getPayload();
				@SuppressWarnings("unchecked")
				SignedData<ACell> challenge=(SignedData<ACell>)payload.get(2);
				signer.message(Message.createChallenge(CVMLong.ONE,challenge)).thenAccept(r -> {
					plain.complete(r);
					m.returnResult(r);
				});
				return true;
			});
			CompletableFuture<AConnection> refused=nextOutcome(peer,false);

			sender.sendMessage(data(Blobs.createRandom(400)));

			assertFalse(plain.get(WAIT,TimeUnit.SECONDS).isError(),"a valid plain answer was given");
			AConnection link=refused.get(WAIT,TimeUnit.SECONDS);
			assertFalse(link.isTrusted());
		} finally {
			signer.close();
			other.close();
			peer.close();
		}
	}

	/**
	 * A connection that fails verification is not challenged again until its back-off
	 * ends, and its state is forgotten once it closes.
	 */
	@Test
	public void aFailedConnectionBacksOffAndIsForgottenWhenClosed() throws Exception {
		Server peer=launch(LOCAL);
		try {
			InboundVerifier verifier=peer.getInboundVerifier();
			AtomicInteger challenges=new AtomicInteger();
			Predicate<Message> refusing=m -> {
				if (m.getType()==MessageType.CHALLENGE) {
					challenges.incrementAndGet();
					m.returnResult(Result.error(ErrorCodes.TRUST,"No answer"));
				}
				return true;
			};

			LocalConnection first=connect(peer,refusing);
			CompletableFuture<AConnection> failed=nextOutcome(peer,false);
			first.sendMessage(data(Blobs.createRandom(400)));
			AConnection firstLink=failed.get(WAIT,TimeUnit.SECONDS);
			assertEquals(1,challenges.get());

			// Delivered on this thread: an attempt would be recorded before sendMessage returns
			first.sendMessage(data(Blobs.createRandom(400)));
			first.sendMessage(Message.createBelief(peer.getBelief()));
			assertFalse(verifier.isVerifying(firstLink),"no attempt within the back-off");
			assertEquals(1,challenges.get());
			assertEquals(1,verifier.getEntryCount());

			// A closed connection is swept when the next attempt starts
			first.close();
			LocalConnection second=connect(peer,refusing);
			CompletableFuture<AConnection> secondFailed=nextOutcome(peer,false);
			second.sendMessage(data(Blobs.createRandom(400)));
			AConnection secondLink=secondFailed.get(WAIT,TimeUnit.SECONDS);
			assertEquals(1,verifier.getEntryCount(),"only the open connection is remembered");

			// A disconnect reported by the transport forgets it at once
			verifier.forget(secondLink);
			assertEquals(0,verifier.getEntryCount());
		} finally {
			peer.close();
		}
	}

	/**
	 * DATA that arrives before its connection is verified is ignored, and the update it
	 * preceded completes through the missing-data fetch from its sender.
	 */
	@Test
	public void dataBeforeVerificationCompletesThroughTheFetch() throws Exception {
		MemoryStore senderStore=new MemoryStore();
		SignedData<Order> orderA=MissingBeliefDataTest.signedOrder(REMOTE_A,1000);
		SignedData<Order> orderB=MissingBeliefDataTest.signedOrder(REMOTE_B,1001);
		Belief belief=Belief.create(orderA,orderB);
		// A message limit that fits each signed Order but not both splits the update
		// into DATA and a root
		UpdateAccumulator update=new UpdateAccumulator(300,MissingBeliefDataTest.LIMIT,belief.getHash());
		ACell announced=Cells.announce(belief,update,senderStore);
		List<Message> sent=update.toMessages(announced);
		assertTrue(sent.stream().anyMatch(m -> m.getType()==MessageType.DATA),"the update sends DATA");

		Server peer=launch(LOCAL);
		try {
			CompletableFuture<SignedData<Order>> merged=MissingBeliefDataTest.awaitOrder(peer,REMOTE_A.getAccountKey());
			AtomicInteger requests=new AtomicInteger();
			Predicate<Message> answering=MissingBeliefDataTest.answeringFrom(senderStore);
			LocalConnection senderEnd=connect(peer,m -> {
				if (m.getType()==MessageType.DATA_REQUEST) requests.incrementAndGet();
				return answering.test(m);
			});
			for (Message m: sent) senderEnd.sendMessage(MissingBeliefDataTest.wire(m));
			assertEquals(orderA,merged.get(WAIT,TimeUnit.SECONDS));
			assertTrue(requests.get()>0,"the update's cells were fetched");
		} finally {
			peer.close();
		}
	}

	/** A Belief from an unverified connection is merged as before: only DATA waits on trust. */
	@Test
	public void beliefsAreTakenWhileVerifying() throws Exception {
		Server peer=launch(LOCAL);
		try {
			CompletableFuture<Hash> merged=new CompletableFuture<>();
			peer.getBeliefPropagator().setOrderUpdateObserver(so -> {
				if (REMOTE_B.getAccountKey().equals(so.getAccountKey())) merged.complete(so.getHash());
			});
			var good=InvalidPeerDataTest.order(REMOTE_B,1,
				InvalidPeerDataTest.block(REMOTE_B,InvalidPeerDataTest.blockTime(peer)));
			connect(peer,m -> true).sendMessage(MissingBeliefDataTest.wire(
				Message.createBelief(Belief.create(good))));
			assertEquals(good.getHash(),merged.get(WAIT,TimeUnit.SECONDS));
		} finally {
			peer.close();
		}
	}
}
