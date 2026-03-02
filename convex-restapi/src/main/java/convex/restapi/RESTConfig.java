package convex.restapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.PeerConfig;

/**
 * Typed configuration wrapper for Convex REST API, MCP, and OAuth settings.
 *
 * <p>Extends {@link PeerConfig} with REST-layer configuration sections:
 * {@code rest}, {@code mcp}, and OAuth settings within {@code auth}.
 * A standalone peer without a REST API only needs {@link PeerConfig};
 * this subclass adds the additional configuration needed when serving
 * HTTP/MCP endpoints.
 *
 * <h2>Config File Format (JSON5)</h2>
 * <pre>{@code
 * {
 *   "peer": { "port": 18888, "keypair": "abcdef...", ... },
 *   "rest": { "port": 8080, "faucet": true, ... },
 *   "mcp":  { "enabled": true, "signing": true, ... },
 *   "auth": { "tokenExpiry": 86400, "oauth": { ... }, ... }
 * }
 * }</pre>
 *
 * @see PeerConfig
 */
public class RESTConfig extends PeerConfig {

	// ========== Top-level section keys ==========

	public static final AString REST = Strings.intern("rest");
	public static final AString MCP = Strings.intern("mcp");

	// ========== REST config keys ==========

	public static final AString BASE_URL = Strings.intern("baseUrl");
	public static final AString FAUCET = Strings.intern("faucet");
	public static final AString CORS = Strings.intern("cors");

	// ========== MCP config keys ==========

	public static final AString ENABLED = Strings.intern("enabled");
	public static final AString SIGNING = Strings.intern("signing");
	public static final AString ELEVATED = Strings.intern("elevated");
	public static final AString TOOLS = Strings.intern("tools");

	// ========== OAuth config keys ==========

	public static final AString OAUTH = Strings.intern("oauth");
	private static final AString CLIENT_ID = Strings.intern("clientId");
	private static final AString CLIENT_SECRET = Strings.intern("clientSecret");

	// ========== Construction ==========

	protected RESTConfig(AMap<AString, ACell> config) {
		super(config);
	}

	/**
	 * Create a RESTConfig wrapping the given config map.
	 * @param config Config map, or null for empty defaults
	 * @return New RESTConfig instance
	 */
	public static RESTConfig create(AMap<AString, ACell> config) {
		return new RESTConfig(config);
	}

	/**
	 * Parse a RESTConfig from a JSON5 string.
	 * @param json5 JSON5 configuration string
	 * @return New RESTConfig instance
	 */
	public static RESTConfig parse(String json5) {
		AMap<AString, ACell> map = JSON.parseJSON5(json5);
		return new RESTConfig(map);
	}

	/**
	 * Load a RESTConfig from a JSON5 file.
	 * @param path Path to JSON5 configuration file
	 * @return New RESTConfig instance
	 * @throws IOException if the file cannot be read
	 */
	public static RESTConfig load(String path) throws IOException {
		String content = Files.readString(Path.of(path));
		return parse(content);
	}

	// ========== REST typed accessors ==========

	/**
	 * Get the REST API port.
	 * @return Port number, or null for default (8080)
	 */
	public Integer getRestPort() {
		CVMLong v = RT.ensureLong(getSection(REST).get(PeerConfig.PORT));
		return (v != null) ? (int) v.longValue() : null;
	}

	/**
	 * Get the external base URL.
	 * @return Base URL string, or null if not configured
	 */
	public String getBaseUrl() {
		AString v = RT.ensureString(getSection(REST).get(BASE_URL));
		return (v != null) ? v.toString() : null;
	}

	/**
	 * Whether the faucet endpoint is enabled.
	 * @return true if faucet enabled (default: false)
	 */
	public boolean isFaucetEnabled() {
		return getBool(getSection(REST), FAUCET, false);
	}

	// ========== MCP typed accessors ==========

	/**
	 * Whether MCP is enabled.
	 * @return true if MCP enabled (default: true)
	 */
	public boolean isMcpEnabled() {
		return getBool(getSection(MCP), ENABLED, true);
	}

	/**
	 * Whether the signing service is enabled via MCP.
	 * @return true if signing enabled (default: false)
	 */
	public boolean isSigningEnabled() {
		return getBool(getSection(MCP), SIGNING, false);
	}

	/**
	 * Whether elevated signing operations (import/export/delete) are enabled.
	 * @return true if elevated ops enabled (default: true when signing is enabled)
	 */
	public boolean isElevatedEnabled() {
		return getBool(getSection(MCP), ELEVATED, isSigningEnabled());
	}

	/**
	 * Get the MCP tools configuration section.
	 * @return Tools config map, or empty map if not configured
	 */
	public AMap<AString, ACell> getToolsConfig() {
		AMap<AString, ACell> mcpSection = getSection(MCP);
		AMap<AString, ACell> tools = RT.ensureMap(mcpSection.get(TOOLS));
		return (tools != null) ? tools : Maps.empty();
	}

	// ========== OAuth typed accessors ==========

	/**
	 * Get OAuth provider configuration for a specific provider.
	 * @param provider Provider name (e.g. "google", "github")
	 * @return Provider config map with clientId/clientSecret, or null if not configured
	 */
	public AMap<AString, ACell> getOAuthProvider(String provider) {
		AMap<AString, ACell> authSection = getSection(AUTH);
		ACell oauthCell = authSection.get(OAUTH);
		if (oauthCell == null) return null;
		AMap<AString, ACell> oauth = RT.ensureMap(oauthCell);
		if (oauth == null) return null;
		ACell providerCell = oauth.get(Strings.create(provider));
		if (providerCell == null) return null;
		return RT.ensureMap(providerCell);
	}

	/**
	 * Get OAuth client ID for a provider.
	 * @param provider Provider name (e.g. "google", "github")
	 * @return Client ID string, or null if not configured
	 */
	public String getOAuthClientId(String provider) {
		AMap<AString, ACell> pc = getOAuthProvider(provider);
		if (pc == null) return null;
		AString v = RT.ensureString(pc.get(CLIENT_ID));
		return (v != null) ? v.toString() : null;
	}

	/**
	 * Get OAuth client secret for a provider.
	 * @param provider Provider name (e.g. "google", "github")
	 * @return Client secret string, or null if not configured
	 */
	public String getOAuthClientSecret(String provider) {
		AMap<AString, ACell> pc = getOAuthProvider(provider);
		if (pc == null) return null;
		AString v = RT.ensureString(pc.get(CLIENT_SECRET));
		return (v != null) ? v.toString() : null;
	}

	// ========== Legacy bridge ==========

	/**
	 * Convert this config to the legacy {@code HashMap<Keyword, Object>} format,
	 * including both peer and REST section keys.
	 *
	 * @return Legacy config map suitable for {@code API.launchPeer()}
	 */
	@Override
	public HashMap<Keyword, Object> toLegacy() {
		HashMap<Keyword, Object> legacy = super.toLegacy();

		// REST section → flat keys in legacy config
		String baseUrl = getBaseUrl();
		if (baseUrl != null) legacy.put(Keywords.BASE_URL, baseUrl);

		if (getSection(REST).containsKey(FAUCET)) {
			legacy.put(Keywords.FAUCET, isFaucetEnabled());
		}

		return legacy;
	}
}
