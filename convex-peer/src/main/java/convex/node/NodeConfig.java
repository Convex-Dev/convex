package convex.node;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
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

	/** Public URL for this node (AString). If set, node advertises itself in :p2p :nodes.
	 *  Must be publicly reachable on the internet — never localhost or private addresses. */
	public static final AString URL = Strings.intern("url");

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
	 * Get the public URL for this node.
	 * If set, the node will advertise itself in the {@code :p2p :nodes} lattice.
	 * @return Public URL string, or null if not configured (private node)
	 */
	public AString getURL() {
		return RT.ensureString(config.get(URL));
	}

	// ========== Helpers ==========

	private boolean getBool(AString key, boolean defaultValue) {
		ACell v = config.get(key);
		if (v == null) return defaultValue;
		return RT.bool(v);
	}
}
