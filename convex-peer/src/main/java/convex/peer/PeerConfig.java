package convex.peer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Typed configuration wrapper for Convex peer settings.
 *
 * <p>Loads configuration from a JSON5 file into an immutable {@link AMap},
 * following the same pattern as Covia's {@code Config} class. Provides typed
 * accessor methods with sensible defaults for peer-layer concerns.
 *
 * <p>For backward compatibility, {@link #toLegacy()} converts the config into
 * the existing {@code HashMap<Keyword, Object>} format used by
 * {@link Server} and {@link API#launchPeer(java.util.Map)}.
 *
 * <p>REST/MCP/OAuth configuration is handled by the
 * {@code convex.restapi.RESTConfig} subclass in the convex-restapi module.
 *
 * <h2>Config File Format (JSON5)</h2>
 * <pre>{@code
 * {
 *   "peer": { "port": 18888, "keypair": "abcdef...", ... },
 *   "auth": { "tokenExpiry": 86400, "publicAccess": true, ... }
 * }
 * }</pre>
 *
 * @see Config
 * @see API#launchPeer(java.util.Map)
 */
public class PeerConfig {

	// ========== Top-level section keys ==========

	public static final AString PEER = Strings.intern("peer");
	public static final AString AUTH = Strings.intern("auth");

	// ========== Peer config keys ==========

	public static final AString PORT = Strings.intern("port");
	public static final AString KEYPAIR = Strings.intern("keypair");
	public static final AString STORE = Strings.intern("store");
	public static final AString URL = Strings.intern("url");
	public static final AString RESTORE = Strings.intern("restore");
	public static final AString PERSIST = Strings.intern("persist");
	public static final AString AUTO_MANAGE = Strings.intern("autoManage");
	public static final AString OUTGOING_CONNECTIONS = Strings.intern("outgoingConnections");
	public static final AString SOURCE = Strings.intern("source");
	public static final AString TIMEOUT = Strings.intern("timeout");
	public static final AString POLL_DELAY = Strings.intern("pollDelay");

	// ========== Auth config keys ==========

	public static final AString TOKEN_EXPIRY = Strings.intern("tokenExpiry");
	public static final AString PUBLIC_ACCESS = Strings.intern("publicAccess");

	// ========== Instance ==========

	private final AMap<AString, ACell> config;

	protected PeerConfig(AMap<AString, ACell> config) {
		this.config = (config == null) ? Maps.empty() : config;
	}

	/**
	 * Create a PeerConfig wrapping the given config map.
	 * @param config Config map, or null for empty defaults
	 * @return New PeerConfig instance
	 */
	public static PeerConfig create(AMap<AString, ACell> config) {
		return new PeerConfig(config);
	}

	/**
	 * Parse a PeerConfig from a JSON5 string.
	 * @param json5 JSON5 configuration string
	 * @return New PeerConfig instance
	 */
	public static PeerConfig parse(String json5) {
		AMap<AString, ACell> map = JSON.parseJSON5(json5);
		return new PeerConfig(map);
	}

	/**
	 * Load a PeerConfig from a JSON5 file.
	 * @param path Path to JSON5 configuration file
	 * @return New PeerConfig instance
	 * @throws IOException if the file cannot be read
	 */
	public static PeerConfig load(String path) throws IOException {
		String content = Files.readString(Path.of(path));
		return parse(content);
	}

	/**
	 * Get the raw config map.
	 * @return Underlying config map (never null)
	 */
	public AMap<AString, ACell> getMap() {
		return config;
	}

	/**
	 * Get a config section as a map.
	 * @param key Section key (e.g. "peer", "auth")
	 * @return Section map, or empty map if not present
	 */
	public AMap<AString, ACell> getSection(AString key) {
		AMap<AString, ACell> section = RT.ensureMap(config.get(key));
		return (section != null) ? section : Maps.empty();
	}

	// ========== Peer typed accessors ==========

	/**
	 * Get the peer network port.
	 * @return Port number, or null for auto-select
	 */
	public Integer getPeerPort() {
		CVMLong v = RT.ensureLong(getSection(PEER).get(PORT));
		return (v != null) ? (int) v.longValue() : null;
	}

	/**
	 * Get the peer keypair seed as a hex string.
	 * @return Seed hex string, or null if not configured
	 */
	public String getKeypairSeed() {
		AString v = RT.ensureString(getSection(PEER).get(KEYPAIR));
		return (v != null) ? v.toString() : null;
	}

	/**
	 * Get the Etch store path.
	 * @return Store path string, or null if not configured (uses temp store)
	 */
	public String getStorePath() {
		AString v = RT.ensureString(getSection(PEER).get(STORE));
		return (v != null) ? v.toString() : null;
	}

	/**
	 * Get the peer public URL.
	 * @return URL string, or null if not configured
	 */
	public String getPeerUrl() {
		AString v = RT.ensureString(getSection(PEER).get(URL));
		return (v != null) ? v.toString() : null;
	}

	/**
	 * Whether to restore peer state from existing store.
	 * @return true if restore enabled (default: true)
	 */
	public boolean isRestore() {
		return getBool(getSection(PEER), RESTORE, true);
	}

	/**
	 * Whether to persist peer state on close.
	 * @return true if persist enabled (default: true)
	 */
	public boolean isPersist() {
		return getBool(getSection(PEER), PERSIST, true);
	}

	/**
	 * Whether to auto-manage the peer account.
	 * @return true if auto-manage enabled (default: true)
	 */
	public boolean isAutoManage() {
		return getBool(getSection(PEER), AUTO_MANAGE, true);
	}

	/**
	 * Get the maximum outgoing peer connections.
	 * @return Connection count, or null for default
	 */
	public Integer getOutgoingConnections() {
		CVMLong v = RT.ensureLong(getSection(PEER).get(OUTGOING_CONNECTIONS));
		return (v != null) ? (int) v.longValue() : null;
	}

	/**
	 * Get the remote peer sync source.
	 * @return Source address string, or null if not configured
	 */
	public String getSource() {
		AString v = RT.ensureString(getSection(PEER).get(SOURCE));
		return (v != null) ? v.toString() : null;
	}

	/**
	 * Get the peer sync timeout in milliseconds.
	 * @return Timeout in ms, or null for default
	 */
	public Long getTimeout() {
		CVMLong v = RT.ensureLong(getSection(PEER).get(TIMEOUT));
		return (v != null) ? v.longValue() : null;
	}

	// ========== Auth typed accessors ==========

	/**
	 * Get the JWT token expiry in seconds.
	 * @return Expiry in seconds (default: 86400 = 24 hours)
	 */
	public long getTokenExpiry() {
		CVMLong v = RT.ensureLong(getSection(AUTH).get(TOKEN_EXPIRY));
		return (v != null) ? v.longValue() : 86400L;
	}

	/**
	 * Whether public (unauthenticated) access is allowed.
	 * @return true if public access enabled (default: true)
	 */
	public boolean isPublicAccess() {
		return getBool(getSection(AUTH), PUBLIC_ACCESS, true);
	}

	// ========== Legacy bridge ==========

	/**
	 * Convert this config to the legacy {@code HashMap<Keyword, Object>} format
	 * used by {@link API#launchPeer(java.util.Map)} and {@link Server}.
	 *
	 * <p>Maps peer-section JSON5 config keys to their corresponding
	 * {@link Keywords} constants. Only sets keys that are explicitly configured;
	 * omitted keys retain their existing defaults in the legacy system.
	 *
	 * <p>Subclasses may override to add additional section mappings.
	 *
	 * @return Legacy config map suitable for {@code API.launchPeer()}
	 */
	public HashMap<Keyword, Object> toLegacy() {
		HashMap<Keyword, Object> legacy = new HashMap<>();

		// Peer section
		AMap<AString, ACell> peer = getSection(PEER);
		mapLong(peer, PORT, legacy, Keywords.PORT);
		mapString(peer, URL, legacy, Keywords.URL);
		mapString(peer, STORE, legacy, Keywords.STORE);
		mapString(peer, SOURCE, legacy, Keywords.SOURCE);
		mapBool(peer, RESTORE, legacy, Keywords.RESTORE);
		mapBool(peer, PERSIST, legacy, Keywords.PERSIST);
		mapBool(peer, AUTO_MANAGE, legacy, Keywords.AUTO_MANAGE);
		mapLong(peer, OUTGOING_CONNECTIONS, legacy, Keywords.OUTGOING_CONNECTIONS);
		mapLong(peer, TIMEOUT, legacy, Keywords.TIMEOUT);
		mapLong(peer, POLL_DELAY, legacy, Keywords.POLL_DELAY);

		// Keypair — convert seed hex to AKeyPair
		String seed = getKeypairSeed();
		if (seed != null) {
			Blob seedBlob = Blob.parse(seed);
			if (seedBlob != null && seedBlob.count() == AKeyPair.SEED_LENGTH) {
				legacy.put(Keywords.KEYPAIR, AKeyPair.create(seedBlob));
			}
		}

		return legacy;
	}

	// ========== Helpers ==========

	protected static boolean getBool(AMap<AString, ACell> section, AString key, boolean defaultValue) {
		ACell v = section.get(key);
		if (v == null) return defaultValue;
		return RT.bool(v);
	}

	protected static void mapLong(AMap<AString, ACell> source, AString sourceKey,
			HashMap<Keyword, Object> target, Keyword targetKey) {
		CVMLong v = RT.ensureLong(source.get(sourceKey));
		if (v != null) target.put(targetKey, (int) v.longValue());
	}

	protected static void mapString(AMap<AString, ACell> source, AString sourceKey,
			HashMap<Keyword, Object> target, Keyword targetKey) {
		AString v = RT.ensureString(source.get(sourceKey));
		if (v != null) target.put(targetKey, v.toString());
	}

	protected static void mapBool(AMap<AString, ACell> source, AString sourceKey,
			HashMap<Keyword, Object> target, Keyword targetKey) {
		ACell v = source.get(sourceKey);
		if (v != null) target.put(targetKey, RT.bool(v));
	}
}
