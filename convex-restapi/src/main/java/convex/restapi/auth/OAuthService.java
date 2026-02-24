package convex.restapi.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.auth.jwt.JWT;
import convex.auth.jwt.JWKSKeys;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.RESTServer;

/**
 * OAuth 2.1 + PKCE flow management for social login.
 *
 * <p>Manages the authorization code flow with PKCE for configured OAuth providers
 * (Google, GitHub). After successful authentication, constructs a
 * {@code did:web:<hostname>:oauth:<provider>:<sub>} identity string.
 *
 * <p>Supports two provider types:
 * <ul>
 *   <li><b>OIDC</b> (Google, Apple): Validates RS256 ID token via JWKS</li>
 *   <li><b>OAuth2</b> (GitHub, Discord): Calls user API with access token</li>
 * </ul>
 */
public class OAuthService {

	private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
	private static final long STATE_EXPIRY_MS = 10 * 60 * 1000; // 10 minutes
	private static final long JWKS_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours

	private final RESTServer restServer;
	private final HttpClient httpClient;

	// Pending auth state: state token → PendingAuth
	private final ConcurrentHashMap<String, PendingAuth> pendingAuths = new ConcurrentHashMap<>();

	// JWKS cache: jwksUrl → CachedJWKS
	private final ConcurrentHashMap<String, CachedJWKS> jwksCache = new ConcurrentHashMap<>();

	/**
	 * OAuth provider definitions with their endpoints.
	 */
	public enum Provider {
		GOOGLE("Google", "google",
			"https://accounts.google.com/o/oauth2/v2/auth",
			"https://oauth2.googleapis.com/token",
			"https://www.googleapis.com/oauth2/v3/certs",
			null, "openid email", true),

		GITHUB("GitHub", "github",
			"https://github.com/login/oauth/authorize",
			"https://github.com/login/oauth/access_token",
			null,
			"https://api.github.com/user", "read:user", false);

		// Apple, Discord can be added in future

		private final String displayName;
		private final String id;
		private final String authUrl;
		private final String tokenUrl;
		private final String jwksUrl;
		private final String userApiUrl;
		private final String scope;
		private final boolean oidc;

		Provider(String displayName, String id, String authUrl, String tokenUrl,
				String jwksUrl, String userApiUrl, String scope, boolean oidc) {
			this.displayName = displayName;
			this.id = id;
			this.authUrl = authUrl;
			this.tokenUrl = tokenUrl;
			this.jwksUrl = jwksUrl;
			this.userApiUrl = userApiUrl;
			this.scope = scope;
			this.oidc = oidc;
		}

		public String displayName() { return displayName; }
		public String id() { return id; }
		public boolean isOidc() { return oidc; }

		/**
		 * Look up a provider by its lowercase ID.
		 */
		public static Provider byId(String id) {
			for (Provider p : values()) {
				if (p.id.equals(id)) return p;
			}
			return null;
		}
	}

	/**
	 * Pending OAuth authorisation state, stored between login page and callback.
	 */
	public record PendingAuth(Provider provider, String codeVerifier,
			String redirectUri, long createdAt) {
		public boolean isExpired() {
			return System.currentTimeMillis() - createdAt > STATE_EXPIRY_MS;
		}
	}

	/**
	 * Cached JWKS keys with TTL.
	 */
	private record CachedJWKS(Map<String, RSAPublicKey> keys, long fetchedAt) {
		boolean isExpired() {
			return System.currentTimeMillis() - fetchedAt > JWKS_TTL_MS;
		}
	}

	public OAuthService(RESTServer restServer) {
		this.restServer = restServer;
		this.httpClient = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}

	// ========== Provider configuration ==========

	/**
	 * Check if a provider has clientId and clientSecret configured.
	 */
	public boolean isConfigured(Provider provider) {
		String clientId = getClientId(provider);
		String clientSecret = getClientSecret(provider);
		return clientId != null && !clientId.isEmpty()
			&& clientSecret != null && !clientSecret.isEmpty();
	}

	/**
	 * Get all configured providers (those with clientId + clientSecret).
	 */
	public List<Provider> getConfiguredProviders() {
		List<Provider> result = new ArrayList<>();
		for (Provider p : Provider.values()) {
			if (isConfigured(p)) result.add(p);
		}
		return result;
	}

	private String getClientId(Provider provider) {
		return getOAuthConfig(provider.id, "clientId");
	}

	private String getClientSecret(Provider provider) {
		return getOAuthConfig(provider.id, "clientSecret");
	}

