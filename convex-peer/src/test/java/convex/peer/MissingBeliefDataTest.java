package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import convex.core.data.Hash;
import convex.core.data.Index;
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
 * A peer keeps every cell a Belief or Order update from a trusted link carries, so later
 * updates that leave them out as already announced need no fetch. One that still cannot be decoded,
 * because the sender left out cells it announced before, is completed by a fetch from
 * the sender rather than waiting for the peer's next poll.
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

	/** A signed Order holding one empty Block, timestamped to rank it against the peer's other Orders. */
	static SignedData<Order> signedOrderAt(AKeyPair kp, long timestamp) {
		Order order=Order.create().withTimestamp(timestamp).append(kp.signData(Block.create(timestamp,List.<SignedData<ATransaction>>of())));
		return kp.signData(order);
	}

	/** A sender's end of a connection that answers nothing the peer sends. */
	static final Predicate<Message> SILENT=m -> true;

	/** Announces a value in the sender's store and shapes its update as the propagator does. */
	static List<Message> update(ACell value, AStore senderStore) throws IOException {
		UpdateAccumulator update=new UpdateAccumulator(LIMIT,LIMIT,value.getHash());
		value=Cells.announce(value,update,senderStore);
		return update.toMessages(value);
	}

	/** A cell an update leaves out, which a receiver holding nothing else must fetch. */
	static Hash leftOut(List<Message> update) throws BadFormatException {
		MemoryStore store=new MemoryStore();
		ACell root=wire(update.get(update.size()-1)).getPayload(store);
		return assertThrows(MissingDataException.class,() -> Cells.persist(root,store)).getMissingHash();
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
	 * A peer that already holds a newer Order from A, relayed by another peer, does not
	 * merge A's own update, from A's trusted link, for an older one, but keeps it: A
	 * counts that Order as announced and leaves it out of the Belief it sends next.
	 */
	@Test
	public void anOrderNotMergedIsKeptForTheUpdatesThatReferToIt() throws Exception {
		SignedData<Order> older=signedOrderAt(REMOTE_A,1000);
		SignedData<Order> newer=signedOrderAt(REMOTE_A,1001);
		SignedData<Order> orderB=signedOrderAt(REMOTE_B,1000);
		List<Message> relayed=update(Belief.create(newer),new MemoryStore());
		MemoryStore senderStore=new MemoryStore();
		List<Message> own=update(older,senderStore);
		List<Message> belief=update(Belief.create(older,orderB),senderStore);

		// The Belief leaves out the Order announced just before it
		ACell payload=wire(belief.get(belief.size()-1)).getPayload(new MemoryStore());
		assertThrows(MissingDataException.class,() -> Belief.extractOrders(payload));

		Server peer=launchPeer(LOCAL);
		try {
			BeliefPropagator propagator=peer.getBeliefPropagator();
			LinkedBlockingQueue<SignedData<Order>> merged=new LinkedBlockingQueue<>();
			propagator.setOrderUpdateObserver(merged::add);
			LocalConnection relayEnd=connect(peer,SILENT);
			LocalConnection senderEnd=connect(peer,SILENT);
			// Trusted as A's own link, so that what it sends is kept
			senderEnd.getPaired().setTrustedKey(REMOTE_A.getAccountKey());
			for (Message m: relayed) relayEnd.sendMessage(wire(m));
			for (Message m: own) senderEnd.sendMessage(wire(m));
			for (Message m: belief) senderEnd.sendMessage(wire(m));

			assertEquals(newer,merged.poll(10,TimeUnit.SECONDS));
			assertEquals(orderB,merged.poll(10,TimeUnit.SECONDS),"B's Order merged from the Belief");
			assertNull(propagator.getMissingDataFetch(),"nothing fetched");
		} finally {
			peer.close();
		}
	}

	/**
	 * A peer keeps a Belief's own cells and the Orders in it that it does not merge, here
	 * its own Order as the sender last saw it, not only the Orders it merges: the sender
	 * repeats an unchanged Belief as a keepalive carrying only its top cell.
	 */
	@Test
	public void aBeliefIsKeptForTheKeepalivesThatRepeatIt() throws Exception {
		List<SignedData<Order>> orders=List.of(signedOrderAt(REMOTE_A,1000),signedOrderAt(REMOTE_B,1000),
			signedOrderAt(LOCAL,1000));
		Belief belief=Belief.create(orders.toArray(SignedData[]::new));
		MemoryStore senderStore=new MemoryStore();
		List<Message> first=update(belief,senderStore);
		List<Message> keepalive=update(belief,senderStore);

		// Even with every Order held, the keepalive lacks a cell of the Belief's own
		MemoryStore holdingOrders=new MemoryStore();
		for (SignedData<Order> so: orders) Cells.persist(so,holdingOrders);
		ACell payload=wire(keepalive.get(keepalive.size()-1)).getPayload(holdingOrders);
		MissingDataException missing=assertThrows(MissingDataException.class,() -> Belief.extractOrders(payload));
		assertTrue(senderStore.refForHash(missing.getMissingHash()).getValue() instanceof Index);
		Message marker=wire(Message.createDataMessage(List.of(Blobs.createRandom(400)),1024));

		Server peer=launchPeer(LOCAL);
		try {
			BeliefPropagator propagator=peer.getBeliefPropagator();
			LocalConnection senderEnd=connect(peer,SILENT);
			// Trusted as a staked peer's, so that its DATA is staged
			senderEnd.getPaired().setTrustedKey(REMOTE_A.getAccountKey());
			CompletableFuture<Message> staged=new CompletableFuture<>();
			propagator.setDataStageObserver(m -> {
				if (marker.getMessageData().equals(m.getMessageData())) staged.complete(m);
			});

			for (Message m: first) senderEnd.sendMessage(wire(m));
			for (Message m: keepalive) senderEnd.sendMessage(wire(m));
			// Staging DATA sent after the keepalive shows the keepalive has been handled
			senderEnd.sendMessage(marker);
			staged.get(10,TimeUnit.SECONDS);
			assertNull(propagator.getMissingDataFetch(),"nothing fetched");
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
			// Trusted as a staked peer's, so that its DATA is staged
			senderEnd.getPaired().setTrustedKey(REMOTE_A.getAccountKey());
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
