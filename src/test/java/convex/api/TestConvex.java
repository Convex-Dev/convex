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
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.lang.ops.Constant;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.peer.ServerTest;
import convex.test.Samples;

public class TestConvex {

	static Convex cv;
	
	static {
		try {
			cv=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO,Init.HERO_KP);
		} catch (IOException e) {
			throw new Error(e);
		}
	}
	
	@Test public void testConnection() throws IOException {
		Convex convex=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO,Init.HERO_KP);
		assertTrue(convex.isConnected());
		convex.disconnect();
		assertFalse(convex.isConnected());
	}
	
	@Test public void testConvex() throws IOException, TimeoutException {
		Result r=cv.transactSync(Invoke.create(Init.HERO,0,Reader.read("*address*")),1000);
		assertNull(r.getErrorCode(),"Error:" +r.toString());
		assertEquals(TestState.HERO,r.getValue());
		
	}
	
	@Test public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		Ref<ATransaction> tr=Invoke.create(Init.HERO,0,Reader.read("*address*")).getRef();
		Result r=cv.transact(SignedData.create(Init.HERO_KP, Samples.FAKE_SIGNATURE,tr)).get();
		assertEquals(ErrorCodes.SIGNATURE,r.getErrorCode());
		
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testManyTransactions() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		int n=1000;
		Future<Result>[] rs=new Future[n];
		for (int i=0; i<n; i++) {
			Future<Result> f=cv.transact(Invoke.create(Init.HERO,0, Constant.of(i)));
			rs[i]=f;
		}
		for (int i=0; i<n; i++) {
			Result r=rs[i].get(1000,TimeUnit.MILLISECONDS);
			assertNull(r.getErrorCode(),"Error:" +r.toString());
		}
	}

}
