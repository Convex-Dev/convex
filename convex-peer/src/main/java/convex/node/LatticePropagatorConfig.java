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
 * propagator. {@link NodeServer} neither consumes nor copies it. Authoritative
 * persistence and standard {@link LatticeListener} settings belong to
 * {@link NodeConfig}, which the application supplies to those components
 * independently.</p>
 */
public final class LatticePropagatorConfig {

	/** @deprecated Ignored. Configure encoded frame limits with {@link #MAX_MESSAGE_SIZE} and {@link #MAX_TRUSTED_MESSAGE_SIZE}. */
	@Deprecated
	public static final AString MAX_INBOUND_VALUE_SIZE=Strings.intern("maxInboundValueSize");

	/**
	 * Consecutive rejected messages before this group's circuit breaker closes a
	 * route. Only messages that show the sender at fault count: malformed,
	 * unmergeable, refused by the application handler, DATA over an unverified
	 * route, or a value whose missing data the sender answers for wrongly. A :LOAD
	 * refusal from either side, an unanswered data request and a local failure
	 * count neither way. An accepted merge, DATA or application message resets the
	 * count.
	 */
	public static final AString MAX_CONSECUTIVE_REJECTS=Strings.intern("maxConsecutiveRejects");

	/** Maximum encoded message size on an unverified manager-owned route. */
	public static final AString MAX_MESSAGE_SIZE=Strings.intern("maxMessageSize");

	/** Maximum encoded body size for one outbound delta chunk. */
	public static final AString MAX_DELTA_MESSAGE_SIZE=Strings.intern("maxDeltaMessageSize");

	/** Maximum combined encoded bytes materialised for one outbound delta. */
	public static final AString MAX_DELTA_BROADCAST_SIZE=Strings.intern("maxDeltaBroadcastSize");

	/** Maximum encoded message size on a cryptographically verified route. */
	public static final AString MAX_TRUSTED_MESSAGE_SIZE=Strings.intern("maxTrustedMessageSize");

	/**
	 * Connections this group expects to keep statistics for; it sweeps the
	 * statistics of closed connections beyond this. Caps nothing: the listener caps
	 * sockets with the node's {@code maxConnections}.
	 */
	public static final AString MAX_CONNECTIONS=Strings.intern("maxConnections");

	/** Maximum desired peers retained by this group. */
	public static final AString MAX_DESIRED_PEERS=Strings.intern("maxDesiredPeers");

	/** Soft target for healthy background peer relationships. */
	public static final AString AMBIENT_PEERS=Strings.intern("ambientPeers");

	/** Additional recently used peers to retain, excluding explicit connections. */
	public static final AString ACTIVE_PEERS=Strings.intern("activePeers");

	/**
	 * Maximum number of decoded messages awaiting ordered processing. Also bounds
	 * concurrent missing-data acquisitions.
	 */
	public static final AString INBOUND_QUEUE_SIZE=Strings.intern("inboundQueueSize");

	/**
	 * DATA_REQUESTs served at once over the routes this group opened. One route may
	 * hold a quarter of them, and at least one; a request beyond either limit pauses
	 * its route until a permit frees, and is answered with :LOAD only if none frees
	 * within the internal timeout.
	 */
	public static final AString MAX_DATA_REQUESTS=Strings.intern("maxDataRequests");

	/** Maximum encoded bytes retained by this group's inbound queue. */
	public static final AString MAX_INBOUND_QUEUE_BYTES=Strings.intern("maxInboundQueueBytes");

	/**
	 * Time allowed for each of this group's workers, ingress and publication, to drain
	 * during shutdown. Publication's allowance includes writing its final broadcasts
	 * to each route before the routes close.
	 */
	public static final AString INBOUND_SHUTDOWN_TIMEOUT=Strings.intern("inboundShutdownTimeout");

	/**
	 * Milliseconds between this group's root announcements. The group sends its
	 * current root to every route at this interval, changed or not, so a peer that
	 * missed a delta fetches what it lacks, and a quiet route stays open through NATs
	 * and load balancers.
	 *
	 * <p>Zero turns periodic announcements off. A peer that missed a delta, or
	 * connected after the last change, then receives the group's state only with its
	 * next change, unless it pulls; quiet routes stay open only through the peers'
	 * own announcements.</p>
	 */
	public static final AString ROOT_ANNOUNCEMENT_INTERVAL=Strings.intern("rootAnnouncementInterval");

	/**
	 * Milliseconds a live route may hear nothing before this group probes it. Any
	 * traffic counts, including the root announcement each end sends every
	 * {@link #ROOT_ANNOUNCEMENT_INTERVAL}, so a healthy route is never probed. The
	 * default is two of this group's announcement intervals, or
	 * {@link #DEFAULT_KEEP_ALIVE} when the group does not announce. Keep it above the
	 * interval the group's peers announce at, with a margin, or probes race their
	 * announcements. The root announcement, not the probe, keeps NATs and load
	 * balancers from dropping a quiet route. Nodes without a connection are never
	 * probed.
	 */
	public static final AString KEEP_ALIVE=Strings.intern("keepAlive");

	/** Conservative public-route message limit. */
	public static final int DEFAULT_MAX_MESSAGE_SIZE=4*1024*1024;

	/** Default eager-delta working set. */
	public static final int DEFAULT_MAX_DELTA_BROADCAST_SIZE=16*1024*1024;

