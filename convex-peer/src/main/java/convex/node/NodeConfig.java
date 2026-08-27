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
 * Typed configuration for a {@link NodeServer}.
 *
 * <p>Follows the same {@link AMap}{@code <AString, ACell>} pattern as
 * {@link convex.peer.PeerConfig}, providing typed accessors with sensible
 * defaults. The config map is immutable once created.
 *
 * <p>Key names reuse {@link convex.peer.PeerConfig} constants where they
 * overlap (port, persist, restore, store) so configurations are consistent
 * across peer and lattice node servers.
 */
public class NodeConfig {

	// ========== Config keys ==========

	/** Network port for incoming connections (Long). Null = auto-select. */
	public static final AString PORT = Strings.intern("port");

	/** Whether to persist state to store (Boolean, default true). */
	public static final AString PERSIST = Strings.intern("persist");

	/** Whether to restore state from store on startup (Boolean, default true). */
	public static final AString RESTORE = Strings.intern("restore");

	/** Interval between periodic persistence runs in ms (Long, default 30000). */
	public static final AString PERSIST_INTERVAL = Strings.intern("persistInterval");

	/** Advertised URL for this node (AString). If set, node advertises itself in
	 *  :p2p :nodes. Must be publicly reachable unless {@link #ALLOW_PRIVATE_URL} is
	 *  deliberately enabled for a development network. */
	public static final AString URL = Strings.intern("url");

	/** Maximum memory size (bytes) of an inbound LATTICE_VALUE accepted for merge (#564). */
	public static final AString MAX_INBOUND_VALUE_SIZE = Strings.intern("maxInboundValueSize");

	/** Whether to permit a private/loopback {@link #URL} for dev networks (Boolean, default false). #567 */
	public static final AString ALLOW_PRIVATE_URL = Strings.intern("allowPrivateURL");

	/** Consecutive rejected/undecodable inbound messages from a single connection before the
	 *  circuit-breaker closes it (Long, default 100; 0 disables). #566 */
	public static final AString MAX_CONSECUTIVE_REJECTS = Strings.intern("maxConsecutiveRejects");

	/** Maximum encoded inbound network message size in bytes (Long, default 4 MiB). */
	public static final AString MAX_MESSAGE_SIZE = Strings.intern("maxMessageSize");

	/** Maximum encoded outbound lattice delta chunk size in bytes. */
	public static final AString MAX_DELTA_MESSAGE_SIZE = Strings.intern("maxDeltaMessageSize");

	/** Maximum combined encoded bytes materialised for one outbound lattice delta. */
	public static final AString MAX_DELTA_BROADCAST_SIZE = Strings.intern("maxDeltaBroadcastSize");

	/** Maximum encoded message size from a cryptographically verified Peer. */
	public static final AString MAX_TRUSTED_MESSAGE_SIZE = Strings.intern("maxTrustedMessageSize");

	/** Maximum simultaneous inbound network connections (Long, default 256). */
	public static final AString MAX_CONNECTIONS = Strings.intern("maxConnections");

	/** Maximum desired peers retained from configuration and discovery. */
	public static final AString MAX_DESIRED_PEERS = Strings.intern("maxDesiredPeers");

	/** Capacity of the bounded inbound processing queue (Long, default 1024). */
	public static final AString INBOUND_QUEUE_SIZE = Strings.intern("inboundQueueSize");

	/** Maximum encoded bytes retained by the inbound processing queue. */
	public static final AString MAX_INBOUND_QUEUE_BYTES = Strings.intern("maxInboundQueueBytes");

	/** Time allowed for the ordered inbound dispatcher to drain during shutdown. */
	public static final AString INBOUND_SHUTDOWN_TIMEOUT = Strings.intern("inboundShutdownTimeout");

	/** Maximum accepted wall-clock lead for timestamp-ordered lattice values. */
	public static final AString MAX_FUTURE_TIMESTAMP_SKEW = Strings.intern("maxFutureTimestampSkew");

	/** Conservative public-node default: large lattice trees are transferred via DATA_REQUEST. */
	public static final int DEFAULT_MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

	/** Default eager delta working set: four public-size chunks, capped by protocol. */
	public static final int DEFAULT_MAX_DELTA_BROADCAST_SIZE = 16 * 1024 * 1024;

	/** Verified Peers may use the full protocol message allowance. */
	public static final int DEFAULT_MAX_TRUSTED_MESSAGE_SIZE =
		(int) convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH;

	/** Conservative public-node connection cap. */
	public static final int DEFAULT_MAX_CONNECTIONS = 256;

	/** Conservative cap on discovery-driven desired-peer state. */
	public static final int DEFAULT_MAX_DESIRED_PEERS = 256;

