package convex.net.impl.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cvm.Keywords;
import convex.core.message.Message;
import convex.core.store.Stores;

public class NIOServerTest {

	@Test public void testNIOServer() throws IOException, TimeoutException, InterruptedException {
		ArrayList<Message> recd=new ArrayList<Message>();
		Consumer<Message> rec=m->{
			recd.add(m);
		};
		
		try (NIOServer s = new NIOServer(Stores.current(),rec)) {
			s.launch();
			InetSocketAddress sa=s.getHostAddress();
			
			Convex c=ConvexRemote.connectNIO(sa);
			assertTrue(c.isConnected());
			
			c.query(Keywords.FOO);
			
			CompletableFuture<Result> bm=c.message(Message.createBelief(Belief.initial()));
			assertFalse(bm.join().isError());
			
			c.query(Keywords.BAR);
			
			int EXP=3;
			while(recd.size()<EXP) {
				Thread.sleep(10);
			};
			assertEquals(EXP,recd.size());
		}
	}
}