	/** Verified routes may use the full protocol message allowance. */
	public static final int DEFAULT_MAX_TRUSTED_MESSAGE_SIZE=(int)CPoSConstants.MAX_MESSAGE_LENGTH;

	/** Conservative cap on configured and discovery-supplied peer intent. */
	public static final int DEFAULT_MAX_DESIRED_PEERS=256;
	public static final int DEFAULT_AMBIENT_PEERS=16;
	public static final int DEFAULT_ACTIVE_PEERS=16;

	/** Default connections one group expects to keep statistics for. */
	public static final int DEFAULT_MAX_CONNECTIONS=256;

	/** Default number of decoded messages awaiting ordered processing. */
	public static final int DEFAULT_INBOUND_QUEUE_SIZE=1024;

	/** Default encoded bytes awaiting ordered processing. */
	public static final int DEFAULT_MAX_INBOUND_QUEUE_BYTES=16*1024*1024;

	/** Default DATA_REQUESTs served at once over the group's own routes. */
	public static final int DEFAULT_MAX_DATA_REQUESTS=16;

	/** Default time allowed for accepted endpoint work to drain. */
	public static final long DEFAULT_INBOUND_SHUTDOWN_TIMEOUT=10_000L;

	/** Default time between root announcements. */
	public static final long DEFAULT_ROOT_ANNOUNCEMENT_INTERVAL=30_000L;

	/**
	 * Default quiet time before a live route is probed with the default announcement
	 * interval: two intervals, so a probe follows a missed announcement.
	 */
	public static final long DEFAULT_KEEP_ALIVE=2*DEFAULT_ROOT_ANNOUNCEMENT_INTERVAL;

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

	/** @deprecated CAD036 limits encoded messages. This legacy accessor returns {@link Long#MAX_VALUE}. */
	@Deprecated
	public long getMaxInboundValueSize() {
		return Long.MAX_VALUE;
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

	/** Returns the connections this group keeps statistics for before sweeping closed ones. */
	public int getMaxConnections() {
		return getPositiveInt(MAX_CONNECTIONS,DEFAULT_MAX_CONNECTIONS);
	}

	/** Returns the desired-peer cap for this group. */
	public int getMaxDesiredPeers() {
		return getPositiveInt(MAX_DESIRED_PEERS,DEFAULT_MAX_DESIRED_PEERS);
	}

	/** Returns the soft ambient-peer target (zero disables ambient dialling). */
	public int getAmbientPeers() {
		return getNonNegativeInt(AMBIENT_PEERS,DEFAULT_AMBIENT_PEERS);
	}

	/** Returns the soft additional active-peer target. */
	public int getActivePeers() {
		return getNonNegativeInt(ACTIVE_PEERS,DEFAULT_ACTIVE_PEERS);
	}

	private int getNonNegativeInt(AString key,int defaultValue) {
		CVMLong configured=RT.ensureLong(config.get(key));
		if (configured==null) return defaultValue;
		long value=configured.longValue();
		if (value<0 || value>Integer.MAX_VALUE) {
			throw new IllegalArgumentException(key+" must be between 0 and "+Integer.MAX_VALUE+": "+value);
		}
		return (int)value;
	}

	/** Returns the decoded-message capacity of this group's inbound queue. */
	public int getInboundQueueSize() {
		return getPositiveInt(INBOUND_QUEUE_SIZE,DEFAULT_INBOUND_QUEUE_SIZE);
	}

	/** Returns how many DATA_REQUESTs the group serves at once over the routes it opened. */
	public int getMaxDataRequests() {
		return getPositiveInt(MAX_DATA_REQUESTS,DEFAULT_MAX_DATA_REQUESTS);
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

	/** Returns the per-worker shutdown drain timeout in milliseconds. */
	public long getInboundShutdownTimeout() {
		CVMLong configured=RT.ensureLong(config.get(INBOUND_SHUTDOWN_TIMEOUT));
		long value=(configured==null) ? DEFAULT_INBOUND_SHUTDOWN_TIMEOUT : configured.longValue();
		if (value<=0) {
			throw new IllegalArgumentException(INBOUND_SHUTDOWN_TIMEOUT+" must be positive: "+value);
		}
		return value;
	}

	/**
	 * Returns the time in milliseconds between this group's root announcements, or
	 * zero when it never announces on a timer.
	 */
	public long getRootAnnouncementInterval() {
		CVMLong configured=RT.ensureLong(config.get(ROOT_ANNOUNCEMENT_INTERVAL));
		long value=(configured==null) ? DEFAULT_ROOT_ANNOUNCEMENT_INTERVAL : configured.longValue();
		if (value<0) throw new IllegalArgumentException(ROOT_ANNOUNCEMENT_INTERVAL+" must not be negative: "+value);
		return value;
	}

	/**
	 * Returns the quiet time in milliseconds before a live route is probed. Unless
	 * configured, it is two of this group's root announcement intervals, or
	 * {@link #DEFAULT_KEEP_ALIVE} when the group does not announce.
	 */
	public long getKeepAlive() {
		CVMLong configured=RT.ensureLong(config.get(KEEP_ALIVE));
		long value;
		if (configured==null) {
			long interval=getRootAnnouncementInterval();
			if (interval==0) value=DEFAULT_KEEP_ALIVE;
			else value=(interval>Long.MAX_VALUE/2) ? Long.MAX_VALUE : 2*interval;
		} else {
			value=configured.longValue();
		}
		if (value<=0) throw new IllegalArgumentException(KEEP_ALIVE+" must be positive: "+value);
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
