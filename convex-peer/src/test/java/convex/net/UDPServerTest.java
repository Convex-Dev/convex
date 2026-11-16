package convex.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Closing a UDP server ends its receiving thread and its socket, for good. */
public class UDPServerTest {

	@Test
	public void closeEndsTheReceiverAndTheSocket() throws Exception {
		UDPServer server=new UDPServer();
		server.launch("127.0.0.1",0);
		Thread receiver=server.thread;
		assertTrue(receiver.isAlive());

		server.close();
		assertFalse(receiver.isAlive(),"close returns once the receiving thread has ended");
		assertTrue(server.socket.isClosed());

		server.close();
		assertThrows(IllegalStateException.class,() -> server.launch("127.0.0.1",0),"a closed server does not launch again");
	}

	@Test
	public void closingAServerNeverLaunchedClosesItsSocket() {
		UDPServer server=new UDPServer();
		server.close();
		assertTrue(server.socket.isClosed());
		assertThrows(IllegalStateException.class,() -> server.launch("127.0.0.1",0));
	}
}
