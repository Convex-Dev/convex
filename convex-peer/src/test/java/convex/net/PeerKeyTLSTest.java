package convex.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.CertUtils;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.data.Maps;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.init.Init;
import convex.core.store.MemoryStore;
import convex.net.impl.netty.NettyServer;
import convex.node.LatticeConnectionManager;
import convex.peer.API;
import convex.peer.Config;
import convex.peer.Server;

public class PeerKeyTLSTest {

	private static X509Certificate certificate(AKeyPair issuer, KeyPair tlsKey) throws Exception {
		Instant now=Instant.now();
		return CertUtils.signPeerCertificate(issuer,tlsKey.getPublic(),now.minusSeconds(60),now.plusSeconds(3600));
	}

	private static SSLContext serverContext(KeyPair tlsKey, X509Certificate certificate) throws Exception {
		char[] password="test-only".toCharArray();
		KeyStore identity=KeyStore.getInstance("PKCS12");
		identity.load(null,null);
		identity.setKeyEntry("tls",tlsKey.getPrivate(),password,new Certificate[] {certificate});
		KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(identity,password);
		SSLContext context=SSLContext.getInstance("TLS");
		context.init(kmf.getKeyManagers(),null,null);
		return context;
	}

	private static URI endpoint(int port) {
		return URI.create("tls://127.0.0.1:"+port);
	}

	@Test
	public void knownPeerIgnoresLocationAndRetainsItsKeyOnReconnect() throws Exception {
		AKeyPair issuer=AKeyPair.generate();
		KeyPair tlsKey=AKeyPair.generate().getJCAKeyPair();
		X509Certificate certificate=certificate(issuer,tlsKey);
		assertNotEquals(issuer.getPublic(),certificate.getPublicKey());
		assertNull(certificate.getSubjectAlternativeNames());
		try (NettyServer server=new NettyServer(0)) {
			server.setSSLContext(serverContext(tlsKey,certificate));
			server.launch();
			URI endpoint=endpoint(server.getPort());
			try (ConvexRemote client=ConvexRemote.connect(endpoint,issuer.getAccountKey())) {
				assertFalse(client.querySync(":peer-key-tls").isError());
				assertNull(client.getVerifiedPeer(),"Protocol admission still requires its signed challenge");
				client.reconnect();
				assertFalse(client.querySync(":reconnected").isError());
			}
			assertThrows(IOException.class,() -> ConvexRemote.connect(endpoint,AKeyPair.generate().getAccountKey()));
			assertThrows(IOException.class,() -> ConvexRemote.connect(endpoint));

			// Discovery already supplies the expected key: use it without custom TLS configuration.
			try (MemoryStore store=new MemoryStore(); LatticeConnectionManager manager=new LatticeConnectionManager(store)) {
				manager.updateDiscoveredPeer(issuer.getAccountKey(),Vectors.of(Strings.create(endpoint.toString())),1);
				manager.addPeer(issuer.getAccountKey());
				manager.start();
				Convex client=manager.whenConnected(issuer.getAccountKey()).get(5,TimeUnit.SECONDS);
				assertFalse(client.querySync(":discovered").isError());
			}
		}
	}

