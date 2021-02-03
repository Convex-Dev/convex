package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Init;
import convex.core.Result;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.peer.ServerTest;
import convex.test.Samples;

public class TestConvex {

	
	@Test public void testConvex() throws IOException, TimeoutException {
		Convex cv=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO,Init.HERO_KP);
		
		assertTrue(cv.isConnected());

		Result r=cv.transactSync(Invoke.create(Init.HERO,1L,Reader.read("*address*")),1000);
		assertEquals(TestState.HERO,r.getValue());
		assertNull(r.getErrorCode());
		
		cv.disconnect();
		assertFalse(cv.isConnected());
	}
	
	@Test public void testBadSignature() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		Convex cv=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO,Init.HERO_KP);
		
		assertTrue(cv.isConnected());

		Ref<ATransaction> tr=Invoke.create(Init.HERO,1L,Reader.read("*address*")).getRef();
		Result r=cv.transact(SignedData.create(Init.HERO_KP, Samples.FAKE_SIGNATURE,tr)).get();
		assertEquals(ErrorCodes.SIGNATURE,r.getErrorCode());
		
		cv.disconnect();
		assertFalse(cv.isConnected());
	}

}
