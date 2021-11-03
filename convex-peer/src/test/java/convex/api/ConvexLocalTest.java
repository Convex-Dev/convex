package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.lang.Reader;
import convex.core.lang.ops.Constant;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.peer.TestNetwork;

/**
 * Tests for a Convex Local Client connection
 */
public class ConvexLocalTest {

	static Address ADDRESS;
	static final AKeyPair KEYPAIR = AKeyPair.generate();

	private static TestNetwork network;

	@BeforeAll
	public static void init() {
		network =  TestNetwork.getInstance();
		synchronized(network.SERVER) {
			try {
				ADDRESS=network.CONVEX.createAccountSync(KEYPAIR.getAccountKey());
				network.CONVEX.transfer(ADDRESS, 1000000000L).get(1000,TimeUnit.MILLISECONDS);
			} catch (Throwable e) {
				e.printStackTrace();
				throw Utils.sneakyThrow(e);
			}
		}
	}

	@Test
	public void testConvex() throws IOException, TimeoutException {
		synchronized (network.SERVER) {
			ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
			Result r = convex.transactSync(Invoke.create(ADDRESS, 0, Reader.read("*address*")), 1000);
			assertNull(r.getErrorCode(), "Error:" + r.toString());
			assertEquals(ADDRESS, r.getValue());
		}
	}

	@Test
	public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			ConvexLocal convex = Convex.connect(network.SERVER, ADDRESS, KEYPAIR);
			Ref<ATransaction> tr = Invoke.create(ADDRESS, 0, Reader.read("*address*")).getRef();
			Result r = convex.transact(SignedData.create(KEYPAIR, Ed25519Signature.ZERO, tr)).get();
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

	@SuppressWarnings("unchecked")
	@Test
	public void testManyTransactions() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (network.SERVER) {
			Convex convex = Convex.connect(network.SERVER.getHostAddress(), ADDRESS, KEYPAIR);
			int n = 100;
			Future<Result>[] rs = new Future[n];
			for (int i = 0; i < n; i++) {
				Future<Result> f = convex.transact(Invoke.create(ADDRESS, 0, Constant.of(i)));
				rs[i] = f;
			}
			for (int i = 0; i < n; i++) {
				Result r = rs[i].get(6000, TimeUnit.MILLISECONDS);
				assertNull(r.getErrorCode(), "Error:" + r.toString());
			}
		}
	}

}
