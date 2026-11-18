package convex.net.impl.nio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cvm.Keywords;
import convex.core.data.Blob;
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

	/**
	 * A connection paused while its bytes are still in the socket reads them once it
	 * resumes, and every message is routed in order. The first message is held, so
	 * the ingress, capped at two waiting messages, pauses the connection after the
	 * read that fills it; each read takes at most a few of these messages, so most
	 * are still in the socket then.
	 */
	@SuppressWarnings("deprecation")
	@Test public void testPausedConnectionResumesWithBytesInSocket() throws Exception {
		int count=50;
		IngressLimits limits=new IngressLimits(1, 0, 2, 1_000_000, 10_000_000, 1_000_000, 1_000_000, 1, 10_000);
		Ingress ingress=new Ingress("test",limits,c->false,e->{});
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		List<Blob> routed=new CopyOnWriteArrayList<>();
		Semaphore routedCount=new Semaphore(0);
		try (NIOServer s = new NIOServer(m -> {})) {
			s.setIngress(ingress, m -> {
				routed.add(m.getMessageData());
				routedCount.release();
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return null;
			});
			s.launch();
			Connection client=Connection.connect(s.getHostAddress(), m -> {});
			try {
				List<Blob> sent=new ArrayList<>();
				for (int i=0; i<count; i++) {
					byte[] data=new byte[100];
					data[0]=(byte)i;
					Blob b=Blob.wrap(data);
					assertTrue(client.sendMessage(Message.create(b)), "sent "+i);
					sent.add(b);
				}
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				release.countDown();
				assertTrue(routedCount.tryAcquire(count, 10, TimeUnit.SECONDS), "every message is routed");
				assertEquals(sent, routed);
			} finally {
				release.countDown();
				client.close();
			}
		} finally {
			ingress.close();
		}
	}
}