	private String getOAuthConfig(String providerId, String key) {
		// Read from server config map: auth.oauth.<provider>.<key>
		Map<?, ?> config = restServer.getConfig();
		Object authObj = config.get(convex.core.data.Keyword.create("auth"));
		if (authObj instanceof AMap<?,?> authMap) {
			AMap<AString, ACell> oauth = RT.ensureMap(((AMap<?,?>)authMap).get(Strings.create("oauth")));
			if (oauth != null) {
				AMap<AString, ACell> providerMap = RT.ensureMap(oauth.get(Strings.create(providerId)));
				if (providerMap != null) {
					AString v = RT.ensureString(providerMap.get(Strings.create(key)));
					if (v != null) return v.toString();
				}
			}
		}
		return null;
	}

	// ========== PKCE helpers ==========

	/**
	 * Generate a PKCE code verifier (43–128 chars, Base64URL-encoded random bytes).
	 */
	public static String generateCodeVerifier() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return BASE64URL.encodeToString(bytes);
	}

	/**
	 * Compute PKCE code challenge from verifier (SHA-256 → Base64URL).
	 */
	public static String computeCodeChallenge(String verifier) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return BASE64URL.encodeToString(hash);
		} catch (Exception e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	/**
	 * Generate a random state parameter (32 hex chars).
	 */
	public static String generateState() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		StringBuilder sb = new StringBuilder(32);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b & 0xff));
		}
		return sb.toString();
	}

	// ========== State management ==========

	/**
	 * Store pending auth state for an OAuth flow.
	 */
	public void storePendingAuth(String state, Provider provider,
			String codeVerifier, String redirectUri) {
		cleanExpiredState();
		pendingAuths.put(state, new PendingAuth(provider, codeVerifier,
			redirectUri, System.currentTimeMillis()));
	}

	/**
	 * Consume and return pending auth for a state token. Returns null if invalid/expired.
	 */
	public PendingAuth consumeState(String state) {
		if (state == null) return null;
		PendingAuth pending = pendingAuths.remove(state);
		if (pending == null || pending.isExpired()) return null;
		return pending;
	}

	private void cleanExpiredState() {
		Iterator<Map.Entry<String, PendingAuth>> it = pendingAuths.entrySet().iterator();
		while (it.hasNext()) {
			if (it.next().getValue().isExpired()) it.remove();
		}
	}

	// ========== Authorization URL ==========

	/**
	 * Build the provider's authorization URL with PKCE parameters.
	 */
	public String buildAuthorizationUrl(Provider provider, String redirectUri,
			String state, String codeChallenge) {
		String clientId = getClientId(provider);
		StringBuilder url = new StringBuilder(provider.authUrl);
		url.append("?response_type=code");
		url.append("&client_id=").append(enc(clientId));
		url.append("&redirect_uri=").append(enc(redirectUri));
		url.append("&state=").append(enc(state));
		url.append("&scope=").append(enc(provider.scope));
		if (provider.oidc) {
			// PKCE for OIDC providers
			url.append("&code_challenge=").append(enc(codeChallenge));
			url.append("&code_challenge_method=S256");
		}
		return url.toString();
	}

	// ========== Token exchange and identity resolution ==========

	/**
	 * Exchange authorization code for identity. Performs token exchange and
	 * identity resolution (OIDC ID token or user API call).
	 *
	 * @param provider OAuth provider
	 * @param code Authorization code from callback
	 * @param codeVerifier PKCE code verifier
	 * @param redirectUri Redirect URI used in the auth request
	 * @param hostname Peer hostname for DID construction
	 * @return Identity string as AString, or null if authentication fails
	 */
	public AString authenticate(Provider provider, String code,
			String codeVerifier, String redirectUri, String hostname) {
		try {
			// Exchange code for tokens
			AMap<AString, ACell> tokenResponse = exchangeCode(provider, code,
				codeVerifier, redirectUri);
			if (tokenResponse == null) return null;

			// Extract user identity
			String sub;
			if (provider.oidc) {
				sub = resolveOidcIdentity(provider, tokenResponse);
			} else {
				sub = resolveOauth2Identity(provider, tokenResponse);
			}
			if (sub == null) return null;

			// Construct DID identity
			return Strings.create("did:web:" + hostname + ":oauth:" + provider.id + ":" + sub);
		} catch (Exception e) {
			log.warn("OAuth authentication failed for {}: {}", provider.id, e.getMessage());
			return null;
		}
	}

	/**
	 * Construct the identity DID string for a given provider and subject.
	 * Public for testing.
	 */
	public static AString buildIdentity(String hostname, Provider provider, String sub) {
		return Strings.create("did:web:" + hostname + ":oauth:" + provider.id + ":" + sub);
	}

	/**
	 * Exchange authorization code for token response.
	 */
	private AMap<AString, ACell> exchangeCode(Provider provider, String code,
			String codeVerifier, String redirectUri) {
		try {
			String clientId = getClientId(provider);
			String clientSecret = getClientSecret(provider);

			StringBuilder body = new StringBuilder();
			body.append("grant_type=authorization_code");
			body.append("&code=").append(enc(code));
			body.append("&redirect_uri=").append(enc(redirectUri));
			body.append("&client_id=").append(enc(clientId));
			body.append("&client_secret=").append(enc(clientSecret));
			if (provider.oidc && codeVerifier != null) {
				body.append("&code_verifier=").append(enc(codeVerifier));
			}

			HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
				.uri(URI.create(provider.tokenUrl))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));

			// GitHub needs Accept: application/json
			if (!provider.oidc) {
				reqBuilder.header("Accept", "application/json");
			}

			HttpResponse<String> response = httpClient.send(reqBuilder.build(),
				HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				log.warn("Token exchange failed for {}: HTTP {}", provider.id, response.statusCode());
				return null;
			}

			return RT.ensureMap(JSON.parse(response.body()));
		} catch (Exception e) {
			log.warn("Token exchange error for {}: {}", provider.id, e.getMessage());
			return null;
		}
	}

	/**
	 * Resolve identity from OIDC ID token (Google, Apple).
	 * Validates RS256 signature via JWKS.
	 */
	private String resolveOidcIdentity(Provider provider, AMap<AString, ACell> tokenResponse) {
		AString idTokenStr = RT.ensureString(tokenResponse.get(Strings.create("id_token")));
		if (idTokenStr == null) {
			log.warn("No id_token in response from {}", provider.id);
			return null;
		}

		JWT idToken = JWT.parse(idTokenStr);
		if (idToken == null) return null;

		// Get the kid from the token header to look up the right key
		String kid = idToken.getKeyID();
		if (kid == null) return null;

		// Get JWKS keys
		Map<String, RSAPublicKey> keys = getJwksKeys(provider.jwksUrl);
		if (keys == null || keys.isEmpty()) return null;

		RSAPublicKey key = keys.get(kid);
		if (key == null) {
			// Key not found — try refreshing JWKS cache
			jwksCache.remove(provider.jwksUrl);
			keys = getJwksKeys(provider.jwksUrl);
			if (keys != null) key = keys.get(kid);
			if (key == null) return null;
		}

		// Verify RS256 signature
		if (!idToken.verifyRS256(key)) return null;

		// Validate claims (check expiry)
		if (!idToken.validateClaims(null, null)) return null;

		// Extract sub
		AString sub = RT.ensureString(idToken.getClaims().get(Strings.create("sub")));
		return (sub != null) ? sub.toString() : null;
	}

	/**
	 * Resolve identity from OAuth2 user API (GitHub, Discord).
	 */
	private String resolveOauth2Identity(Provider provider, AMap<AString, ACell> tokenResponse) {
		AString accessToken = RT.ensureString(tokenResponse.get(Strings.create("access_token")));
		if (accessToken == null) {
			log.warn("No access_token in response from {}", provider.id);
			return null;
		}

		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(provider.userApiUrl))
				.header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/json")
				.header("User-Agent", "Convex-Peer")
				.GET()
				.build();

			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				log.warn("User API call failed for {}: HTTP {}", provider.id, response.statusCode());
				return null;
			}

			AMap<AString, ACell> userInfo = RT.ensureMap(JSON.parse(response.body()));
			if (userInfo == null) return null;

			// GitHub uses "id" (numeric), Discord uses "id" (string)
			ACell idCell = userInfo.get(Strings.create("id"));
			return (idCell != null) ? idCell.toString() : null;
		} catch (Exception e) {
			log.warn("User API error for {}: {}", provider.id, e.getMessage());
			return null;
		}
	}

	// ========== JWKS caching ==========

	private Map<String, RSAPublicKey> getJwksKeys(String jwksUrl) {
		CachedJWKS cached = jwksCache.get(jwksUrl);
		if (cached != null && !cached.isExpired()) return cached.keys;

		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(jwksUrl))
				.GET()
				.build();

			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) return null;

			Map<String, RSAPublicKey> keys = JWKSKeys.parseKeys(response.body());
			jwksCache.put(jwksUrl, new CachedJWKS(keys, System.currentTimeMillis()));
			return keys;
		} catch (Exception e) {
			log.warn("JWKS fetch failed for {}: {}", jwksUrl, e.getMessage());
			return null;
		}
	}

	// ========== Helpers ==========

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