	/** Default number of decoded messages awaiting lattice processing. */
	public static final int DEFAULT_INBOUND_QUEUE_SIZE = 1024;

	/** Default encoded bytes awaiting lattice processing. */
	public static final int DEFAULT_MAX_INBOUND_QUEUE_BYTES = 16 * 1024 * 1024;

	/** Default time allowed for accepted inbound work to finish during shutdown. */
	public static final long DEFAULT_INBOUND_SHUTDOWN_TIMEOUT = 10_000L;

	/** Default wall-clock lead accepted from timestamp-ordered lattice values. */
	public static final long DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW = 30_000L;

	// ========== Instance ==========

	private final AMap<AString, ACell> config;

	private NodeConfig(AMap<AString, ACell> config) {
		this.config = (config == null) ? Maps.empty() : config;
	}

	/**
	 * Create a NodeConfig wrapping the given config map.
	 * @param config Config map, or null for defaults
	 * @return New NodeConfig instance
	 */
	public static NodeConfig create(AMap<AString, ACell> config) {
		return new NodeConfig(config);
	}

	/**
	 * Create a NodeConfig with all defaults.
	 * @return New NodeConfig instance with empty config
	 */
	public static NodeConfig create() {
		return new NodeConfig(null);
	}

	/**
	 * Create a NodeConfig with a specific port.
	 * @param port Port number for incoming connections
	 * @return New NodeConfig instance with port set
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
	 * Get the raw config map.
	 * @return Underlying config map (never null)
	 */
	public AMap<AString, ACell> getMap() {
		return config;
	}

	// ========== Typed accessors ==========

	/**
	 * Get the network port.
	 * @return Port number, or null for auto-select
	 */
	public Integer getPort() {
		CVMLong v = RT.ensureLong(config.get(PORT));
		return (v != null) ? (int) v.longValue() : null;
	}

	/**
	 * Whether to persist state to the store.
	 * @return true if persist enabled (default: true)
	 */
	public boolean isPersist() {
		return getBool(PERSIST, true);
	}

	/**
	 * Whether to restore state from the store on startup.
	 * @return true if restore enabled (default: true)
	 */
	public boolean isRestore() {
		return getBool(RESTORE, true);
	}

	/**
	 * Get the interval between periodic persistence runs.
	 * @return Interval in milliseconds (default: 30000)
	 */
	public long getPersistInterval() {
		CVMLong v = RT.ensureLong(config.get(PERSIST_INTERVAL));
		return (v != null) ? v.longValue() : 30_000L;
	}

	/**
	 * Get the advertised URL for this node.
	 * If set, the node will advertise itself in the {@code :p2p :nodes} lattice.
	 * @return advertised URL string, or null if not configured (private node)
	 */
	public AString getURL() {
		return RT.ensureString(config.get(URL));
	}

	/**
	 * Gets the transport URL to publish after the listener has bound. A configured
	 * port of zero is replaced with the actual OS-assigned listener port, preserving
	 * the remaining URI components.
	 *
	 * @param boundPort actual listener port, or null when no listener exists
	 * @return resolved advertised URL, or null when no URL is configured
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
	 * Maximum memory size (bytes) of an inbound LATTICE_VALUE this node will merge (#564).
	 * Larger values are rejected before the merge runs, bounding merge cost from untrusted
	 * peers. Defaults to this node's configured encoded-message cap, providing a
	 * consistent conservative limit for public nodes.
	 *
	 * @return maximum inbound value size in bytes
	 */
	public long getMaxInboundValueSize() {
		CVMLong v = RT.ensureLong(config.get(MAX_INBOUND_VALUE_SIZE));
		return (v != null) ? v.longValue() : getMaxMessageSize();
	}

	/**
	 * Whether a private, loopback or link-local {@link #URL} is permitted (#567).
	 * Defaults to false, so a misconfigured private URL fails at launch rather than
	 * polluting the {@code [:p2p :nodes]} registry with an unreachable address. Set
	 * true only on dev networks where private addressing is intentional.
	 *
	 * @return true if private URLs are permitted
	 */
	public boolean isAllowPrivateURL() {
		return getBool(ALLOW_PRIVATE_URL, false);
	}

	/**
	 * Consecutive rejected or undecodable inbound messages from a single connection before
	 * the circuit-breaker closes it (#566). A single accepted merge resets the streak.
	 * Defaults to 100 — generous enough not to bite a trusted peer, tight enough to cut off
	 * a peer streaming malformed values indefinitely. Set to 0 to disable the breaker.
	 *
	 * @return consecutive-reject limit, or 0 if the circuit-breaker is disabled
	 */
	public long getMaxConsecutiveRejects() {
		CVMLong v = RT.ensureLong(config.get(MAX_CONSECUTIVE_REJECTS));
		return (v != null) ? v.longValue() : 100L;
	}

