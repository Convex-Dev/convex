package convex.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
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
