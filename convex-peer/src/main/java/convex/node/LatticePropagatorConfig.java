package convex.node;

import convex.core.cpos.CPoSConstants;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Immutable transport, queue and publication limits for one
 * {@link LatticePropagator}.
 *
 * <p>The calling application supplies this configuration directly to the
 * propagator. {@link NodeServer} neither consumes nor copies it. Host listener
 * and authoritative persistence settings belong to {@link NodeConfig}.</p>
 */
public final class LatticePropagatorConfig {

	/** Maximum memory size of a complete value admitted by this group. */
	public static final AString MAX_INBOUND_VALUE_SIZE=Strings.intern("maxInboundValueSize");

	/** Consecutive rejected messages before this group's circuit breaker closes a route. */
	public static final AString MAX_CONSECUTIVE_REJECTS=Strings.intern("maxConsecutiveRejects");

	/** Maximum encoded message size on an unverified manager-owned route. */
	public static final AString MAX_MESSAGE_SIZE=Strings.intern("maxMessageSize");

	/** Maximum encoded body size for one outbound delta chunk. */
	public static final AString MAX_DELTA_MESSAGE_SIZE=Strings.intern("maxDeltaMessageSize");

	/** Maximum combined encoded bytes materialised for one outbound delta. */
	public static final AString MAX_DELTA_BROADCAST_SIZE=Strings.intern("maxDeltaBroadcastSize");

	/** Maximum encoded message size on a cryptographically verified route. */
	public static final AString MAX_TRUSTED_MESSAGE_SIZE=Strings.intern("maxTrustedMessageSize");

	/** Maximum physical inbound connections tracked by this group. */
	public static final AString MAX_CONNECTIONS=Strings.intern("maxConnections");

	/** Maximum desired peers retained by this group. */
	public static final AString MAX_DESIRED_PEERS=Strings.intern("maxDesiredPeers");

	/** Maximum number of decoded messages awaiting ordered processing. */
	public static final AString INBOUND_QUEUE_SIZE=Strings.intern("inboundQueueSize");

	/** Maximum encoded bytes retained by this group's inbound queue. */
	public static final AString MAX_INBOUND_QUEUE_BYTES=Strings.intern("maxInboundQueueBytes");

	/** Time allowed for this group's endpoint to drain during shutdown. */
	public static final AString INBOUND_SHUTDOWN_TIMEOUT=Strings.intern("inboundShutdownTimeout");

	/** Conservative public-route message limit. */
	public static final int DEFAULT_MAX_MESSAGE_SIZE=4*1024*1024;

	/** Default eager-delta working set. */
	public static final int DEFAULT_MAX_DELTA_BROADCAST_SIZE=16*1024*1024;

	/** Verified routes may use the full protocol message allowance. */
	public static final int DEFAULT_MAX_TRUSTED_MESSAGE_SIZE=(int)CPoSConstants.MAX_MESSAGE_LENGTH;

	/** Conservative cap on configured and discovery-supplied peer intent. */
	public static final int DEFAULT_MAX_DESIRED_PEERS=256;

	/** Conservative cap on physical inbound connections tracked by one group. */
	public static final int DEFAULT_MAX_CONNECTIONS=256;

	/** Default number of decoded messages awaiting ordered processing. */
	public static final int DEFAULT_INBOUND_QUEUE_SIZE=1024;

	/** Default encoded bytes awaiting ordered processing. */
	public static final int DEFAULT_MAX_INBOUND_QUEUE_BYTES=16*1024*1024;

	/** Default time allowed for accepted endpoint work to drain. */
	public static final long DEFAULT_INBOUND_SHUTDOWN_TIMEOUT=10_000L;

	private final AMap<AString,ACell> config;

	private LatticePropagatorConfig(AMap<AString,ACell> config) {
		this.config=(config==null) ? Maps.empty() : config;
	}

	/**
	 * Creates a group configuration wrapping an immutable map.
	 *
	 * @param config configuration map, or {@code null} for defaults
	 * @return propagation-group configuration
	 */
	public static LatticePropagatorConfig create(AMap<AString,ACell> config) {
		return new LatticePropagatorConfig(config);
	}

	/**
	 * Creates a group configuration containing only defaults.
	 *
	 * @return default propagation-group configuration
	 */
	public static LatticePropagatorConfig create() {
		return new LatticePropagatorConfig(null);
	}

	/**
	 * Creates a migration adapter from a combined legacy node configuration.
	 * The resulting object is independent and must still be supplied explicitly
	 * to the propagator by the calling application.
	 *
	 * @param config legacy combined configuration, or {@code null} for defaults
	 * @return propagation-group view of the same immutable map
	 */
	public static LatticePropagatorConfig from(NodeConfig config) {
		return create((config==null) ? null : config.getMap());
	}

