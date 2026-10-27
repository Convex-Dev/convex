package convex.core.crypto;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/**
 * TLS contexts and engines shared by Convex servers and clients (CAD055).
 *
 * <p>Every engine created here negotiates only TLS 1.3 or TLS 1.2, whatever else
 * the JVM enables. A context presents one identity: a node identity (a certificate
 * signed by the node's key, see {@link CertUtils#signPeerCertificate}) or a service
 * identity (an ordinary hostname certificate).
 */
public class TLS {

	/** Protocols a Convex endpoint may negotiate; earlier versions are never enabled */
	private static final Set<String> PROTOCOLS=Set.of("TLSv1.3","TLSv1.2");

	/** Protects the key entry of an in-memory key store that never leaves this class */
	private static final char[] IN_MEMORY_PASSWORD="in-memory".toCharArray();

	/**
	 * Creates a context presenting the identity held in a key store, such as one
	 * loaded with {@link PFXTools#loadStore}.
	 *
	 * @param identity Key store holding the private key and its certificate chain
	 * @param password Password protecting the key entry
	 * @return Context for server engines
	 * @throws GeneralSecurityException If the key store cannot supply a key
	 */
	public static SSLContext serverContext(KeyStore identity, char[] password) throws GeneralSecurityException {
		KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(identity,password);
		SSLContext context=SSLContext.getInstance("TLS");
		context.init(kmf.getKeyManagers(),null,null);
		return context;
	}

	/**
	 * Creates a context presenting a private key and its certificate chain.
	 *
	 * @param key Private key matching the leaf certificate
	 * @param chain Certificate chain, leaf first
	 * @return Context for server engines
	 * @throws GeneralSecurityException If the key and chain cannot form an identity
	 */
	public static SSLContext serverContext(PrivateKey key, X509Certificate... chain) throws GeneralSecurityException {
		KeyStore identity=KeyStore.getInstance("PKCS12");
		try {
			identity.load(null,null);
		} catch (IOException e) {
			throw new GeneralSecurityException("Unable to create an in-memory key store",e);
		}
		identity.setKeyEntry("tls",key,IN_MEMORY_PASSWORD,chain);
		return serverContext(identity,IN_MEMORY_PASSWORD);
	}

	/**
	 * Creates an engine for the server side of a connection.
	 *
	 * @param context Context presenting the server's identity
	 * @return Server engine negotiating only TLS 1.3 or 1.2
	 */
	public static SSLEngine serverEngine(SSLContext context) {
		SSLEngine engine=context.createSSLEngine();
		engine.setUseClientMode(false);
		restrictProtocols(engine);
		return engine;
	}

	/**
	 * Creates an engine for the client side of a connection.
	 *
	 * @param context Context holding the client's trust policy
	 * @param host Host the client dialled, used for hostname checks and session resumption
	 * @param port Port the client dialled
	 * @return Client engine negotiating only TLS 1.3 or 1.2
	 */
	public static SSLEngine clientEngine(SSLContext context, String host, int port) {
		SSLEngine engine=context.createSSLEngine(host,port);
		engine.setUseClientMode(true);
		restrictProtocols(engine);
		return engine;
	}

	/** Removes protocols older than TLS 1.2; never enables one the JVM has disabled */
	private static void restrictProtocols(SSLEngine engine) {
		engine.setEnabledProtocols(Stream.of(engine.getEnabledProtocols()).filter(PROTOCOLS::contains).toArray(String[]::new));
	}
}
