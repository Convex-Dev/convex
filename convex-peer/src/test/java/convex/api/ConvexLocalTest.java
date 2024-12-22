package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.cvm.Address;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.lang.Reader;
import convex.core.message.Message;
import convex.peer.TestNetwork;

/**
 * Tests for a Convex Local Client connection
 */
public class ConvexLocalTest {

	static Address ADDRESS;
	static final AKeyPair KEYPAIR = AKeyPair.generate();

	private static TestNetwork network;

	@BeforeAll
	public static void init() throws InterruptedException, ResultException, ExecutionException, TimeoutException {
		network =  TestNetwork.getInstance();
		synchronized(network.SERVER) {
			ADDRESS=network.CONVEX.createAccountSync(KEYPAIR.getAccountKey());
			Result r=network.CONVEX.transfer(ADDRESS, 1000000000L).get(5000,TimeUnit.MILLISECONDS);
			assertFalse(r.isError(),()->"Error transferring init funds: "+r);
		}
	}

	@Test
	public void testConvexTransact() throws TimeoutException, InterruptedException {
		synchronized (network.SERVER) {
			ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
			
			long s=convex.getSequence();
			
			Result r = convex.transactSync(Invoke.create(ADDRESS, s+1, Reader.read("*address*")));
			assertNull(r.getErrorCode(), "Error:" + r.toString());
			assertEquals(ADDRESS, r.getValue());
			
			assertEquals(s+1,convex.getSequence());
		}
	}
	
	@Test
	public void testQueryMessage() throws TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
			
			Message m=Message.createQuery(675678567,"*balance*",ADDRESS);
			// ABlob data=Blob.wrap(new byte[] {MessageType.QUERY.getMessageCode()}).append(m.getMessageData());
			ABlob data=m.getMessageData();
			Result r = convex.messageRaw(data.toFlatBlob()).get(5000,TimeUnit.MILLISECONDS);
			assertFalse(r.isError());
			assertTrue(r.getValue() instanceof CVMLong);
		}
	}

	@Test
	public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
			Ref<ATransaction> tr = Invoke.create(ADDRESS, convex.getSequence()+1, Reader.read("*address*")).getRef();
			Result r = convex.transact(SignedData.create(KEYPAIR.getAccountKey(), Ed25519Signature.ZERO, tr)).get();
			assertEquals(ErrorCodes.SIGNATURE, r.getErrorCode());
		}
	}
	
	@Test
	public void testBadFormat() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
			
			// We are going to fake a value that isn't a transaction
			ACell trFake = Reader.read("*address*"); // a symbol, not a transaction!
			@SuppressWarnings({ "rawtypes", "unchecked" })
			SignedData<ATransaction> tr = (SignedData<ATransaction>)(SignedData)(KEYPAIR.signData(trFake));
			
			Result r = convex.transact(tr).get();
			assertEquals(ErrorCodes.FORMAT, r.getErrorCode());
		}
	}
	
	@Test
	public void testBadSequence() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			Convex convex = newLocalClient();
			ATransaction tr = Invoke.create(convex.getAddress(), 10, Reader.read("*address*"));
			Result r = convex.transactSync(tr);
			assertEquals(ErrorCodes.SEQUENCE, r.getErrorCode());
			
			// Sequence should recover
			r=convex.transactSync("(+ 2 3)");
			assertEquals(CVMLong.create(5),r.getValue());
		}
	}

	private ConvexLocal newLocalClient() {
		Convex c = network.getClient();
		return Convex.connect(network.SERVER, c.getAddress(), c.getKeyPair());
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testManyTransactions() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
			int n = 100;
			Future<Result>[] rs = new Future[n];
			for (int i = 0; i < n; i++) {
				Future<Result> f = convex.transact(Invoke.create(ADDRESS, 0, Constant.of(i)));
				rs[i] = f;
			}
			for (int i = 0; i < n; i++) {
				Result r = rs[i].get(12000, TimeUnit.MILLISECONDS);
				assertNull(r.getErrorCode(), "Error:" + r.toString());
			}
		}
	}

}
