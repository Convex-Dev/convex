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
import convex.core.Init;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.core.data.Ref;
import convex.core.data.SignedData;
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

	static final Convex cv;
	static final Address ADDR;
	static final AKeyPair KP=AKeyPair.generate();
	
	static {
		synchronized(ServerTest.server) {
			try {
				cv=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO,Init.HERO_KP);
				ADDR=cv.createAccount(KP.getAccountKey());
				cv.transfer(ADDR, 1000000000L);
				cv.setAddress(ADDR,KP);
			} catch (Throwable e) {
				e.printStackTrace();
				throw Utils.sneakyThrow(e);
			} 
		}
	}
	
	@Test public void testConnection() throws IOException {
		Convex convex=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO,Init.HERO_KP);
		assertTrue(convex.isConnected());
		convex.close();
		assertFalse(convex.isConnected());
	}
	
	@Test public void testConvex() throws IOException, TimeoutException {
		Result r=cv.transactSync(Invoke.create(ADDR,0,Reader.read("*address*")),1000);
		assertNull(r.getErrorCode(),"Error:" +r.toString());
		assertEquals(ADDR,r.getValue());
		
	}
	
	@Test public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		Ref<ATransaction> tr=Invoke.create(ADDR,0,Reader.read("*address*")).getRef();
		Result r=cv.transact(SignedData.create(KP, Samples.FAKE_SIGNATURE,tr)).get();
		assertEquals(ErrorCodes.SIGNATURE,r.getErrorCode());
		
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testManyTransactions() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		int n=100;
		Future<Result>[] rs=new Future[n];
		for (int i=0; i<n; i++) {
			Future<Result> f=cv.transact(Invoke.create(ADDR, 0,Constant.of(i)));
			rs[i]=f;
		}
		for (int i=0; i<n; i++) {
			Result r=rs[i].get(1000,TimeUnit.MILLISECONDS);
			assertNull(r.getErrorCode(),"Error:" +r.toString());
		}
	}

}
