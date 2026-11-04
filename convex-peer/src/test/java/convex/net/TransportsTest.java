package convex.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.cpos.CPoSConstants;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blobs;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.net.impl.netty.NettyServer;

public class TransportsTest {

	@Test
	public void endpointsRetainTheirTransport() throws Exception {
		assertEquals(URI.create("tcp://localhost:18888"),Transports.endpoint("localhost:18888"));
		assertEquals(URI.create("tcp://localhost"),Transports.endpoint("localhost"));
		URI tls=URI.create("tls://localhost:18889");
		assertEquals(tls,Transports.endpoint(tls));
		assertSame(Transports.TLS,Transports.forEndpoint(tls));
		assertEquals(URI.create("tcp://[::1]:18888"),
			Transports.endpoint(InetSocketAddress.createUnresolved("::1",18888)));
		assertThrows(IllegalArgumentException.class,() -> Convex.connect("https://localhost:1"));
		assertThrows(IllegalArgumentException.class,() -> Convex.connect("tls://localhost:1/path"));
		assertThrows(IllegalArgumentException.class,() -> Transports.endpoint("tcp://user@localhost:1"));
	}

	/** An operator names a peer by a TCP or TLS endpoint, with the default port if none; a DID is not read as a host. */
	@Test
	public void peerEndpointsNeedATransportAndTakeTheDefaultPort() {
		assertEquals(URI.create("tcp://localhost:19888"),Transports.peerEndpoint(" localhost:19888 "));
		assertEquals(URI.create("tcp://10.0.0.2:19888"),Transports.peerEndpoint("tcp://10.0.0.2:19888"));
		assertEquals(URI.create("tls://peer.example.com:443"),Transports.peerEndpoint("tls://peer.example.com:443"));
		assertEquals(URI.create("tcp://[::1]:19888"),Transports.peerEndpoint("[::1]:19888"));
		int port=Constants.DEFAULT_PEER_PORT;
		assertEquals(URI.create("tcp://localhost:"+port),Transports.peerEndpoint("localhost"));
		assertEquals(URI.create("tls://peer.example.com:"+port),Transports.peerEndpoint("tls://peer.example.com"));
		assertEquals(URI.create("tcp://[::1]:"+port),Transports.peerEndpoint("tcp://[::1]"));
		for (String invalid:new String[] {"did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK",
				"DID:web:peer.example.com","localhost:0","localhost:70000",
				"https://localhost:443","tcp://localhost:1/path","tcp://localhost:1?q=1","tcp://user@localhost:1",""}) {
			assertThrows(IllegalArgumentException.class,() -> Transports.peerEndpoint(invalid),invalid);
		}
		assertThrows(IllegalArgumentException.class,() -> Transports.peerEndpoint(null));
	}

