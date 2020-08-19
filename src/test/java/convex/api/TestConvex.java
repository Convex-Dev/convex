package convex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.Result;
import convex.core.lang.Reader;
import convex.core.lang.TestState;
import convex.core.transactions.Invoke;
import convex.peer.ServerTest;

public class TestConvex {

	
	@Test public void testConvex() throws IOException, TimeoutException {
		Convex cv=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO_KP);
		
		assertTrue(cv.isConnected());

		Result r=cv.transactSync(Invoke.create(1L,Reader.read("*address*")),1000);
		assertEquals(TestState.HERO,r.getValue());
		assertNull(r.getErrorCode());
		
		cv.disconnect();
		assertFalse(cv.isConnected());
	}

}
