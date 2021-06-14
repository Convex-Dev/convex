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
import convex.core.data.Address;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.init.InitConfigTest;
import convex.core.lang.Reader;
import convex.core.lang.ops.Constant;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.util.Utils;
import convex.peer.ServerTest;
import convex.test.Samples;

/**
 * Tests for a Convex Client connection
 */
public class ConvexTest {

	static final Convex CONVEX;
	static final Address ADDRESS;
	static final AKeyPair KEYPAIR=AKeyPair.generate();

	protected InitConfigTest initConfigTest;

	protected ConvexTest() {
		InitConfigTest initConfigTest = InitConfigTest.create();
	}

	static {
		synchronized(ServerTest.SERVER) {
			InitConfigTest initConfigTest = InitConfigTest.create();
			try {
				CONVEX=Convex.connect(
					ServerTest.SERVER.getHostAddress(),
					ServerTest.HERO_ADDRESS,
					ServerTest.HERO_KEYPAIR
				);
				ADDRESS=CONVEX.createAccount(KEYPAIR.getAccountKey());
				CONVEX.transfer(ADDRESS, 1000000000L);
				CONVEX.setAddress(ADDRESS,KEYPAIR);
			} catch (Throwable e) {
				e.printStackTrace();
				throw Utils.sneakyThrow(e);
			}
		}
	}

	@Test public void testConnection() throws IOException {
		// Don't need locking
		Convex convex=Convex.connect(
			ServerTest.SERVER.getHostAddress(),
				ServerTest.HERO_ADDRESS,
				ServerTest.HERO_KEYPAIR
		);
		assertTrue(convex.isConnected());
		convex.close();
		assertFalse(convex.isConnected());
	}

	@Test public void testConvex() throws IOException, TimeoutException {
		synchronized (ServerTest.SERVER) {
			Result r=CONVEX.transactSync(Invoke.create(ADDRESS,0,Reader.read("*address*")),1000);
			assertNull(r.getErrorCode(),"Error:" +r.toString());
			assertEquals(ADDRESS,r.getValue());
		}
	}

	@Test public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (ServerTest.SERVER) {
			Ref<ATransaction> tr=Invoke.create(ADDRESS,0,Reader.read("*address*")).getRef();
			Result r=CONVEX.transact(SignedData.create(KEYPAIR, Samples.FAKE_SIGNATURE,tr)).get();
			assertEquals(ErrorCodes.SIGNATURE,r.getErrorCode());
		}
	}

	@SuppressWarnings("unchecked")
	@Test public void testManyTransactions() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		synchronized (ServerTest.SERVER) {
			int n=100;
			Future<Result>[] rs=new Future[n];
			for (int i=0; i<n; i++) {
				Future<Result> f=CONVEX.transact(Invoke.create(ADDRESS, 0,Constant.of(i)));
				rs[i]=f;
			}
			for (int i=0; i<n; i++) {
				Result r=rs[i].get(1000,TimeUnit.MILLISECONDS);
				assertNull(r.getErrorCode(),"Error:" +r.toString());
			}
		}
	}

}
