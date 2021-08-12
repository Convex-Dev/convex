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

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.Address;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.lang.Reader;
import convex.core.lang.ops.Constant;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.peer.ServerTest;

/**
 * Tests for a Convex Client connection
 */
public class ConvexTest {

	static final Address USER;
	static final AKeyPair KEYPAIR = AKeyPair.generate();

	static {
		synchronized(ServerTest.SERVER) {
			try {
				USER=ServerTest.CONVEX.createAccountSync(KEYPAIR.getAccountKey());
				ServerTest.CONVEX.transfer(USER, 1000000000L).get(1000,TimeUnit.MILLISECONDS);
			} catch (Throwable e) {
				e.printStackTrace();
				throw Utils.sneakyThrow(e);
			}
		}
	}

	@Test
	public void testConnection() throws IOException, TimeoutException {
		synchronized (ServerTest.SERVER) {
			Convex convex = Convex.connect(ServerTest.SERVER);
			assertTrue(convex.isConnected());
			convex.close();
			assertFalse(convex.isConnected());
		}
	}

	@Test
	public void testConvex() throws IOException, TimeoutException {
		synchronized (ServerTest.SERVER) {
			Convex convex = Convex.connect(ServerTest.SERVER.getHostAddress(), USER, KEYPAIR);
			Result r = convex.transactSync(Invoke.create(USER, 0, Reader.read("*address*")), 1000);
			assertNull(r.getErrorCode(), "Error:" + r.toString());
			assertEquals(USER, r.getValue());
		}
	}

	@Test
	public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (ServerTest.SERVER) {
			Convex convex = Convex.connect(ServerTest.SERVER.getHostAddress(), USER, KEYPAIR);
			Ref<ATransaction> tr = Invoke.create(USER, 0, Reader.read("*address*")).getRef();
			Result r = convex.transact(SignedData.create(KEYPAIR, Ed25519Signature.ZERO, tr)).get();
			assertEquals(ErrorCodes.SIGNATURE, r.getErrorCode());
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testManyTransactions() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (ServerTest.SERVER) {
			Convex convex = Convex.connect(ServerTest.SERVER.getHostAddress(), USER, KEYPAIR);
			int n = 100;
			Future<Result>[] rs = new Future[n];
			for (int i = 0; i < n; i++) {
				Future<Result> f = convex.transact(Invoke.create(USER, 0, Constant.of(i)));
				rs[i] = f;
			}
			for (int i = 0; i < n; i++) {
				Result r = rs[i].get(6000, TimeUnit.MILLISECONDS);
				assertNull(r.getErrorCode(), "Error:" + r.toString());
			}
		}
	}

}