	/**
	 * Gets the maximum encoded inbound network message size. This limit is applied by
	 * Netty before allocating the complete message byte array.
	 *
	 * @return maximum encoded message size in bytes
	 */
	public int getMaxMessageSize() {
		return getMessageSize(MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_SIZE);
	}

	/**
	 * Gets the maximum encoded body size used for outbound lattice delta chunks.
	 * This is independent of the complete inbound value-size policy. It defaults
	 * to the conservative public frame limit advertised by this node.
	 *
	 * @return maximum outbound delta chunk size in bytes
	 */
	public int getMaxDeltaMessageSize() {
		return getMessageSize(MAX_DELTA_MESSAGE_SIZE, getMaxMessageSize());
	}

	/**
	 * Gets the total encoded-byte budget for one eager lattice propagation.
	 * This bounds novelty references plus DATA message bodies independently of
	 * the size of the complete lattice value in the store.
	 */
	public int getMaxDeltaBroadcastSize() {
		int messageLimit=getMaxDeltaMessageSize();
		int defaultValue=Math.max(messageLimit,DEFAULT_MAX_DELTA_BROADCAST_SIZE);
		defaultValue=(int)Math.min(defaultValue,convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH);
		int value=getMessageSize(MAX_DELTA_BROADCAST_SIZE,defaultValue);
		if (value<messageLimit) {
			throw new IllegalArgumentException(MAX_DELTA_BROADCAST_SIZE
				+" must be at least "+MAX_DELTA_MESSAGE_SIZE+": "+value+" < "+messageLimit);
		}
		return value;
	}

	/**
	 * Gets the encoded-message limit used after an outbound Peer's AccountKey has
	 * passed challenge/response verification. Unverified connections always remain
	 * subject to {@link #getMaxMessageSize()}.
	 *
	 * @return trusted peer message limit in bytes
	 */
	public int getMaxTrustedMessageSize() {
		return getMessageSize(MAX_TRUSTED_MESSAGE_SIZE, DEFAULT_MAX_TRUSTED_MESSAGE_SIZE);
	}

	/**
	 * Gets the maximum number of simultaneous inbound network connections.
	 *
	 * @return inbound connection cap
	 */
	public int getMaxConnections() {
		return getPositiveInt(MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS);
	}

	/** Gets the maximum number of desired peers retained by each propagator. */
	public int getMaxDesiredPeers() {
		return getPositiveInt(MAX_DESIRED_PEERS, DEFAULT_MAX_DESIRED_PEERS);
	}

	/**
	 * Gets the capacity of the bounded inbound processing queue.
	 *
	 * @return inbound queue capacity
	 */
	public int getInboundQueueSize() {
		return getPositiveInt(INBOUND_QUEUE_SIZE, DEFAULT_INBOUND_QUEUE_SIZE);
	}

	/** Gets the encoded-byte capacity of the inbound dispatcher queue. */
	public int getMaxInboundQueueBytes() {
		int messageLimit=getMaxMessageSize();
		int defaultValue=Math.max(messageLimit,DEFAULT_MAX_INBOUND_QUEUE_BYTES);
		defaultValue=(int)Math.min(defaultValue,convex.core.cpos.CPoSConstants.MAX_MESSAGE_LENGTH);
		int value=getMessageSize(MAX_INBOUND_QUEUE_BYTES,defaultValue);
		if (value<messageLimit) {
			throw new IllegalArgumentException(MAX_INBOUND_QUEUE_BYTES
				+" must be at least "+MAX_MESSAGE_SIZE+": "+value+" < "+messageLimit);
		}
		return value;
	}

	/**
	 * Gets the time allowed for the ordered inbound dispatcher to drain during
	 * shutdown. A timeout does not abandon the dispatcher: shutdown remains
	 * incomplete and may be retried once the current operation finishes.
	 *
	 * @return dispatcher shutdown timeout in milliseconds
	 */
	public long getInboundShutdownTimeout() {
		CVMLong v = RT.ensureLong(config.get(INBOUND_SHUTDOWN_TIMEOUT));
		long value = (v != null) ? v.longValue() : DEFAULT_INBOUND_SHUTDOWN_TIMEOUT;
		if (value <= 0) {
			throw new IllegalArgumentException(INBOUND_SHUTDOWN_TIMEOUT + " must be positive: " + value);
		}
		return value;
	}

	/**
	 * Gets the maximum accepted lead over this node's clock for lattice values
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

	// ========== URL validation (#567) ==========

	/**
	 * Validates a public node URL for advertisement in the {@code [:p2p :nodes]} lattice (#567).
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
