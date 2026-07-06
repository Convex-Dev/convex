package convex.net.impl.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.parallel.*;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.store.NullStore;
import io.netty.channel.embedded.EmbeddedChannel;

@Execution(ExecutionMode.CONCURRENT)
@TestInstance(Lifecycle.PER_CLASS)
public class NettyServerTest {

	@Test public void testServerSetup() throws IOException, InterruptedException, TimeoutException, BadFormatException {
		try (NettyServer server = new NettyServer(0)) {
			server.launch();
			Integer port=server.getPort();
			
			assertNotNull(port);
			InetSocketAddress addr=server.getHostAddress();
			assertEquals(port,addr.getPort());
			
			CompletableFuture<Message> rec=new CompletableFuture<>();
			NettyConnection client=NettyConnection.connect(addr, m->{
				rec.complete(m);
			});
			
			client.send(Message.createQuery(10, "*address*", Address.create(17)));
			
			Message m=rec.join();
			assertEquals(RT.cvm(10),m.getResultID());
			
			{ // Regular client
				Convex convex=Convex.connect(addr);
				Result r=convex.query(Keywords.FOO).join();
				assertFalse(r.isError());
				AVector<?> v= RT.ensureVector(r.getValue());
				
				AVector<?> expected=Vectors.of(MessageTag.QUERY,0,Keywords.FOO,null);
				assertEquals(expected,v);
			}
			
			{ // Netty client
				Convex convex=ConvexRemote.connectNetty(addr);
				Result r=convex.query(":hello").join();
				assertFalse(r.isError());
			}
		}
	}
	
	@Test public void testBigMessage() throws IOException, InterruptedException, TimeoutException, BadFormatException {
		try (NettyServer server = new NettyServer(0)) {
			server.launch();
			server.setReceiveAction(m->{
				Result r;
				try {
					m.getPayload(NullStore.INSTANCE);
					r = Result.create(m.getRequestID(), m.getPayload(), null);
				} catch (BadFormatException e) {
					throw new Error("Bad format",e);
				}
				m.returnResult(r);
			});
			Integer port=server.getPort();
			
			assertNotNull(port);
			InetSocketAddress socketAddr=server.getHostAddress();
			assertEquals(port,socketAddr.getPort());
			
			ArrayBlockingQueue<Message> queue=new ArrayBlockingQueue<>(100);
		
			NettyConnection client=NettyConnection.connect(socketAddr, m->{
				queue.add(m);
			});
			
			Blob blob=Blobs.createRandom(100000).toFlatBlob();
			
			Message mq=Message.createQuery(10, blob, Address.create(17));
			client.send(mq);
			
			Message m=queue.poll(1000,TimeUnit.MILLISECONDS);
			assertEquals(RT.cvm(10),m.getResultID());


		}
	}

	/**
	 * #482: the inbound connection limit is configurable; connections beyond the
	 * limit are closed by the server. The two accepted clients complete a query
	 * round-trip first, guaranteeing their channels are registered before the
	 * third connection attempts — no sleeps needed.
	 */
	@Test public void testMaxClientConnections() throws Exception {
		try (NettyServer server = new NettyServer(0)) {
			server.setMaxClientConnections(2);
			assertEquals(2, server.getMaxClientConnections());
			server.launch();
			InetSocketAddress addr = server.getHostAddress();

			Convex c1 = Convex.connect(addr);
			Convex c2 = Convex.connect(addr);
			try {
				assertFalse(c1.query(":one").join().isError());
				assertFalse(c2.query(":two").join().isError());
				assertEquals(2, server.getClientConnectionCount());

				// Third connection is rejected: the server closes it, so the
				// socket observes EOF (read returns -1)
				try (java.net.Socket s = new java.net.Socket()) {
					s.connect(addr, 5000);
					s.setSoTimeout(10000);
					assertEquals(-1, s.getInputStream().read(),
						"Connection beyond the limit should be closed by the server");
				}
			} finally {
				c1.close();
				c2.close();
			}
		}
	}

	/**
	 * #41: an inbound frame whose declared VLQ length exceeds MAX_MESSAGE_LENGTH
	 * is rejected during length parsing — before any body bytes are buffered —
	 * and the connection is closed. The server remains healthy for other clients.
	 */
	@Test public void testOversizedFrameRejected() throws Exception {
		try (NettyServer server = new NettyServer(0)) {
			server.launch();
			InetSocketAddress addr = server.getHostAddress();

			try (java.net.Socket s = new java.net.Socket()) {
				s.connect(addr, 5000);
				s.setSoTimeout(10000);
				// Four continuation bytes declare a VLQ length of 2^28-1
				// (~268MB), over the 50MB transport cap — rejected mid-parse
				s.getOutputStream().write(new byte[] {(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF});
				s.getOutputStream().flush();
				assertEquals(-1, s.getInputStream().read(),
					"Server should close a connection declaring an oversized frame");
			}

			// Server still healthy after rejecting the hostile frame
			Convex convex = Convex.connect(addr);
			try {
				assertFalse(convex.query(":still-alive").join().isError());
			} finally {
				convex.close();
			}
		}
	}

	/**
	 * #566: when an inbound channel closes, the handler fires the disconnect action with its
	 * connection, letting the server release per-connection state eagerly. Uses an
	 * EmbeddedChannel so the channelInactive lifecycle is driven deterministically.
	 */
	@Test public void testChannelInactiveFiresDisconnect() {
		NettyInboundHandler handler = new NettyInboundHandler(msg -> null, null);
		EmbeddedChannel ch = new EmbeddedChannel(handler);
		NettyServerConnection conn = new NettyServerConnection(ch, handler);
		handler.setConnection(conn);

		CompletableFuture<AConnection> disconnected = new CompletableFuture<>();
		handler.setDisconnectAction(disconnected::complete);

		ch.close();

		assertTrue(disconnected.isDone(), "channelInactive should fire the disconnect action");
		assertEquals(conn, disconnected.getNow(null));
	}
}
