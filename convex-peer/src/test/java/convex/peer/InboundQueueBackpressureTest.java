package convex.peer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.message.Message;
import convex.core.message.MessageType;

/** Verifies the bounded queues used for externally submitted Peer workloads. */
class InboundQueueBackpressureTest {

	private static final Message QUERY=Message.createQuery(1,"1",null);
	private static final Message TRANSACTION=Message.create(MessageType.TRANSACT,null);

	@Test
	void queryQueueIsBoundedAndBlockingRetryResumesWhenCapacityReturns() throws Exception {
		QueryHandler handler=new QueryHandler(null);
		for (int i=0;i<Config.QUERY_QUEUE_SIZE;i++) assertTrue(handler.offerQuery(QUERY));
		assertFalse(handler.offerQuery(QUERY));

		ArrayBlockingQueue<Message> queue=queryQueue(handler);
		assertBlockingRetryResumes(() -> handler.offerQueryBlocking(QUERY),queue);
	}

	@Test
	void transactionQueueIsBoundedAndBlockingRetryResumesWhenCapacityReturns() throws Exception {
		TransactionHandler handler=new TransactionHandler(null);
		for (int i=0;i<Config.TRANSACTION_QUEUE_SIZE;i++) assertTrue(handler.offerTransaction(TRANSACTION));
		assertFalse(handler.offerTransaction(TRANSACTION));

		assertBlockingRetryResumes(() -> handler.offerTransactionBlocking(TRANSACTION),
				handler.txMessageQueue);
	}

	private static void assertBlockingRetryResumes(CheckedBooleanSupplier retry,
			ArrayBlockingQueue<Message> queue) throws Exception {
		CountDownLatch started=new CountDownLatch(1);
		CompletableFuture<Boolean> result=CompletableFuture.supplyAsync(() -> {
			started.countDown();
			return retry.get();
		});

		assertTrue(started.await(1,TimeUnit.SECONDS));
		assertFalse(result.isDone(),"Retry should wait while the bounded queue is full");
		assertTrue(queue.poll()!=null);
		assertTrue(result.get(1,TimeUnit.SECONDS));
	}

	@SuppressWarnings("unchecked")
	private static ArrayBlockingQueue<Message> queryQueue(QueryHandler handler) throws Exception {
		Field field=QueryHandler.class.getDeclaredField("queryQueue");
		field.setAccessible(true);
		return (ArrayBlockingQueue<Message>) field.get(handler);
	}

	@FunctionalInterface
	private interface CheckedBooleanSupplier {
		boolean get();
	}
}
