package convex.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import convex.core.Constants;
import convex.core.data.AccountKey;
import convex.core.exceptions.BadFormatException;
import convex.net.impl.netty.NettyConnection;
import convex.net.impl.nio.Connection;
import convex.peer.Config;

/** Built-in transports and endpoint parsing. Unqualified addresses use TCP. */
public final class Transports {

	private Transports() {}

	public static final Transport NETTY = (endpoint, receive, limit) ->
		NettyConnection.connect(socketAddress(endpoint, "tcp"), receive, limit);

	public static final Transport NIO = (endpoint, receive, limit) ->
		Connection.connect(socketAddress(endpoint, "tcp"), receive, limit);

	public static final Transport TCP = Config.USE_NETTY_CLIENT ? NETTY : NIO;

	/** Uses the JVM's configured key and trust stores, resolved on connection. */
	public static final Transport TLS = (endpoint, receive, limit) ->
		tls(defaultSSLContext()).connect(endpoint, receive, limit);

	/** TLS with an explicitly supplied trust policy; hostname verification is retained. */
	public static Transport tls(SSLContext context) {
		Objects.requireNonNull(context);
		return (endpoint, receive, limit) ->
			NettyConnection.connect(socketAddress(endpoint, "tls"), receive, limit, context);
	}

	/**
	 * TLS authenticated by a certificate signed directly by the expected peer key.
	 * DNS names, IP addresses and public CAs do not establish identity in this mode.
	 */
	public static Transport tls(AccountKey expectedPeer) {
		Objects.requireNonNull(expectedPeer);
		return (endpoint, receive, limit) -> {
			SSLContext context;
			try {
				context=SSLContext.getInstance("TLS");
				context.init(null,new TrustManager[] {new PeerTrustManager(expectedPeer)},null);
			} catch (GeneralSecurityException | BadFormatException e) {
				throw new IOException("Unable to initialise peer-key TLS",e);
			}
			return NettyConnection.connect(socketAddress(endpoint,"tls"),receive,limit,context,false);
		};
	}

	public static SSLContext defaultSSLContext() throws IOException {
		try {
			return SSLContext.getDefault();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Unable to initialise the default TLS context", e);
		}
	}

	/** Selects a built-in transport. Unknown schemes never fall back to TCP. */
	public static Transport forEndpoint(URI endpoint) {
		return forEndpoint(endpoint,null);
	}

	/** Known peer keys take precedence over location-based TLS authentication. */
	public static Transport forEndpoint(URI endpoint, AccountKey expectedPeer) {
		if (endpoint.getScheme()==null) throw new IllegalArgumentException("Transport scheme required: " + endpoint);
		return switch (endpoint.getScheme().toLowerCase(Locale.ROOT)) {
			case "tcp" -> TCP;
			case "tls" -> expectedPeer==null ? TLS : tls(expectedPeer);
			default -> throw new IllegalArgumentException("Unsupported transport: " + endpoint.getScheme());
		};
	}

	/** Parses a URI, URL, socket address, hostname or host:port without doing DNS. */
	public static URI endpoint(Object address) {
		if (address instanceof InetSocketAddress socket) {
			try {
				return new URI("tcp", null, socket.getHostString(), socket.getPort(), null, null, null);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException("Invalid socket address: " + socket, e);
			}
		}
		if (!(address instanceof URI || address instanceof URL || address instanceof String)) {
			throw new IllegalArgumentException("Expected a peer URI or socket address");
		}
		String text=address.toString().trim();
		URI uri=URI.create(text.contains("://") ? text : "tcp://" + text);
		if (uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null) {
			throw new IllegalArgumentException("Invalid peer endpoint: " + uri);
		}
		return uri;
	}

	/**
	 * Parses a peer an operator names by address: a {@code tcp://} or {@code tls://}
	 * URI, or {@code host:port} for TCP, always with a port. A DID is a name
	 * {@link PeerResolver} resolves, so one is refused here rather than read as a host name.
	 *
	 * @param peer peer address as the operator wrote it
	 * @return the peer's transport endpoint, without DNS resolution
	 * @throws IllegalArgumentException if the peer is not named by such an endpoint
	 */
	public static URI peerEndpoint(String peer) {
		if (peer==null) throw new IllegalArgumentException("Peer address required");
		String text=peer.trim();
		if (text.regionMatches(true,0,"did:",0,4)) {
			throw new IllegalArgumentException("A DID is not a peer address: "+text);
		}
		URI endpoint;
		try {
			endpoint=endpoint(text);
			forEndpoint(endpoint);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid peer address "+text+": "+e.getMessage(),e);
		}
		if (endpoint.getRawQuery()!=null || (endpoint.getRawPath()!=null && !endpoint.getRawPath().isEmpty())) {
			throw new IllegalArgumentException("Invalid peer address "+text+": a peer endpoint has no path or query");
		}
		int port=endpoint.getPort();
		if (port<1 || port>65535) {
			throw new IllegalArgumentException("Invalid peer address "+text+": a port from 1 to 65535 is required");
		}
		return endpoint;
	}

	/** Native stream transports have no path, query or user information. */
	private static InetSocketAddress socketAddress(URI endpoint, String scheme) {
		if (!scheme.equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost()==null
				|| endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null
				|| endpoint.getRawFragment()!=null
				|| (endpoint.getRawPath()!=null && !endpoint.getRawPath().isEmpty())) {
			throw new IllegalArgumentException("Invalid " + scheme + " endpoint: " + endpoint);
		}
		int port=endpoint.getPort();
		return new InetSocketAddress(endpoint.getHost(), port<0 ? Constants.DEFAULT_PEER_PORT : port);
	}
}
