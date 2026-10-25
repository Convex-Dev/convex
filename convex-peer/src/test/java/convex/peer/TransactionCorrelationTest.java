package convex.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
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
}
