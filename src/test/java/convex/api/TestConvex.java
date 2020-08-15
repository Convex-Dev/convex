package convex.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.peer.ServerTest;

public class TestConvex {

	
	@Test public void testConvex() throws IOException {
		Convex cv=Convex.connect(ServerTest.server.getHostAddress(), Init.HERO_KP);
		
		assertTrue(cv.isConnected());
		
		cv.disconnect();
		assertFalse(cv.isConnected());

	}

}
