package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.init.Init;
import convex.core.message.AConnection;
import convex.core.message.LocalConnection;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;

/**
 * A peer that cannot decode a Belief or Order update, because the sender left out
 * cells it had already announced, fetches them from the sender rather than waiting
 * for its next poll.
 */
public class MissingBeliefDataTest {
	static final AKeyPair LOCAL=AKeyPair.createSeeded(130);
	static final AKeyPair REMOTE_A=AKeyPair.createSeeded(131);
	static final AKeyPair REMOTE_B=AKeyPair.createSeeded(132);
	static final int LIMIT=Config.DEFAULT_MAX_BELIEF_DELTA_MESSAGE_SIZE;

	/** Long enough that no fetch is dropped and no poll runs while a test waits. */
	static final int LONG_POLL_DELAY=600000;

	/**
	 * Launches a peer on a network of itself and the two remote keys, all staked. It has
	 * no connections of its own, so it never polls: only a fetch can complete what an
	 * update lacks.
	 */
	static Server launchPeer(AKeyPair kp) throws Exception {
		State genesis=Init.createState(List.of(LOCAL.getAccountKey(),REMOTE_A.getAccountKey(),REMOTE_B.getAccountKey()));
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR,kp);
		config.put(Keywords.STATE,genesis);
		config.put(Keywords.PORT,0);
		config.put(Keywords.STORE,new MemoryStore());
		config.put(Keywords.POLL_DELAY,LONG_POLL_DELAY);
		return API.launchPeer(config);
	}

	/**
	 * A signed Order holding one empty Block. Its Order is small enough to embed, but the
	 * signed Order is not, so a Belief refers to it by hash and a delta can leave it out.
	 */
	static SignedData<Order> signedOrder(AKeyPair kp, long timestamp) {
		Order order=Order.create().append(kp.signData(Block.create(timestamp,List.<SignedData<ATransaction>>of())));
		SignedData<Order> signed=kp.signData(order);
		assertTrue(order.isEmbedded());
		assertFalse(signed.isEmbedded());
		return signed;
	}

	/** Announces a value in the sender's store and shapes its update as the propagator does. */
	static List<Message> update(ACell value, AStore senderStore) throws IOException {
		UpdateAccumulator update=new UpdateAccumulator(LIMIT,LIMIT,value.getHash());
		value=Cells.announce(value,update,senderStore);
		return update.toMessages(value);
	}

	/** The message as it arrives off the wire: its encoding only, sharing no cells with the sender. */
	static Message wire(Message m) {
		try {
			return Message.create(m.getMessageData());
		} catch (BadFormatException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A connection whose far end receives what the peer sends and passes on what the sender sends. */
	static LocalConnection connect(Server peer, Predicate<Message> sender) {
		return LocalConnection.createPair(sender, m -> {
			peer.deliverMessage(m);
			return true;
		});
	}

	/** The sender's end of a connection answers data requests from its own store, as a peer's outbound connection does. */
	static Predicate<Message> answeringFrom(AStore senderStore) {
		return m -> {
			if (m.getType()!=MessageType.DATA_REQUEST) return true;
			try {
				m.returnMessage(wire(m.makeDataResponse(senderStore)));
			} catch (BadFormatException e) {
				throw new IllegalStateException(e);
			}
			return true;
		};
	}

	static CompletableFuture<SignedData<Order>> awaitOrder(Server peer, AccountKey key) {
		CompletableFuture<SignedData<Order>> merged=new CompletableFuture<>();
		peer.getBeliefPropagator().setOrderUpdateObserver(so -> {
			if (key.equals(so.getAccountKey())) merged.complete(so);
		});
		return merged;
	}

	/**
	 * The sender relayed A's Order before this peer connected, so its next Belief update
	 * leaves it out. The peer fetches it from the sender and merges the update.
	 */
	@Test
	public void aBeliefMissingAnOrderIsCompletedFromItsSender() throws Exception {
		MemoryStore senderStore=new MemoryStore();
		SignedData<Order> orderA=signedOrder(REMOTE_A,1000);
		SignedData<Order> orderB=signedOrder(REMOTE_B,1001);
		update(Belief.create(orderA),senderStore);
		List<Message> sent=update(Belief.create(orderA,orderB),senderStore);

		// The update cannot be decoded without A's Order
		Message root=wire(sent.get(sent.size()-1));
		ACell payload=root.getPayload(new MemoryStore());
		assertThrows(MissingDataException.class,() -> Belief.extractOrders(payload));

		Server peer=launchPeer(LOCAL);
		try {
			CompletableFuture<SignedData<Order>> merged=awaitOrder(peer,REMOTE_A.getAccountKey());
			LocalConnection senderEnd=connect(peer,answeringFrom(senderStore));
			for (Message m: sent) senderEnd.sendMessage(wire(m));
			assertEquals(orderA,merged.get(10,TimeUnit.SECONDS));
		} finally {
			peer.close();
		}
	}

	/**
	 * A peer fetches for one update at a time. A second update that misses data while the
	 * first fetch is outstanding starts no request. A fetch that fails is dropped and frees
	 * the peer to fetch again.
	 */
	@Test
	public void onlyOneFetchIsOutstanding() throws Exception {
		MemoryStore senderStore=new MemoryStore();
		SignedData<Order> orderA=signedOrder(REMOTE_A,1000);
		SignedData<Order> orderB=signedOrder(REMOTE_B,1001);
		update(Belief.create(orderA),senderStore);
		update(Belief.create(orderB),senderStore);
		List<Message> first=update(Belief.create(orderA,signedOrder(REMOTE_B,1002)),senderStore);
		List<Message> second=update(Belief.create(signedOrder(REMOTE_A,1003),orderB),senderStore);
		Message data=wire(Message.createDataMessage(List.of(Blobs.createRandom(400)),1024));

		Server peer=launchPeer(LOCAL);
		try {
			BeliefPropagator propagator=peer.getBeliefPropagator();
			// The sender holds every request unanswered
			LinkedBlockingQueue<Message> requests=new LinkedBlockingQueue<>();
			LocalConnection senderEnd=connect(peer,m -> {
				if (m.getType()==MessageType.DATA_REQUEST) requests.add(m);
				return true;
			});
			CompletableFuture<Message> staged=new CompletableFuture<>();
			propagator.setDataStageObserver(m -> {
				if (data.getMessageData().equals(m.getMessageData())) staged.complete(m);
			});

			for (Message m: first) senderEnd.sendMessage(wire(m));
			Message request=requests.poll(10,TimeUnit.SECONDS);
			assertNotNull(request,"the first miss is fetched");

			// Staging DATA sent after the second update shows that update has been handled
			for (Message m: second) senderEnd.sendMessage(wire(m));
			senderEnd.sendMessage(data);
			staged.get(10,TimeUnit.SECONDS);
			assertTrue(requests.isEmpty(),"no second fetch while the first is outstanding");

			CompletableFuture<?> fetch=propagator.getMissingDataFetch();
			assertFalse(fetch.isDone());
			request.returnResult(Result.error(ErrorCodes.MISSING,"Not held"));
			fetch.get(10,TimeUnit.SECONDS);

			for (Message m: second) senderEnd.sendMessage(wire(m));
			assertNotNull(requests.poll(10,TimeUnit.SECONDS),"a dropped fetch frees the peer to fetch again");
		} finally {
			peer.close();
		}
	}

	/**
	 * A peer's connection to another peer serves its store to requests sent back along it,
	 * and the receiving peer takes the reply as the answer to its request.
	 */
	@Test
	public void anOutboundPeerConnectionServesDataRequests() throws Exception {
		Server receiver=launchPeer(LOCAL);
		Server sender=launchPeer(REMOTE_A);
		try {
			Blob held=Blobs.createRandom(300);
			Cells.persist(held,sender.getStore());

			// The receiver's end of the sender's connection, seen on the first message along it
			CompletableFuture<AConnection> inbound=new CompletableFuture<>();
			receiver.setMessageReceiveObserver(m -> {
				if (m.getConnection()!=null) inbound.complete(m.getConnection());
			});
			sender.getConnectionManager().connectToPeer(receiver.getHostAddress()).get(10,TimeUnit.SECONDS);
			AConnection connection=inbound.get(10,TimeUnit.SECONDS);

			Message reply=connection.request(Message.createDataRequest(null,held.getHash()),10000).get(10,TimeUnit.SECONDS);
			reply.getPayload(receiver.getStore());
			Result result=reply.toResult();
			assertFalse(result.isError(),result.toString());
			assertEquals(held,((AVector<?>)result.getValue()).get(0));
		} finally {
			sender.close();
			receiver.close();
		}
	}
}
