package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.State;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.init.Init;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.core.store.MemoryStore;
import convex.net.ingress.Destination;
import convex.net.ingress.QueueDestination;

/** Verifies the bounded queues used for externally submitted Peer workloads. */
class InboundQueueBackpressureTest {

	private static final Message QUERY=Message.createQuery(1,"1",null);
	private static final Message TRANSACTION=Message.create(MessageType.TRANSACT,null);
	@Test
	void queryQueueIsBoundedAndBlockingRetryResumesWhenCapacityReturns() throws Exception {
		QueryHandler handler=new QueryHandler(null);
		for (int i=0;i<Config.QUERY_QUEUE_SIZE;i++) assertTrue(handler.offerQuery(QUERY));
		assertFalse(handler.offerQuery(QUERY));

		assertBlockingRetryResumes(QUERY,(QueueDestination)handler.getDestination());
	}

	@Test
	void transactionQueueIsBoundedAndBlockingRetryResumesWhenCapacityReturns() throws Exception {
		TransactionHandler handler=new TransactionHandler(null);
		for (int i=0;i<Config.TRANSACTION_QUEUE_SIZE;i++) assertTrue(handler.offerTransaction(TRANSACTION));
		assertFalse(handler.offerTransaction(TRANSACTION));

		assertBlockingRetryResumes(TRANSACTION,handler.txMessageQueue);
	}

	/**
	 * A request refused by a full queue is routed to that queue as a destination, which
	 * signals once its handler takes from it.
	 */
	@Test
	void fullRequestQueueRoutesToItsDestination() throws Exception {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE,new MemoryStore());
		try (Server server=Server.create(config)) { // not launched: queues are not drained
			for (int i=0;i<Config.TRANSACTION_QUEUE_SIZE;i++) assertNull(server.processMessage(TRANSACTION));
			Predicate<Message> refused=server.processMessage(TRANSACTION);
			assertSame(server.transactionHandler.getDestination(),refused);
			Destination d=(Destination)refused;
			assertFalse(d.tryOffer(TRANSACTION));

			CountDownLatch room=new CountDownLatch(1);
			d.whenCapacity(room::countDown);
			assertEquals(1,room.getCount(),"no room until the handler takes a message");
			server.transactionHandler.txMessageQueue.poll(0,TimeUnit.MILLISECONDS);
			assertEquals(0,room.getCount());
			assertNull(server.processMessage(TRANSACTION));

			for (int i=0;i<Config.QUERY_QUEUE_SIZE;i++) assertNull(server.processMessage(QUERY));
			assertSame(server.queryHandler.getDestination(),server.processMessage(QUERY));
		}
	}

	/**
	 * A callback on the query handler thread cannot wait for space in the query queue:
	 * only that thread empties it, so the wait could only time out.
	 */
	@Test
	@Timeout(60)
	void queryRetryOnHandlerThreadDoesNotWait() throws Exception {
		record Outcome(Result result, long nanos) {}
		AKeyPair kp=AKeyPair.generate();
		State genesis=Migrations.applyAll(Init.createState(List.of(kp.getAccountKey())));
		try (MemoryStore store=new MemoryStore()) {
			Map<Keyword,Object> config=Maps.hashMapOf(Keywords.KEYPAIR,kp,Keywords.STATE,genesis,Keywords.STORE,store,
				Keywords.PORT,0,Keywords.AUTO_MANAGE,false,Keywords.OUTGOING_CONNECTIONS,0);
			try (Server server=API.launchPeer(config)) {
				ConvexLocal convex=Convex.connect(server);
				CompletableFuture<Outcome> outcome=new CompletableFuture<>();
				// A result for a raw send matches no request, so it is observed on the thread
				// delivering it: the query handler's
				convex.setReceiveObserver(() -> {
					if (outcome.isDone()) return;
					for (int i=0;i<Config.QUERY_QUEUE_SIZE;i++) convex.trySend(QUERY);
					long start=System.nanoTime();
					// A raw query takes the query queue, as from the network
					Result result=convex.request(QUERY).join();
					outcome.complete(new Outcome(result,System.nanoTime()-start));
				});
				assertTrue(convex.trySend(QUERY));
				Outcome o=outcome.get();
				assertEquals(Result.FULL_CLIENT_BUFFER.getErrorCode(),o.result().getErrorCode());
				// A wait would have lasted the whole timeout
				assertTrue(o.nanos()<TimeUnit.MILLISECONDS.toNanos(Config.DEFAULT_INTERNAL_TIMEOUT));
			}
		}
	}

	/** A wait for room in a full queue ends when its consumer takes a message. */
	private static void assertBlockingRetryResumes(Message m, QueueDestination queue) throws Exception {
		CountDownLatch started=new CountDownLatch(1);
		CompletableFuture<Boolean> result=CompletableFuture.supplyAsync(() -> {
			started.countDown();
			return queue.test(m);
		});

		assertTrue(started.await(1,TimeUnit.SECONDS));
		assertFalse(result.isDone(),"Retry should wait while the bounded queue is full");
		assertNotNull(queue.poll(0,TimeUnit.MILLISECONDS));
		assertTrue(result.get(1,TimeUnit.SECONDS));
	}
}