	/** Returns the underlying immutable configuration map. */
	public AMap<AString,ACell> getMap() {
		return config;
	}

	/** Returns the maximum complete inbound value size. */
	public long getMaxInboundValueSize() {
		CVMLong value=RT.ensureLong(config.get(MAX_INBOUND_VALUE_SIZE));
		return (value==null) ? getMaxMessageSize() : value.longValue();
	}

	/** Returns the per-connection consecutive-reject limit, or zero when disabled. */
	public long getMaxConsecutiveRejects() {
		CVMLong value=RT.ensureLong(config.get(MAX_CONSECUTIVE_REJECTS));
		return (value==null) ? 100L : value.longValue();
	}

	/** Returns the encoded-message limit before route verification. */
	public int getMaxMessageSize() {
		return getMessageSize(MAX_MESSAGE_SIZE,DEFAULT_MAX_MESSAGE_SIZE);
	}

	/** Returns the maximum encoded body size for one outbound delta chunk. */
	public int getMaxDeltaMessageSize() {
		return getMessageSize(MAX_DELTA_MESSAGE_SIZE,getMaxMessageSize());
	}

	/** Returns the total encoded-byte budget for one eager delta. */
	public int getMaxDeltaBroadcastSize() {
		int messageLimit=getMaxDeltaMessageSize();
		int defaultValue=Math.max(messageLimit,DEFAULT_MAX_DELTA_BROADCAST_SIZE);
		defaultValue=(int)Math.min(defaultValue,CPoSConstants.MAX_MESSAGE_LENGTH);
		int value=getMessageSize(MAX_DELTA_BROADCAST_SIZE,defaultValue);
		if (value<messageLimit) {
			throw new IllegalArgumentException(MAX_DELTA_BROADCAST_SIZE
				+" must be at least "+MAX_DELTA_MESSAGE_SIZE+": "+value+" < "+messageLimit);
		}
		return value;
	}

	/** Returns the encoded-message limit after route verification. */
	public int getMaxTrustedMessageSize() {
		return getMessageSize(MAX_TRUSTED_MESSAGE_SIZE,DEFAULT_MAX_TRUSTED_MESSAGE_SIZE);
	}

	/** Returns the physical inbound connection cap for this group. */
	public int getMaxConnections() {
		return getPositiveInt(MAX_CONNECTIONS,DEFAULT_MAX_CONNECTIONS);
	}

	/** Returns the desired-peer cap for this group. */
	public int getMaxDesiredPeers() {
		return getPositiveInt(MAX_DESIRED_PEERS,DEFAULT_MAX_DESIRED_PEERS);
	}

	/** Returns the decoded-message capacity of this group's inbound queue. */
	public int getInboundQueueSize() {
		return getPositiveInt(INBOUND_QUEUE_SIZE,DEFAULT_INBOUND_QUEUE_SIZE);
	}

	/** Returns the encoded-byte capacity of this group's inbound queue. */
	public int getMaxInboundQueueBytes() {
		int messageLimit=getMaxMessageSize();
		int defaultValue=Math.max(messageLimit,DEFAULT_MAX_INBOUND_QUEUE_BYTES);
		defaultValue=(int)Math.min(defaultValue,CPoSConstants.MAX_MESSAGE_LENGTH);
		int value=getMessageSize(MAX_INBOUND_QUEUE_BYTES,defaultValue);
		if (value<messageLimit) {
			throw new IllegalArgumentException(MAX_INBOUND_QUEUE_BYTES
				+" must be at least "+MAX_MESSAGE_SIZE+": "+value+" < "+messageLimit);
		}
		return value;
	}

	/** Returns the endpoint shutdown timeout in milliseconds. */
	public long getInboundShutdownTimeout() {
		CVMLong configured=RT.ensureLong(config.get(INBOUND_SHUTDOWN_TIMEOUT));
		long value=(configured==null) ? DEFAULT_INBOUND_SHUTDOWN_TIMEOUT : configured.longValue();
		if (value<=0) {
			throw new IllegalArgumentException(INBOUND_SHUTDOWN_TIMEOUT+" must be positive: "+value);
		}
		return value;
	}

	private int getPositiveInt(AString key,int defaultValue) {
		CVMLong configured=RT.ensureLong(config.get(key));
		if (configured==null) return defaultValue;
		long value=configured.longValue();
		if (value<=0 || value>Integer.MAX_VALUE) {
			throw new IllegalArgumentException(key+" must be between 1 and "
				+Integer.MAX_VALUE+": "+value);
		}
		return (int)value;
	}

	private int getMessageSize(AString key,int defaultValue) {
		int value=getPositiveInt(key,defaultValue);
		if (value>CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException(key+" must not exceed the protocol maximum of "
				+CPoSConstants.MAX_MESSAGE_LENGTH+": "+value);
		}
		return value;
	}
}
