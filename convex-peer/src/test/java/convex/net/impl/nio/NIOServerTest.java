package convex.net.impl.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
import convex.core.store.MemoryStore;
import convex.net.ingress.Ingress;
import convex.net.ingress.IngressLimits;

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
			
			try (Convex c=ConvexRemote.connectNIO(sa)) {
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
			s.close();
			assertNull(s.getHostAddress(),"close must wait until the listener is released");
		}
	}

	@SuppressWarnings("deprecation")
	@Test public void testNIOServerRoutesOnIngress() throws Exception {
		LinkedBlockingQueue<Message> onAction=new LinkedBlockingQueue<>();
		LinkedBlockingQueue<Boolean> onWorker=new LinkedBlockingQueue<>();
		Ingress ingress=new Ingress("test",IngressLimits.defaults(),c->false,e->{});
		try (NIOServer s = new NIOServer(onAction::offer)) {
			s.setIngress(ingress, m -> {
				onWorker.offer(Ingress.isWorkerThread());
				try {
					m.getPayload(new MemoryStore());
				} catch (BadFormatException e) {
					throw new IllegalStateException(e);
				}
				m.returnResult(Result.value(CVMLong.ONE));
				return null;
			});
			s.launch();
			try (Convex c=ConvexRemote.connectNIO(s.getHostAddress())) {
				Result r=c.query(Keywords.FOO).get(10, TimeUnit.SECONDS);
				assertEquals(CVMLong.ONE, r.getValue());
			}
			assertEquals(Boolean.TRUE, onWorker.poll(10, TimeUnit.SECONDS), "routed on an ingress worker");
			assertTrue(onAction.isEmpty(), "the receive action is not used");
		} finally {
			ingress.close();
		}
	}
}