	@Test
	public void rejectsInvalidSignaturesAndValidity() throws Exception {
		AKeyPair issuer=AKeyPair.generate();
		AKeyPair impostor=AKeyPair.generate();
		KeyPair tlsKey=AKeyPair.generate().getJCAKeyPair();
		PeerTrustManager trust=new PeerTrustManager(issuer.getAccountKey());
		X509Certificate valid=certificate(issuer,tlsKey);
		assertDoesNotThrow(() -> trust.checkServerTrusted(new X509Certificate[] {valid},"UNKNOWN"));

		// Merely containing the expected public key cannot substitute for its signature.
		X509Certificate forged=certificate(impostor,issuer.getJCAKeyPair());
		assertThrows(CertificateException.class,() -> trust.checkServerTrusted(new X509Certificate[] {forged},"UNKNOWN"));
		// Nor can a correctly signed certificate elsewhere in the supplied chain authorise the leaf.
		assertThrows(CertificateException.class,() -> trust.checkServerTrusted(new X509Certificate[] {forged,valid},"UNKNOWN"));

		Instant now=Instant.now();
		X509Certificate expired=CertUtils.signPeerCertificate(issuer,tlsKey.getPublic(),now.minusSeconds(7200),now.minusSeconds(3600));
		X509Certificate future=CertUtils.signPeerCertificate(issuer,tlsKey.getPublic(),now.plusSeconds(3600),now.plusSeconds(7200));
		assertThrows(CertificateException.class,() -> trust.checkServerTrusted(new X509Certificate[] {expired},"UNKNOWN"));
		assertThrows(CertificateException.class,() -> trust.checkServerTrusted(new X509Certificate[] {future},"UNKNOWN"));

		byte[] encoded=valid.getEncoded();
		encoded[encoded.length-1]^=1;
		X509Certificate tampered=(X509Certificate)CertificateFactory.getInstance("X.509")
			.generateCertificate(new ByteArrayInputStream(encoded));
		assertThrows(CertificateException.class,() -> trust.checkServerTrusted(new X509Certificate[] {tampered},"UNKNOWN"));
		assertThrows(CertificateException.class,() -> trust.checkServerTrusted(new X509Certificate[0],"UNKNOWN"));
	}

	@Test
	public void certificateHolderMustProvePossessionOfTLSPrivateKey() throws Exception {
		AKeyPair issuer=AKeyPair.generate();
		KeyPair certifiedKey=AKeyPair.generate().getJCAKeyPair();
		KeyPair impostor=AKeyPair.generate().getJCAKeyPair();
		AtomicInteger delivered=new AtomicInteger();
		try (NettyServer server=new NettyServer(0)) {
			server.setSSLContext(serverContext(impostor,certificate(issuer,certifiedKey)));
			server.setReceiveAction(message -> delivered.incrementAndGet());
			server.launch();
			assertThrows(IOException.class,() -> ConvexRemote.connect(endpoint(server.getPort()),issuer.getAccountKey()));
			assertEquals(0,delivered.get());
		}
	}

	@Test
	public void consensusManagerUsesExpectedKeyForTLSAndPeerChallenge() throws Exception {
		AKeyPair remoteKey=AKeyPair.generate();
		AKeyPair localKey=AKeyPair.generate();
		KeyPair tlsKey=AKeyPair.generate().getJCAKeyPair();
		var state=Migrations.applyAll(Init.createState(List.of(remoteKey.getAccountKey(),localKey.getAccountKey())));
		try (MemoryStore remoteStore=new MemoryStore(); MemoryStore localStore=new MemoryStore()) {
			Map<Keyword,Object> remoteConfig=Maps.hashMapOf(Keywords.KEYPAIR,remoteKey,Keywords.STATE,state,Keywords.STORE,remoteStore,
				Keywords.PORT,0,Keywords.AUTO_MANAGE,false,Keywords.OUTGOING_CONNECTIONS,0,
				Config.TLS_PORT,0,Config.TLS_CONTEXT,serverContext(tlsKey,certificate(remoteKey,tlsKey)));
			Map<Keyword,Object> localConfig=Maps.hashMapOf(Keywords.KEYPAIR,localKey,Keywords.STATE,state,Keywords.STORE,localStore,
				Keywords.PORT,0,Keywords.AUTO_MANAGE,false,Keywords.OUTGOING_CONNECTIONS,0);
			try (Server remote=API.launchPeer(remoteConfig); Server local=API.launchPeer(localConfig)) {
				URI endpoint=endpoint(remote.getTLSPort());
				Convex connection=local.getConnectionManager().connectToPeer(endpoint,remoteKey.getAccountKey()).get(5,TimeUnit.SECONDS);
				assertEquals(remoteKey.getAccountKey(),connection.getVerifiedPeer());
				ExecutionException failure=assertThrows(ExecutionException.class,() -> local.getConnectionManager()
					.connectToPeer(endpoint,localKey.getAccountKey()).get(5,TimeUnit.SECONDS));
				assertInstanceOf(IOException.class,failure.getCause());
			}
		}
	}
}
