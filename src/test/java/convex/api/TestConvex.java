package convex.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.data.AVector;
import convex.core.lang.Reader;
import convex.core.transactions.Invoke;
import convex.peer.ServerTest;

public class TestConvex {

	
	@Test public void testConvex() throws IOException, TimeoutException {
		Convex cv=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO_KP);
		
		assertTrue(cv.isConnected());

		AVector<Object> r=cv.transactSync(Invoke.create(0L,Reader.read("*address*")),1000);
		assertNotNull(r);
		
		cv.disconnect();
		assertFalse(cv.isConnected());
	}

}
