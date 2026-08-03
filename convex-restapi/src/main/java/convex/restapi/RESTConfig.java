package convex.restapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import convex.auth.did.DID;
import convex.core.crypto.util.Multikey;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
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
	/** Legacy launch-map key carrying this typed configuration to RESTServer. */
	public static final Keyword CONFIG = Keyword.intern("rest-config");

	// ========== Top-level section keys ==========

	public static final AString REST = Strings.intern("rest");
	public static final AString MCP = Strings.intern("mcp");

	// ========== REST config keys ==========

	public static final AString BASE_URL = Strings.intern("baseUrl");
	public static final AString FAUCET = Strings.intern("faucet");
	public static final AString CORS = Strings.intern("cors");
	public static final AString QUERY_WATCH = Strings.intern("queryWatch");
	public static final AString ADMIN = Strings.intern("admin");
	public static final AString MESSAGE_ENDPOINT = Strings.intern("messageEndpoint");
	public static final AString KEYS = Strings.intern("keys");
	public static final AString TRUSTED_PROXIES = Strings.intern("trustedProxies");
	public static final AString MAX_CONCURRENT_REQUESTS = Strings.intern("maxConcurrentRequests");
	public static final AString MAX_REQUEST_BYTES = Strings.intern("maxRequestBytes");

	// ========== x402 config keys (rest.x402.*) ==========

	public static final AString X402 = Strings.intern("x402");
	public static final AString FACILITATOR = Strings.intern("facilitator");
	public static final AString PAY_TO = Strings.intern("payTo");
	public static final AString ROUTES = Strings.intern("routes");

	// ========== Public query lane keys (rest.query.*) ==========

	public static final AString QUERY = Strings.intern("query");
	public static final AString MAX_CONCURRENT = Strings.intern("maxConcurrent");
	public static final AString MAX_WAIT_MILLIS = Strings.intern("maxWaitMillis");
	public static final AString MAX_JUICE = Strings.intern("maxJuice");

	// ========== Public-surface limit defaults ==========

	/** Default global cap on concurrent short-lived REST requests. */
	public static final long DEFAULT_MAX_CONCURRENT_REQUESTS = 10_000L;
	/** Default maximum size in bytes of a structured REST request body. */
	public static final long DEFAULT_MAX_REQUEST_BYTES = 1_000_000L;
	/** Default time a public query waits for an execution slot before shedding load. */
	public static final long DEFAULT_QUERY_MAX_WAIT_MILLIS = 1_000L;

	// ========== MCP config keys ==========

	public static final AString ENABLED = Strings.intern("enabled");
	public static final AString SIGNING = Strings.intern("signing");
	public static final AString ELEVATED = Strings.intern("elevated");
	public static final AString TOOLS = Strings.intern("tools");
	public static final AString ALLOWED_ORIGINS = Strings.intern("allowedOrigins");
	public static final AString ALLOW_HTTP_SEEDS = Strings.intern("allowHttpSeeds");

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

	/**
	 * Whether the query watch endpoint is enabled.
	 * @return true if query watching is enabled (default: false)
	 */
	public boolean isQueryWatchEnabled() {
		return getBool(getSection(REST), QUERY_WATCH, false);
	}

	/**
	 * Whether process administration routes are enabled.
	 * @return true if administration is explicitly enabled (default: false)
	 */
	public boolean isAdminEnabled() {
		ACell value=getSection(REST).get(ADMIN);
		AMap<AString,ACell> section=RT.castMap(value);
		if (section!=null) value=section.get(ENABLED);
		if (value==null) return false;
		if (value instanceof CVMBool enabled) return enabled.booleanValue();
		throw new IllegalArgumentException("rest.admin must be a boolean or an object with a boolean enabled field");
	}

	/**
	 * Gets an explicit administrator-key allowlist.
	 *
	 * <p>Keys are represented as {@code did:key} identities. A missing value means
	 * use the venue operational key and the current consensus controller key. An
	 * explicitly configured empty vector therefore denies all keys.</p>
	 *
	 * @return configured key identities, or {@code null} for the dynamic defaults
	 */
	public java.util.Set<AString> getAdminKeys() {
		AMap<AString,ACell> section=RT.castMap(getSection(REST).get(ADMIN));
		if ((section==null)||!section.containsKey(KEYS)) return null;
		convex.core.data.AVector<ACell> values=RT.ensureVector(section.get(KEYS));
		if (values==null) throw new IllegalArgumentException("rest.admin.keys must be a vector of did:key strings");
		java.util.HashSet<AString> result=new java.util.HashSet<>();
		for (long i=0;i<values.count();i++) {
			AString key=RT.ensureString(values.get(i));
			if ((key==null)||!key.toString().startsWith("did:key:")) {
				throw new IllegalArgumentException("rest.admin.keys entries must be did:key strings");
			}
			try {
				var accountKey=Multikey.decodePublicKey(key.toString().substring("did:key:".length()));
				result.add(DID.forKey(accountKey));
			} catch (Exception e) {
				throw new IllegalArgumentException("Invalid did:key in rest.admin.keys: "+key,e);
			}
		}
		return java.util.Set.copyOf(result);
	}

	/**
	 * Gets immediate proxy addresses trusted to report HTTPS transport for remote
	 * administration. Loopback proxies are always trusted; forwarded client
	 * addresses are never used for authorisation.
	 *
	 * @return configured immediate proxy IP addresses
	 */
	public java.util.Set<String> getAdminTrustedProxies() {
		AMap<AString,ACell> section=RT.castMap(getSection(REST).get(ADMIN));
		if ((section==null)||!section.containsKey(TRUSTED_PROXIES)) return java.util.Set.of();
		convex.core.data.AVector<ACell> values=RT.ensureVector(section.get(TRUSTED_PROXIES));
		if (values==null) throw new IllegalArgumentException("rest.admin.trustedProxies must be a vector of IP address strings");
		java.util.HashSet<String> result=new java.util.HashSet<>();
		for (long i=0;i<values.count();i++) {
			AString address=RT.ensureString(values.get(i));
			if ((address==null)||address.toString().isBlank()) {
				throw new IllegalArgumentException("rest.admin.trustedProxies entries must be IP address strings");
			}
			result.add(address.toString());
		}
		return java.util.Set.copyOf(result);
	}

	/**
	 * Whether the generic Peer protocol message endpoint is enabled.
	 *
	 * <p>This endpoint is disabled by default because it exposes essentially the
	 * same message surface as the public Peer protocol port. Operators may enable
	 * it deliberately when HTTP is their chosen public protocol transport.</p>
	 *
	 * @return true if the generic message endpoint is explicitly enabled
	 */
	public boolean isMessageEndpointEnabled() {
		return getBool(getSection(REST), MESSAGE_ENDPOINT, false);
	}

	/**
	 * Gets configured CORS origins.
	 * @return Configured origin strings, or {@code null} to allow all origins
	 */
	public java.util.Set<String> getCorsAllowedOrigins() {
		ACell value = getSection(REST).get(CORS);
		if (value == null) return null;
		AString single = RT.ensureString(value);
		if (single != null) {
			if ("*".equals(single.toString())) return null;
			return java.util.Set.of(single.toString());
		}
		convex.core.data.AVector<ACell> values = RT.ensureVector(value);
		if (values == null) return java.util.Set.of();
		java.util.HashSet<String> result = new java.util.HashSet<>();
		for (long i = 0; i < values.count(); i++) {
			AString origin = RT.ensureString(values.get(i));
			if (origin != null) result.add(origin.toString());
		}
		return result;
	}

	// ========== Public-surface limit accessors ==========

	/**
	 * Global cap on concurrent short-lived REST requests. Long-lived SSE streams
	 * keep their own connection limits and are not counted here.
	 * @return maximum concurrent requests (default 10,000)
	 */
	public long getMaxConcurrentRequests() {
		return getLong(getSection(REST), MAX_CONCURRENT_REQUESTS, DEFAULT_MAX_CONCURRENT_REQUESTS);
	}

	/**
	 * Maximum size in bytes of a structured REST request body.
	 * @return maximum request body size (default 1,000,000)
	 */
	public long getMaxRequestBytes() {
		return getLong(getSection(REST), MAX_REQUEST_BYTES, DEFAULT_MAX_REQUEST_BYTES);
	}

	/**
	 * Maximum number of public queries executed concurrently. This bounds the CPU
	 * cores public reads may take from consensus, not throughput — a single core
	 * evaluates queries fast enough that a small slice of cores serves a high query
	 * rate. Defaults to roughly a tenth of the available processors (at least one).
	 * @return maximum concurrent public queries
	 */
	public int getMaxConcurrentQueries() {
		long dflt = Math.max(1L, Runtime.getRuntime().availableProcessors()/10 + 1);
		return (int) getLong(getQuerySection(), MAX_CONCURRENT, dflt);
	}

	/**
	 * Time a public query waits for a free execution slot before returning a load
	 * result. A short wait absorbs bursts without rejecting; only a sustained
	 * overload sheds.
	 * @return maximum wait in milliseconds (default 1000)
	 */
	public long getQueryMaxWaitMillis() {
		return getLong(getQuerySection(), MAX_WAIT_MILLIS, DEFAULT_QUERY_MAX_WAIT_MILLIS);
	}

	/**
	 * Execution ceiling applied to each public query.
	 * @return maximum Juice per public query (default {@link PublicQueryService#MAX_QUERY_JUICE})
	 */
	public long getMaxQueryJuice() {
		return getLong(getQuerySection(), MAX_JUICE, PublicQueryService.MAX_QUERY_JUICE);
	}

	// ========== x402 typed accessors ==========

	/**
	 * Whether x402 payment support is enabled (CAD042).
	 * @return true if x402 is explicitly enabled (default: false)
	 */
	public boolean isX402Enabled() {
		ACell value=getSection(REST).get(X402);
		AMap<AString,ACell> section=RT.castMap(value);
		if (section!=null) value=section.get(ENABLED);
		if (value==null) return false;
		if (value instanceof CVMBool enabled) return enabled.booleanValue();
		throw new IllegalArgumentException("rest.x402 must be a boolean or an object with a boolean enabled field");
	}

	/**
	 * Whether the x402 facilitator endpoints are served when x402 is enabled.
	 * @return true unless explicitly disabled (default: true)
	 */
	public boolean isX402FacilitatorEnabled() {
		return getBool(getX402Section(), FACILITATOR, true);
	}

	/**
	 * Gets the default recipient address for x402-gated routes.
	 * @return payTo address string, or null if not configured
	 */
	public AString getX402PayTo() {
		return RT.ensureString(getX402Section().get(PAY_TO));
	}

	/**
	 * Gets the configured x402 gated routes.
	 * @return Vector of route config objects, or empty vector if none
	 */
	public convex.core.data.AVector<ACell> getX402Routes() {
		convex.core.data.AVector<ACell> v=RT.ensureVector(getX402Section().get(ROUTES));
		return (v!=null) ? v : Vectors.empty();
	}

	private AMap<AString,ACell> getX402Section() {
		AMap<AString,ACell> s = RT.castMap(getSection(REST).get(X402));
		return (s!=null) ? s : Maps.empty();
	}

	private AMap<AString,ACell> getQuerySection() {
		AMap<AString,ACell> q = RT.castMap(getSection(REST).get(QUERY));
		return (q!=null) ? q : Maps.empty();
	}

	private static long getLong(AMap<AString,ACell> section, AString key, long defaultValue) {
		ACell raw = section.get(key);
		if (raw==null) return defaultValue;
		CVMLong v = RT.ensureLong(raw);
		if (v==null) throw new IllegalArgumentException("rest config value for '"+key+"' must be an integer");
		return v.longValue();
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
	 * @return true if elevated ops are explicitly enabled (default: false)
	 */
	public boolean isElevatedEnabled() {
		return getBool(getSection(MCP), ELEVATED, false);
	}

	/**
	 * Get the allowed Origins for MCP requests (#552).
	 * @return Set of allowed Origin strings, or null if all origins are allowed
	 */
	public java.util.Set<String> getAllowedOrigins() {
		ACell v = getSection(MCP).get(ALLOWED_ORIGINS);
		if (v == null) return null;
		convex.core.data.AVector<ACell> vec = RT.ensureVector(v);
		if (vec == null) return null;
		java.util.HashSet<String> result = new java.util.HashSet<>();
		for (long i = 0; i < vec.count(); i++) {
			AString s = RT.ensureString(vec.get(i));
			if (s != null) result.add(s.toString());
		}
		return result;
	}

	/**
	 * Whether seed-based REST and MCP operations may be used over cleartext HTTP (#554).
	 * @return true if HTTP seeds are allowed (default: false — HTTPS or loopback required)
	 */
	public boolean isHttpSeedsAllowed() {
		AMap<AString,ACell> restSection=getSection(REST);
		if (restSection.containsKey(ALLOW_HTTP_SEEDS)) {
			return getBool(restSection,ALLOW_HTTP_SEEDS,false);
		}
		// Backwards compatibility with the original MCP-only option.
		return getBool(getSection(MCP), ALLOW_HTTP_SEEDS, false);
	}

	/**
	 * Get the MCP tools configuration section.
	 * @return Tools config map, or empty map if not configured
	 */
	public AMap<AString, ACell> getToolsConfig() {
		AMap<AString, ACell> mcpSection = getSection(MCP);
		AMap<AString, ACell> tools = RT.castMap(mcpSection.get(TOOLS));
		return (tools != null) ? tools : Maps.empty();
	}

	/**
	 * Tests whether an MCP tool is enabled by the per-tool policy.
	 * Missing entries default to enabled; group switches such as signing and
	 * elevated access are applied separately by the tool registrar.
	 *
	 * @param name MCP tool name
	 * @return true unless the tool is explicitly disabled
	 */
	public boolean isToolEnabled(String name) {
		ACell value = getToolsConfig().get(Strings.create(name));
		if (value == null) return true;
		AMap<AString, ACell> tool = RT.castMap(value);
		if (tool != null) return getBool(tool, ENABLED, true);
		return RT.bool(value);
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
		AMap<AString, ACell> oauth = RT.castMap(oauthCell);
		if (oauth == null) return null;
		ACell providerCell = oauth.get(Strings.create(provider));
		if (providerCell == null) return null;
		return RT.castMap(providerCell);
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
	 * Normalises the supported legacy REST launch keys into typed configuration.
	 *
	 * <p>This is a compatibility boundary for programmatic callers that predate
	 * {@link RESTConfig}. New configuration should attach the typed object using
	 * {@link #toLegacy()}, so nested authentication and feature policy is retained.</p>
	 *
	 * @param legacy Peer launch map
	 * @return Typed configuration containing recognised legacy REST keys
	 */
	public static RESTConfig fromLegacy(Map<Keyword, Object> legacy) {
		AMap<AString,ACell> rest=Maps.empty();
		AMap<AString,ACell> mcp=Maps.empty();

		Object baseUrl=legacy.get(Keywords.BASE_URL);
		if (baseUrl instanceof String s) rest=rest.assoc(BASE_URL,Strings.create(s));
		if (legacy.containsKey(Keywords.FAUCET)) {
			rest=rest.assoc(FAUCET,CVMBool.create(RT.bool(legacy.get(Keywords.FAUCET))));
		}
		if (legacy.containsKey(Keywords.QUERY_WATCH)) {
			rest=rest.assoc(QUERY_WATCH,CVMBool.create(RT.bool(legacy.get(Keywords.QUERY_WATCH))));
		}

		Object origins=legacy.get(Keywords.ALLOWED_ORIGINS);
		if (origins instanceof Collection<?> values) {
			var vector=Vectors.<ACell>empty();
			for (Object value:values) {
				if (value!=null) vector=vector.conj(Strings.create(value.toString()));
			}
			mcp=mcp.assoc(ALLOWED_ORIGINS,vector);
		}
		if (legacy.containsKey(Keywords.ALLOW_HTTP_SEEDS)) {
			mcp=mcp.assoc(ALLOW_HTTP_SEEDS,
					CVMBool.create(RT.bool(legacy.get(Keywords.ALLOW_HTTP_SEEDS))));
		}

		return create(Maps.of(REST,rest,MCP,mcp));
	}

	/**
	 * Convert this config to the legacy {@code HashMap<Keyword, Object>} format,
	 * including both peer and REST section keys.
	 *
	 * @return Legacy config map suitable for {@code API.launchPeer()}
	 */
	@Override
	public HashMap<Keyword, Object> toLegacy() {
		HashMap<Keyword, Object> legacy = super.toLegacy();
		// Preserve the typed configuration across the legacy Peer launch boundary.
		// RESTServer consumes this object directly instead of reconstructing nested
		// REST, MCP and authentication policy from flattened keys.
		legacy.put(CONFIG, this);

		// REST section → flat keys in legacy config
		String baseUrl = getBaseUrl();
		if (baseUrl != null) legacy.put(Keywords.BASE_URL, baseUrl);

		if (getSection(REST).containsKey(FAUCET)) {
			legacy.put(Keywords.FAUCET, isFaucetEnabled());
		}
		if (getSection(REST).containsKey(QUERY_WATCH)) {
			legacy.put(Keywords.QUERY_WATCH, isQueryWatchEnabled());
		}

		// MCP security options (#552, #554)
		java.util.Set<String> origins = getAllowedOrigins();
		if (origins != null) legacy.put(Keywords.ALLOWED_ORIGINS, origins);
		if (getSection(REST).containsKey(ALLOW_HTTP_SEEDS)||getSection(MCP).containsKey(ALLOW_HTTP_SEEDS)) {
			legacy.put(Keywords.ALLOW_HTTP_SEEDS, isHttpSeedsAllowed());
		}

		return legacy;
	}
}
