package convex.node;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Immutable application configuration shared by an authoritative
 * {@link NodeServer} and, when present, its standard {@link LatticeListener}.
 *
 * <p>Follows the same {@link AMap}{@code <AString, ACell>} pattern as
 * {@link convex.peer.PeerConfig}, providing typed accessors with sensible
 * defaults. The underlying config map is immutable.</p>
 *
 * <p>Key names reuse {@link convex.peer.PeerConfig} constants where they
 * overlap (port, persist and restore) so configurations are consistent
 * across peer and lattice node servers.</p>
 *
 * <p>{@code NodeServer} consumes persistence and validation settings only.
 * {@code LatticeListener} independently consumes the port and physical inbound
 * limits. Passing this object to a server does not create, configure or own a
 * transport.</p>
 *
 * <p>Propagation-group queue, route, acquisition and publication limits belong
 * to {@link LatticePropagatorConfig}. Deprecated group accessors remain here only
 * to migrate callers which previously used one combined map. Attaching a group
 * never copies configuration from the node.</p>
 *
 * <p>Some fields, notably {@link #URL}, are optional metadata for application
 * wrappers and discovery adapters. The schema-independent {@link NodeServer}
 * does not publish or interpret them.</p>
 */
public class NodeConfig {

	// ========== Config keys ==========

	/**
	 * Standard {@link LatticeListener} port (Long). Zero selects an available port; negative
	 * disables the listener.
	 */
	public static final AString PORT = Strings.intern("port");

	/** Whether to persist state to store (Boolean, default true). */
	public static final AString PERSIST = Strings.intern("persist");

	/** Whether to restore state from store on startup (Boolean, default true). */
	public static final AString RESTORE = Strings.intern("restore");

	/** Interval between periodic persistence runs in milliseconds (Long, default 30000). */
	public static final AString PERSIST_INTERVAL = Strings.intern("persistInterval");

	/** Optional advertised URL consumed by a discovery adapter (AString).
	 *  {@link NodeServer} itself ignores this field. */
	public static final AString URL = Strings.intern("url");

	/** @deprecated Use {@link LatticePropagatorConfig#MAX_INBOUND_VALUE_SIZE}. */
	@Deprecated
	public static final AString MAX_INBOUND_VALUE_SIZE =
		LatticePropagatorConfig.MAX_INBOUND_VALUE_SIZE;

	/**
	 * Whether development networks may publish a private or loopback {@link #URL}
	 * (Boolean, default false).
	 */
	public static final AString ALLOW_PRIVATE_URL = Strings.intern("allowPrivateURL");

	/** @deprecated Use {@link LatticePropagatorConfig#MAX_CONSECUTIVE_REJECTS}. */
	@Deprecated
	public static final AString MAX_CONSECUTIVE_REJECTS =
		LatticePropagatorConfig.MAX_CONSECUTIVE_REJECTS;

	/** Maximum encoded inbound listener message size in bytes (Long, default 4 MiB). */
	public static final AString MAX_MESSAGE_SIZE = Strings.intern("maxMessageSize");

	/** @deprecated Use {@link LatticePropagatorConfig#MAX_DELTA_MESSAGE_SIZE}. */
	@Deprecated
	public static final AString MAX_DELTA_MESSAGE_SIZE =
		LatticePropagatorConfig.MAX_DELTA_MESSAGE_SIZE;

	/** @deprecated Use {@link LatticePropagatorConfig#MAX_DELTA_BROADCAST_SIZE}. */
	@Deprecated
	public static final AString MAX_DELTA_BROADCAST_SIZE =
		LatticePropagatorConfig.MAX_DELTA_BROADCAST_SIZE;

	/** @deprecated Use {@link LatticePropagatorConfig#MAX_TRUSTED_MESSAGE_SIZE}. */
	@Deprecated
	public static final AString MAX_TRUSTED_MESSAGE_SIZE =
		LatticePropagatorConfig.MAX_TRUSTED_MESSAGE_SIZE;

	/** Maximum simultaneous listener connections (Long, default 256). */
	public static final AString MAX_CONNECTIONS = Strings.intern("maxConnections");

	/** @deprecated Use {@link LatticePropagatorConfig#MAX_DESIRED_PEERS}. */
	@Deprecated
	public static final AString MAX_DESIRED_PEERS =
		LatticePropagatorConfig.MAX_DESIRED_PEERS;

	/** @deprecated Use {@link LatticePropagatorConfig#INBOUND_QUEUE_SIZE}. */
	@Deprecated
	public static final AString INBOUND_QUEUE_SIZE =
		LatticePropagatorConfig.INBOUND_QUEUE_SIZE;

	/** @deprecated Use {@link LatticePropagatorConfig#MAX_INBOUND_QUEUE_BYTES}. */
	@Deprecated
	public static final AString MAX_INBOUND_QUEUE_BYTES =
		LatticePropagatorConfig.MAX_INBOUND_QUEUE_BYTES;

	/** @deprecated Use {@link LatticePropagatorConfig#INBOUND_SHUTDOWN_TIMEOUT}. */
	@Deprecated
	public static final AString INBOUND_SHUTDOWN_TIMEOUT =
		LatticePropagatorConfig.INBOUND_SHUTDOWN_TIMEOUT;

	/** Maximum accepted wall-clock lead for timestamp-ordered lattice values. */
	public static final AString MAX_FUTURE_TIMESTAMP_SKEW = Strings.intern("maxFutureTimestampSkew");

	/** Conservative public-node default: large lattice trees are transferred via DATA_REQUEST. */
	public static final int DEFAULT_MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

	/** @deprecated Use {@link LatticePropagatorConfig#DEFAULT_MAX_DELTA_BROADCAST_SIZE}. */
	@Deprecated
	public static final int DEFAULT_MAX_DELTA_BROADCAST_SIZE =
		LatticePropagatorConfig.DEFAULT_MAX_DELTA_BROADCAST_SIZE;

	/** @deprecated Use {@link LatticePropagatorConfig#DEFAULT_MAX_TRUSTED_MESSAGE_SIZE}. */
	@Deprecated
	public static final int DEFAULT_MAX_TRUSTED_MESSAGE_SIZE =
		LatticePropagatorConfig.DEFAULT_MAX_TRUSTED_MESSAGE_SIZE;

	/** Conservative public-node connection cap. */
	public static final int DEFAULT_MAX_CONNECTIONS = 256;

	/** @deprecated Use {@link LatticePropagatorConfig#DEFAULT_MAX_DESIRED_PEERS}. */
	@Deprecated
	public static final int DEFAULT_MAX_DESIRED_PEERS =
		LatticePropagatorConfig.DEFAULT_MAX_DESIRED_PEERS;

	/** @deprecated Use {@link LatticePropagatorConfig#DEFAULT_INBOUND_QUEUE_SIZE}. */
	@Deprecated
	public static final int DEFAULT_INBOUND_QUEUE_SIZE =
		LatticePropagatorConfig.DEFAULT_INBOUND_QUEUE_SIZE;

	/** @deprecated Use {@link LatticePropagatorConfig#DEFAULT_MAX_INBOUND_QUEUE_BYTES}. */
	@Deprecated
	public static final int DEFAULT_MAX_INBOUND_QUEUE_BYTES =
		LatticePropagatorConfig.DEFAULT_MAX_INBOUND_QUEUE_BYTES;

	/** @deprecated Use {@link LatticePropagatorConfig#DEFAULT_INBOUND_SHUTDOWN_TIMEOUT}. */
	@Deprecated
	public static final long DEFAULT_INBOUND_SHUTDOWN_TIMEOUT =
		LatticePropagatorConfig.DEFAULT_INBOUND_SHUTDOWN_TIMEOUT;

	/** Default wall-clock lead accepted from timestamp-ordered lattice values. */
	public static final long DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW = 30_000L;

	// ========== Instance ==========

	private final AMap<AString, ACell> config;

	private NodeConfig(AMap<AString, ACell> config) {
		this.config = (config == null) ? Maps.empty() : config;
	}

	/**
	 * Creates a configuration wrapping the given immutable map.
	 *
	 * @param config config map, or {@code null} for defaults
	 * @return node configuration
	 */
	public static NodeConfig create(AMap<AString, ACell> config) {
		return new NodeConfig(config);
	}

	/**
	 * Creates a configuration containing only defaults.
	 *
	 * @return default node configuration
	 */
	public static NodeConfig create() {
		return new NodeConfig(null);
	}

	/**
	 * Creates a configuration with a specific standard-listener port.
	 *
	 * @param port inbound port; zero selects an available port and negative disables listening
	 * @return node configuration with the port set
	 */
	public static NodeConfig port(int port) {
		return new NodeConfig(Maps.of(PORT, CVMLong.create(port)));
	}

	/**
	 * Creates a configuration for an isolated local lattice network. The listener
	 * binds to an OS-assigned port and advertises that resolved port on loopback.
	 *
	 * <p>This is intended for local development and in-process integration tests.
	 * Private transport advertisement is deliberately enabled; production nodes
	 * should configure an explicit publicly reachable {@link #URL} instead.</p>
	 *
	 * @return local-network configuration using an OS-assigned port
	 */
	public static NodeConfig localNetwork() {
		return new NodeConfig(Maps.of(
			PORT, CVMLong.ZERO,
			URL, Strings.create("tcp://localhost:0"),
			ALLOW_PRIVATE_URL, CVMBool.TRUE));
	}

	/**
	 * Returns the underlying immutable config map.
	 *
	 * @return config map, never {@code null}
	 */
	public AMap<AString, ACell> getMap() {
		return config;
	}

	// ========== Typed accessors ==========

	/**
	 * Returns the configured standard-listener port.
	 *
	 * @return port number, or {@code null} to use the transport default with fallback
	 */
	public Integer getPort() {
		CVMLong v = RT.ensureLong(config.get(PORT));
		return (v != null) ? (int) v.longValue() : null;
	}

	/**
	 * Returns whether authoritative state is persisted to the node store.
	 *
	 * @return {@code true} when persistence is enabled
	 */
	public boolean isPersist() {
		return getBool(PERSIST, true);
	}

	/**
	 * Returns whether authoritative state is restored from the node store at launch.
	 *
	 * @return {@code true} when restoration is enabled
	 */
	public boolean isRestore() {
		return getBool(RESTORE, true);
	}

	/**
	 * Returns the interval between periodic persistence barriers.
	 *
	 * @return interval in milliseconds
	 */
	public long getPersistInterval() {
		CVMLong v = RT.ensureLong(config.get(PERSIST_INTERVAL));
		return (v != null) ? v.longValue() : 30_000L;
	}

	/**
	 * Returns the optional URL available to an application discovery adapter.
	 * {@link NodeServer} does not advertise it.
	 *
	 * @return advertised URL, or {@code null} if not configured
	 */
	public AString getURL() {
		return RT.ensureString(config.get(URL));
	}

	/**
	 * Returns the transport URL to publish after the listener has bound. A configured
	 * port of zero is replaced with the actual OS-assigned listener port, preserving
	 * the remaining URI components.
	 *
	 * @param boundPort actual listener port, or {@code null} when no listener exists
	 * @return resolved advertised URL, or {@code null} when no URL is configured
	 */
	public AString getAdvertisedURL(Integer boundPort) {
		AString configured=getURL();
		if (configured==null) return null;

		try {
			URI uri=new URI(configured.toString().trim());
			if (uri.getPort()!=0) return configured;
			if (boundPort==null || boundPort<=0) {
				throw new IllegalStateException(
					"A transport URL with port 0 requires a bound network listener");
			}
			URI resolved=new URI(uri.getScheme(),uri.getUserInfo(),uri.getHost(),boundPort,
				uri.getPath(),uri.getQuery(),uri.getFragment());
			return Strings.create(resolved.toString());
		} catch (URISyntaxException e) {
			throw new IllegalStateException("Invalid node URL configuration: "+configured,e);
		}
	}

	/**
	 * Returns the maximum memory size of a complete inbound lattice value admitted
	 * by a propagation endpoint. Larger values are rejected before the authoritative
	 * merge, bounding work from untrusted peers. The default is the configured message cap.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getMaxInboundValueSize()}.
	 * @return maximum inbound value size in bytes
	 */
	@Deprecated
	public long getMaxInboundValueSize() {
		return LatticePropagatorConfig.from(this).getMaxInboundValueSize();
	}

	/**
	 * Returns whether a private, loopback or link-local {@link #URL} is permitted.
	 * Defaults to false, allowing an advertising application such as P2PNode to
	 * fail before publishing an unreachable address. {@link NodeServer} does not apply this
	 * policy because it does not publish discovery records. Set true only on dev
	 * networks where private addressing is intentional.
	 *
	 * @return {@code true} if private URLs are permitted
	 */
	public boolean isAllowPrivateURL() {
		return getBool(ALLOW_PRIVATE_URL, false);
	}

	/**
	 * Returns the number of consecutive rejected or undecodable inbound messages
	 * allowed per connection before its circuit breaker closes it. An accepted
	 * merge resets the streak; zero disables the breaker.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getMaxConsecutiveRejects()}.
	 * @return consecutive-reject limit, or zero when disabled
	 */
	@Deprecated
	public long getMaxConsecutiveRejects() {
		return LatticePropagatorConfig.from(this).getMaxConsecutiveRejects();
	}

	/**
	 * Returns the maximum encoded inbound network message size. This limit is applied by
	 * Netty before allocating the complete message byte array.
	 *
	 * @return maximum encoded message size in bytes
	 */
	public int getMaxMessageSize() {
		return getMessageSize(MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE);
	}

	/**
	 * Returns the maximum encoded body size used for outbound lattice delta chunks.
	 * This is independent of the complete inbound value-size policy. It defaults
	 * to the conservative public frame limit advertised by this node.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getMaxDeltaMessageSize()}.
	 * @return maximum outbound delta chunk size in bytes
	 */
	@Deprecated
	public int getMaxDeltaMessageSize() {
		return LatticePropagatorConfig.from(this).getMaxDeltaMessageSize();
	}

	/**
	 * Returns the total encoded-byte budget for one eager lattice propagation.
	 * This bounds novelty references plus DATA message bodies independently of
	 * the size of the complete lattice value in the store.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getMaxDeltaBroadcastSize()}.
	 * @return maximum eager-delta working set in encoded bytes
	 */
	@Deprecated
	public int getMaxDeltaBroadcastSize() {
		return LatticePropagatorConfig.from(this).getMaxDeltaBroadcastSize();
	}

	/**
	 * Returns the encoded-message limit used after an outbound Peer's AccountKey has
	 * passed challenge/response verification. Unverified connections always remain
	 * subject to {@link #getMaxMessageSize()}.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getMaxTrustedMessageSize()}.
	 * @return trusted peer message limit in bytes
	 */
	@Deprecated
	public int getMaxTrustedMessageSize() {
		return LatticePropagatorConfig.from(this).getMaxTrustedMessageSize();
	}

	/**
	 * Returns the maximum number of simultaneous inbound network connections.
	 *
	 * @return inbound connection cap
	 */
	public int getMaxConnections() {
		return getPositiveInt(MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS);
	}

	/**
	 * Returns the maximum desired peers retained by each propagator.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getMaxDesiredPeers()}.
	 * @return desired-peer cap
	 */
	@Deprecated
	public int getMaxDesiredPeers() {
		return LatticePropagatorConfig.from(this).getMaxDesiredPeers();
	}

	/**
	 * Returns the capacity of each propagation endpoint's inbound processing queue.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getInboundQueueSize()}.
	 * @return inbound queue capacity
	 */
	@Deprecated
	public int getInboundQueueSize() {
		return LatticePropagatorConfig.from(this).getInboundQueueSize();
	}

	/**
	 * Returns the encoded-byte capacity of each propagation endpoint's inbound queue.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getMaxInboundQueueBytes()}.
	 * @return queue capacity in encoded bytes
	 */
	@Deprecated
	public int getMaxInboundQueueBytes() {
		return LatticePropagatorConfig.from(this).getMaxInboundQueueBytes();
	}

	/**
	 * Returns the time allowed for an ordered inbound dispatcher to drain during
	 * shutdown. A timeout does not abandon the dispatcher: shutdown remains
	 * incomplete and may be retried once the current operation finishes.
	 *
	 * @deprecated Use {@link LatticePropagatorConfig#getInboundShutdownTimeout()}.
	 * @return dispatcher shutdown timeout in milliseconds
	 */
	@Deprecated
	public long getInboundShutdownTimeout() {
		return LatticePropagatorConfig.from(this).getInboundShutdownTimeout();
	}

	/**
	 * Returns the maximum accepted lead over the local clock for lattice values
	 * that use wall-clock conflict ordering. Zero requires timestamps no later
	 * than the local clock.
	 *
	 * @return non-negative future timestamp allowance in milliseconds
	 */
	public long getMaxFutureTimestampSkew() {
		CVMLong v = RT.ensureLong(config.get(MAX_FUTURE_TIMESTAMP_SKEW));
		long value = (v != null) ? v.longValue() : DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW;
		if (value < 0) {
			throw new IllegalArgumentException(MAX_FUTURE_TIMESTAMP_SKEW + " must not be negative: " + value);
		}
		return value;
	}

	// ========== URL validation ==========

	/**
	 * Validates a public node URL for use by an advertising application.
	 *
	 * <p>This is a purely <em>local</em> check: it never resolves DNS or probes reachability.
	 * A node cannot verify its own external reachability, and resolving an operator- or
	 * attacker-supplied hostname would itself be a minor DNS/SSRF surface. It parses the URI
	 * and — for IP <em>literals</em> only — rejects addresses that cannot be publicly routable
	 * (loopback, RFC1918 private, link-local, IPv6 ULA, the unspecified address). A bare
	 * hostname other than {@code localhost} is accepted without resolution.</p>
	 *
	 * @param urlStr URL string to validate (as configured)
	 * @param allowPrivate if true, private/loopback/link-local literals are permitted (dev networks)
	 * @return null if the URL is acceptable for publication, else a human-readable rejection reason
	 */
	public static String validatePublicURL(String urlStr, boolean allowPrivate) {
		if (urlStr == null || urlStr.isBlank()) return "URL is empty";
		URI uri;
		try {
			uri = new URI(urlStr.trim());
		} catch (URISyntaxException e) {
			return "URL is malformed: " + e.getMessage();
		}
		if (uri.getScheme() == null) return "URL is missing a scheme (e.g. tcp://host:port): " + urlStr;
		String host = uri.getHost();
		if (host == null || host.isEmpty()) return "URL is missing a valid host: " + urlStr;
		if (uri.getPort() < 0) return "URL is missing a port: " + urlStr;
		if (!allowPrivate) {
			String reason = privateHostReason(host);
			if (reason != null) {
				return "URL host '" + host + "' is not publicly reachable (" + reason
					+ "); set allowPrivateURL true to override on a dev network";
			}
		}
		return null;
	}

	/**
	 * Returns a reason if {@code host} is a non-publicly-routable IP literal or {@code localhost},
	 * else null. Never resolves DNS — a bare hostname (not an IP literal) other than localhost
	 * returns null (treated as potentially public).
	 *
	 * @param host host component of a URI (IPv6 may be bracketed)
	 * @return rejection reason, or null if the host may be publicly routable
	 */
	static String privateHostReason(String host) {
		String h = host;
		if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1); // strip IPv6 brackets
		String lower = h.toLowerCase(Locale.ROOT);
		if (lower.equals("localhost") || lower.endsWith(".localhost")) return "localhost";

		int[] v4 = parseIPv4(h);
		if (v4 != null) return ipv4PrivateReason(v4);

		if (h.indexOf(':') >= 0) {
			// IPv6 literal: safe to parse without triggering DNS
			try {
				InetAddress addr = InetAddress.getByName(h);
				if (addr.isAnyLocalAddress()) return "unspecified address (::)";
				if (addr.isLoopbackAddress()) return "IPv6 loopback (::1)";
				if (addr.isLinkLocalAddress()) return "IPv6 link-local (fe80::/10)";
				if (addr.isSiteLocalAddress()) return "IPv6 site-local (fec0::/10)";
				byte[] b = addr.getAddress();
				if (b.length == 16 && (b[0] & 0xFE) == 0xFC) return "IPv6 unique-local (fc00::/7)";
			} catch (Exception e) {
				return "unparseable IPv6 literal";
			}
		}
		return null; // hostname or public IP literal
	}

	/**
	 * Parses a strict IPv4 dotted-decimal literal without any DNS lookup.
	 *
	 * @param h candidate host string
	 * @return the four octets (0-255), or null if {@code h} is not a valid IPv4 literal
	 */
	static int[] parseIPv4(String h) {
		String[] parts = h.split("\\.", -1);
		if (parts.length != 4) return null;
		int[] o = new int[4];
		for (int i = 0; i < 4; i++) {
			String p = parts[i];
			if (p.isEmpty() || p.length() > 3) return null;
			int val = 0;
			for (int j = 0; j < p.length(); j++) {
				char c = p.charAt(j);
				if (c < '0' || c > '9') return null;
				val = val * 10 + (c - '0');
			}
			if (val > 255) return null;
			o[i] = val;
		}
		return o;
	}

	/**
	 * Reason an IPv4 literal is not publicly routable, or null if it may be public.
	 *
	 * @param o four IPv4 octets
	 * @return rejection reason, or null
	 */
	static String ipv4PrivateReason(int[] o) {
		if (o[0] == 0) return "unspecified (0.0.0.0/8)";
		if (o[0] == 127) return "loopback (127.0.0.0/8)";
		if (o[0] == 10) return "private (10.0.0.0/8)";
		if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return "private (172.16.0.0/12)";
		if (o[0] == 192 && o[1] == 168) return "private (192.168.0.0/16)";
		if (o[0] == 169 && o[1] == 254) return "link-local (169.254.0.0/16)";
		return null;
	}

	// ========== Helpers ==========

	private boolean getBool(AString key, boolean defaultValue) {
		ACell v = config.get(key);
		if (v == null) return defaultValue;
		return RT.bool(v);
	}

	private int getPositiveInt(AString key, int defaultValue) {
		CVMLong v = RT.ensureLong(config.get(key));
		if (v == null) return defaultValue;
		long value = v.longValue();
		if (value <= 0 || value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(key + " must be between 1 and " + Integer.MAX_VALUE
					+ ": " + value);
		}
		return (int) value;
	}

	private int getMessageSize(AString key, int defaultValue) {
		int value = getPositiveInt(key, defaultValue);
		long protocolMax = convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH;
		if (value > protocolMax) {
			throw new IllegalArgumentException(key + " must not exceed the protocol maximum of "
				+ protocolMax + ": " + value);
		}
		return value;
	}
}
