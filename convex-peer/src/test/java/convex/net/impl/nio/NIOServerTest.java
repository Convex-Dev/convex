package convex.net.impl.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cvm.Keywords;
import convex.core.message.Message;

public class NIOServerTest {

	@SuppressWarnings("deprecation")
	@Test public void testNIOServer() throws IOException, TimeoutException, InterruptedException {
		// Thread-safe queue: messages arrive on server threads, and poll(timeout)
		// gives us something to wait on rather than sleep-polling a plain list
		LinkedBlockingQueue<Message> recd=new LinkedBlockingQueue<>();
		Consumer<Message> rec=recd::offer;
		
		try (NIOServer s = new NIOServer(rec)) {
			s.launch();
			InetSocketAddress sa=s.getHostAddress();
			
			Convex c=ConvexRemote.connectNIO(sa);
			assertTrue(c.isConnected());
			
			c.query(Keywords.FOO);
			
			CompletableFuture<Result> bm=c.message(Message.createBelief(Belief.initial()));
			assertFalse(bm.join().isError());
			
			c.query(Keywords.BAR);
			
			int EXP=3;
			for (int i=0; i<EXP; i++) {
				Message m=recd.poll(10, TimeUnit.SECONDS);
				assertNotNull(m,"Timed out waiting for message "+(i+1)+" of "+EXP);
			}
			assertEquals(0,recd.size());
		}
	}
}
