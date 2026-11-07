package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.junit.jupiter.api.Test;

/**
 * Engines from {@link TLS} negotiate only TLS 1.3 or 1.2 (CAD055).
 */
public class TLSTest {

	private static void assertModernProtocols(SSLEngine engine) {
		String[] protocols=engine.getEnabledProtocols();
		assertTrue(protocols.length>0,"No protocols enabled");
		for (String protocol: protocols) {
			assertTrue(Set.of("TLSv1.3","TLSv1.2").contains(protocol),protocol);
		}
	}

	@Test
	public void testServerEngine() throws Exception {
		AKeyPair node=AKeyPair.generate();
		KeyPair tlsKey=AKeyPair.generate().getJCAKeyPair();
		Instant now=Instant.now();
		X509Certificate certificate=CertUtils.signPeerCertificate(node,tlsKey.getPublic(),now.minusSeconds(60),now.plusSeconds(3600));

		SSLEngine engine=TLS.serverEngine(TLS.serverContext(tlsKey.getPrivate(),certificate));
		assertFalse(engine.getUseClientMode());
		assertModernProtocols(engine);
	}

	@Test
	public void testClientEngine() throws Exception {
		SSLEngine engine=TLS.clientEngine(SSLContext.getDefault(),"peer.example",18889);
		assertTrue(engine.getUseClientMode());
		assertEquals("peer.example",engine.getPeerHost());
		assertEquals(18889,engine.getPeerPort());
		assertModernProtocols(engine);
	}
}