	@Test
	public void plainTCPFactoriesAndReconnectNeedNoTLSConfiguration() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.launch();
			InetSocketAddress address=server.getHostAddress();
			URI endpoint=Transports.endpoint(address);
			var expectedPeer=AKeyPair.generate().getAccountKey();
			assertSame(Transports.TCP,Transports.forEndpoint(endpoint,expectedPeer));
			try (ConvexRemote netty=ConvexRemote.connectNetty(address);
					ConvexRemote nio=ConvexRemote.connectNIO(address);
					ConvexRemote defaultClient=ConvexRemote.connect(address);
					ConvexRemote knownPeer=ConvexRemote.connect(endpoint,expectedPeer)) {
				for (ConvexRemote client : new ConvexRemote[] {netty,nio,defaultClient,knownPeer}) {
					assertFalse(client.querySync(":plain-tcp").isError());
					client.setMaxInboundMessageLength(4096);
					client.reconnect();
					assertEquals(endpoint,client.getEndpoint());
					assertEquals(4096,client.getMaxInboundMessageLength());
					assertFalse(client.querySync(":reconnected").isError());
					assertNull(client.getVerifiedPeer());
				}
			}
		}
	}

	/** A Netty connection stops reading while its delivery waits, so later messages follow it. */
	@Test
	public void nettyPausesWhileDeliveryWaits() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.launch();
			assertPausesWhileDeliveryWaits(Transports.NETTY,server,Transports.endpoint(server.getHostAddress()));
		}
	}

	/**
	 * Has the server send two pings over a connection the transport opened, the first
	 * asking to wait: the second must be delivered only once that wait has ended.
	 */
	static void assertPausesWhileDeliveryWaits(Transport transport,NettyServer server,URI endpoint) throws Exception {
		CompletableFuture<AConnection> back=new CompletableFuture<>();
		server.setReceiveAction(message -> back.complete(message.getConnection()));
		CountDownLatch waiting=new CountDownLatch(1);
		CountDownLatch release=new CountDownLatch(1);
		List<ACell> delivered=Collections.synchronizedList(new ArrayList<>());
		CompletableFuture<Void> second=new CompletableFuture<>();
		Predicate<Message> retry=message -> {
			waiting.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
			delivered.add(CVMLong.ONE);
			return true;
		};
		AConnection client=transport.connectWithDelivery(endpoint,message -> {
			ACell id=requestID(message);
			if (CVMLong.ONE.equals(id)) return retry;
			delivered.add(id);
			second.complete(null);
			return null;
		},(int)CPoSConstants.MAX_MESSAGE_LENGTH);
		try {
			assertTrue(client.sendMessage(Message.createPing(0)));
			AConnection serverSide=back.get(5,TimeUnit.SECONDS);
			assertTrue(serverSide.sendMessage(Message.createPing(1)));
			assertTrue(serverSide.sendMessage(Message.createPing(2)));
			assertTrue(waiting.await(5,TimeUnit.SECONDS));
			release.countDown();
			second.get(5,TimeUnit.SECONDS);
			assertEquals(List.of(CVMLong.ONE,CVMLong.create(2)),delivered);
		} finally {
			release.countDown();
			client.close();
		}
	}

	private static ACell requestID(Message message) {
		try {
			message.getPayload(null);
			return message.getRequestID();
		} catch (BadFormatException e) {
			return null;
		}
	}

	/** A transport that cannot pause answers a request its delivery asks to wait for with :LOAD, and drops anything else. */
	@Test
	public void deliveryWithoutPausingRefusesWaits() {
		List<Message> returned=new ArrayList<>();
		AConnection connection=new AConnection() {
			@Override public boolean sendMessage(Message msg) { return trySendMessage(msg); }
			@Override public boolean trySendMessage(Message msg) { returned.add(msg); return true; }
			@Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("192.0.2.3",30000); }
			@Override public boolean isClosed() { return false; }
			@Override public void close() {}
			@Override public long getReceivedCount() { return 0; }
		};
		List<Message> delivered=new ArrayList<>();
		Consumer<Message> receive=Transport.withoutPausing(message -> {
			delivered.add(message);
			return CVMLong.create(7).equals(message.getRequestID()) ? null : waiting -> true;
		});

		receive.accept(Message.createPing(5).withConnection(connection));
		assertEquals(1,returned.size());
		Result refused=returned.get(0).toResult();
		assertEquals(ErrorCodes.LOAD,refused.getErrorCode());
		assertEquals(CVMLong.create(5),refused.getID());

		receive.accept(Message.createDataMessage(List.of(Blobs.createRandom(400)),
			(int)CPoSConstants.MAX_MESSAGE_LENGTH).withConnection(connection));
		receive.accept(Message.createPing(7).withConnection(connection));
		assertEquals(1,returned.size(),"only a request is answered, and only when asked to wait");
		assertEquals(3,delivered.size());
	}

	/** A future transport can keep a URI path and reuse correlation without a registry. */
	@Test
	public void explicitTransportRetainsEndpointOnReconnect() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.launch();
			URI endpoint=URI.create("test://localhost/messages?version=1");
			AtomicInteger opens=new AtomicInteger();
			Transport transport=(uri,receive,limit) -> {
				assertEquals(endpoint,uri);
				opens.incrementAndGet();
				return Transports.NETTY.connect(Transports.endpoint(server.getHostAddress()),receive,limit);
			};
			try (ConvexRemote client=ConvexRemote.connect(endpoint,transport)) {
				assertFalse(client.querySync(":first").isError());
				client.reconnect();
				assertEquals(2,opens.get());
				assertFalse(client.querySync(":second").isError());
			}
		}
	}
}
