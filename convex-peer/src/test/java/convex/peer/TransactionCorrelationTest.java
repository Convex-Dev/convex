package convex.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.api.ConvexLocal;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.crypto.Ed25519Signature;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.message.LocalConnection;
import convex.core.message.Message;

public class TransactionCorrelationTest {
	@Test public void testDuplicatePendingTransactionDoesNotReplaceOriginalWaiter() throws Exception {
		TestNetwork network = TestNetwork.getInstance();
		try (var client = network.getLocalClient()) {
			SignedData<ATransaction> transaction = client.getKeyPair().signData(
					(ATransaction) Invoke.create(client.getAddress(),client.getSequence()+1,"(+ 2 3)"));
			CompletableFuture<Result> first = new CompletableFuture<>();
			CompletableFuture<Result> second = new CompletableFuture<>();
			LocalConnection connection = LocalConnection.createReturnable(message -> {
				Result result = message.toResult();
				(result.getID().equals(CVMLong.ONE) ? first : second).complete(result);
				return true;
			},message -> true);
			try {
				// A separate handler lets this test explicitly drive intake and reporting,
				// so both submissions are pending before any result can be delivered.
				TransactionHandler handler = new TransactionHandler(network.SERVER);
				assertTrue(handler.offerTransaction(Message.createTransaction(1,transaction).withConnection(connection.getPaired())));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,transaction).withConnection(connection.getPaired())));
				handler.loop();
				assertEquals(1,handler.transactionQueue.size(),"Only one copy may enter the publication queue");
				Result duplicate = second.get(1,TimeUnit.SECONDS);
				assertEquals(ErrorCodes.STATE,duplicate.getErrorCode());
				assertEquals(transaction.getHash(),duplicate.getIn(Keywords.INFO,Keywords.TX));
				assertFalse(first.isDone());
				assertEquals(1,handler.countInterests());

				Block block = Block.create(network.SERVER.getTimestamp(),List.of(transaction));
				handler.reportTransactions(block,BlockResult.create(network.SERVER.getState(),
						new Result[] {Result.value(CVMLong.create(5))}),0);
				assertEquals(CVMLong.create(5),first.get(1,TimeUnit.SECONDS).getValue());
				assertEquals(transaction.getHash(),first.join().getIn(Keywords.INFO,Keywords.TX));
				assertEquals(0,handler.countInterests());
			} finally {
				connection.close();
			}
		}
	}

	/**
	 * Reporting a block hands its results to a thread of their own, so a client that
	 * holds back the reporting thread's caller delays no later block's results.
	 */
	@Test @Timeout(60) public void testReportingDoesNotWaitForDelivery() throws Exception {
		TestNetwork network = TestNetwork.getInstance();
		try (var client = network.getLocalClient()) {
			SignedData<ATransaction> first = sign(client,"(+ 1 2)");
			SignedData<ATransaction> second = sign(client,"(+ 3 4)");
			CountDownLatch delivering = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			CompletableFuture<Result> secondResult = new CompletableFuture<>();
			LocalConnection connection = LocalConnection.createReturnable(message -> {
				Result result = message.toResult();
				if (result.getID().equals(CVMLong.ONE)) {
					delivering.countDown();
					try {
						release.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				} else {
					secondResult.complete(result);
				}
				return true;
			},message -> true);
			try {
				TransactionHandler handler = new TransactionHandler(network.SERVER);
				assertTrue(handler.offerTransaction(Message.createTransaction(1,first).withConnection(connection.getPaired())));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,second).withConnection(connection.getPaired())));
				handler.loop();
				assertEquals(2,handler.countInterests());

				// This thread reports, as the CVM executor does
				handler.reportTransactions(block(network,first),results(network,3),0);
				delivering.await();
				handler.reportTransactions(block(network,second),results(network,7),1);
				assertEquals(CVMLong.create(7),secondResult.get().getValue());
				assertEquals(0,handler.countInterests());
			} finally {
				release.countDown();
				connection.close();
			}
		}
	}

	/**
	 * A transaction sent without a request ID asks for no result: it gets none, and the
	 * others in its block still get theirs.
	 */
	@Test @Timeout(60) public void testReportingTransactionWithoutRequestID() throws Exception {
		TestNetwork network = TestNetwork.getInstance();
		try (var client = network.getLocalClient()) {
			SignedData<ATransaction> unanswered = sign(client,"(+ 1 2)");
			SignedData<ATransaction> answered = sign(client,"(+ 3 4)");
			List<Result> received = new CopyOnWriteArrayList<>();
			CompletableFuture<Result> answer = new CompletableFuture<>();
			LocalConnection connection = LocalConnection.createReturnable(message -> {
				Result result = message.toResult();
				received.add(result);
				answer.complete(result);
				return true;
			},message -> true);
			try {
				TransactionHandler handler = new TransactionHandler(network.SERVER);
				assertTrue(handler.offerTransaction(Message.createTransaction((CVMLong)null,unanswered).withConnection(connection.getPaired())));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,answered).withConnection(connection.getPaired())));
				handler.loop();
				assertEquals(2,handler.countInterests());

				Block block = Block.create(network.SERVER.getTimestamp(),List.of(unanswered,answered));
				handler.reportTransactions(block,BlockResult.create(network.SERVER.getState(),
						new Result[] {Result.value(CVMLong.create(3)),Result.value(CVMLong.create(7))}),0);
				assertEquals(0,handler.countInterests());
				assertEquals(CVMLong.create(7),answer.get().getValue());
				// A block's results are delivered in order, so any reply to the first came before
				assertEquals(1,received.size());
			} finally {
				connection.close();
			}
		}
	}

	/**
	 * A transaction refused at intake without a request ID gets no result, and the rest
	 * of its batch is still taken in.
	 */
	@Test public void testIntakeRefusalWithoutRequestID() throws Exception {
		TestNetwork network = TestNetwork.getInstance();
		try (var client = network.getLocalClient()) {
			ATransaction tx = Invoke.create(client.getAddress(),client.getSequence()+1,"(+ 1 2)");
			SignedData<ATransaction> badSignature = SignedData.create(client.getAccountKey(),Ed25519Signature.ZERO,tx.getRef());
			SignedData<ATransaction> valid = sign(client,"(+ 3 4)");
			LocalConnection connection = LocalConnection.createReturnable(message -> true,message -> true);
			try {
				TransactionHandler handler = new TransactionHandler(network.SERVER);
				assertTrue(handler.offerTransaction(Message.createTransaction((CVMLong)null,badSignature).withConnection(connection.getPaired())));
				assertTrue(handler.offerTransaction(Message.createTransaction(2,valid).withConnection(connection.getPaired())));
				handler.loop();
				assertEquals(1,handler.countInterests());
				assertEquals(1,handler.transactionQueue.size());
			} finally {
				connection.close();
			}
		}
	}

	private static SignedData<ATransaction> sign(ConvexLocal client, String code) throws Exception {
		return client.getKeyPair().signData(
				(ATransaction) Invoke.create(client.getAddress(),client.getSequence()+1,code));
	}

	private static Block block(TestNetwork network, SignedData<ATransaction> transaction) {
		return Block.create(network.SERVER.getTimestamp(),List.of(transaction));
	}

	private static BlockResult results(TestNetwork network, long value) {
		return BlockResult.create(network.SERVER.getState(),new Result[] {Result.value(CVMLong.create(value))});
	}
}
