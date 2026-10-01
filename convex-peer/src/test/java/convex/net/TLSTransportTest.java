package convex.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.CertUtils;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.message.Message;
import convex.core.store.MemoryStore;
import convex.net.impl.netty.NettyServer;
import convex.node.LatticeConnectionManager;
import convex.peer.API;
import convex.peer.Config;
import convex.peer.PeerConfig;
import convex.peer.Server;

/** Real TLS sockets, isolated trust stores and OS-assigned ports. */
public class TLSTransportTest {

	private static SSLContext serverContext;
	private static SSLContext clientContext;
	private static SSLContext untrustedContext;

	@BeforeAll
	public static void certificates() throws Exception {
		var generator=KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		var keys=generator.generateKeyPair();
		var certificate=CertUtils.selfSign(keys,"CN=localhost");
		char[] password="test-only".toCharArray();
		KeyStore identity=KeyStore.getInstance("PKCS12");
		identity.load(null,null);
		identity.setKeyEntry("server",keys.getPrivate(),password,new Certificate[] {certificate});
		KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(identity,password);
		serverContext=SSLContext.getInstance("TLS");
		serverContext.init(kmf.getKeyManagers(),null,null);

		KeyStore trust=KeyStore.getInstance("PKCS12");
		trust.load(null,null);
		untrustedContext=clientContext(trust);
		trust.setCertificateEntry("server",certificate);
		clientContext=clientContext(trust);
	}

	private static SSLContext clientContext(KeyStore trust) throws Exception {
		TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trust);
		SSLContext context=SSLContext.getInstance("TLS");
		context.init(null,tmf.getTrustManagers(),null);
		return context;
	}

	private static URI endpoint(int port) {
		return URI.create("tls://localhost:"+port);
	}

	@Test
	public void peerOffersTCPAndTLS() throws Exception {
		AKeyPair peerKey=AKeyPair.generate();
		try (MemoryStore store=new MemoryStore(); MemoryStore acquired=new MemoryStore()) {
			Map<Keyword,Object> config=Maps.hashMapOf(
				Keywords.PORT,0,Config.TLS_PORT,0,Config.TLS_CONTEXT,serverContext,
				Keywords.KEYPAIR,peerKey,Keywords.STORE,store,Keywords.AUTO_MANAGE,false,
				Keywords.OUTGOING_CONNECTIONS,0,
				Keywords.STATE,Migrations.applyAll(Init.createState(List.of(peerKey.getAccountKey()))));
			try (Server server=API.launchPeer(config);
					Convex tcp=Convex.connect(server.getHostAddress());
					ConvexRemote tls=ConvexRemote.connect(endpoint(server.getTLSPort()),Transports.tls(clientContext))) {
				assertNotEquals(server.getPort(),server.getTLSPort());
				assertEquals(CVMLong.create(3),tcp.querySync("(+ 1 2)").getValue());
				assertEquals(CVMLong.create(3),tls.querySync("(+ 1 2)").getValue());
				assertNull(tls.getVerifiedPeer(),"TLS alone must not confer peer trust");
				tls.setKeyPair(AKeyPair.generate());
				assertEquals(peerKey.getAccountKey(),tls.verifyPeer(peerKey.getAccountKey()).get(5,TimeUnit.SECONDS));
				assertEquals(server.getPeer().getConsensusState(),
					tls.acquire(server.getPeer().getConsensusState().getHash(),acquired).get(5,TimeUnit.SECONDS));
			}
		}
	}

	@Test
	public void managerRetainsTLSWhenAdoptingAClient() throws Exception {
		try (NettyServer server=new NettyServer(0); MemoryStore store=new MemoryStore();
				LatticeConnectionManager manager=new LatticeConnectionManager(store)) {
			server.setSSLContext(serverContext);
			server.launch();
			URI endpoint=endpoint(server.getPort());
			try (ConvexRemote client=ConvexRemote.connect(endpoint,Transports.tls(clientContext))) {
				var key=AKeyPair.generate().getAccountKey();
				manager.addPeer(key,client).get(5,TimeUnit.SECONDS);
				assertEquals(endpoint.toString(),manager.getDesiredPeers().get(key).transports.get(0).toString());
			}
		}
	}

	@Test
	public void reconnectRetainsTLSFactoryAndLimit() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.setSSLContext(serverContext);
			server.launch();
			URI endpoint=endpoint(server.getPort());
			AtomicInteger opens=new AtomicInteger();
			Transport transport=(uri,receive,limit) -> {
				assertEquals(endpoint,uri);
				assertEquals(1_000_000,limit);
				opens.incrementAndGet();
				return Transports.tls(clientContext).connect(uri,receive,limit);
			};
			try (ConvexRemote client=ConvexRemote.connect(endpoint,transport,1_000_000)) {
				// Cross many TLS records and exercise framing of a non-embedded value.
				var payload=Blobs.createRandom(100_000);
				Message query=Message.createQuery(123,payload,null);
				assertEquals(query.getPayload(),client.message(query).get(5,TimeUnit.SECONDS).getValue());
				client.close();
				client.reconnect();
				assertEquals(endpoint,client.getEndpoint());
				assertEquals(2,opens.get());
				assertFalse(client.querySync(":again").isError());
			}
		}
	}

	@Test
	public void certificateAndHostnameAreVerified() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.setSSLContext(serverContext);
			server.launch();
			URI endpoint=endpoint(server.getPort());
			assertThrows(IOException.class,() ->
				ConvexRemote.connect(endpoint,Transports.tls(untrustedContext)));
			assertThrows(IOException.class,() ->
				ConvexRemote.connect(URI.create("tls://127.0.0.1:"+server.getPort()),Transports.tls(clientContext)));
			try (Convex client=ConvexRemote.connect(endpoint,Transports.tls(clientContext))) {
				assertFalse(client.querySync(":healthy").isError());
			}
		}
	}

	@Test
	public void tlsNeverFallsBackToPlaintext() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.setReceiveAction(m -> m.getConnection().close());
			server.launch();
			assertThrows(IOException.class,() ->
				ConvexRemote.connect(endpoint(server.getPort()),Transports.tls(clientContext)));
		}
	}

	@Test
	public void tlsEnforcesFrameLimitAndClosesClients() throws Exception {
		try (NettyServer server=new NettyServer(0)) {
			server.setSSLContext(serverContext);
			server.setMaxMessageLength(64);
			server.launch();
			try (SSLSocket socket=(SSLSocket)clientContext.getSocketFactory().createSocket("localhost",server.getPort())) {
				socket.setSoTimeout(5000);
				socket.startHandshake();
				// Only the length prefix: reject before buffering the declared body.
				socket.getOutputStream().write(65);
				socket.getOutputStream().flush();
				assertEquals(-1,socket.getInputStream().read());
			}
			try (SSLSocket socket=(SSLSocket)clientContext.getSocketFactory().createSocket("localhost",server.getPort())) {
				socket.setSoTimeout(5000);
				socket.startHandshake();
				server.close();
				assertEquals(-1,socket.getInputStream().read());
			}
		}
	}

	@Test
	public void configuredTLSListenerIsOptIn() {
		assertFalse(PeerConfig.parse("{}").toLegacy().containsKey(Config.TLS_PORT));
		assertEquals(0,PeerConfig.parse("{peer:{tlsPort:0}}").toLegacy().get(Config.TLS_PORT));
		assertThrows(IllegalArgumentException.class,() -> PeerConfig.parse("{peer:{tlsPort:-1}}").toLegacy());
		assertThrows(IllegalArgumentException.class,() -> PeerConfig.parse("{peer:{tlsPort:65536}}").toLegacy());
	}
}
