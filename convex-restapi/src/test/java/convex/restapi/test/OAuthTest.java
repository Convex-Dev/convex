package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.restapi.auth.OAuthService;
import convex.restapi.auth.OAuthService.Provider;

/**
 * Tests for Stage 12: OAuth social login flow.
 *
 * Since real OAuth providers cannot be called in unit tests, we test:
 * - Login page rendering (no providers configured)
 * - Callback error handling (missing/invalid state, error param)
 * - PKCE helper functions (deterministic, correct format)
 * - Identity DID format construction
 * - ConfirmAPI j2html migration regression
 */
public class OAuthTest extends ARESTTest {

	private static final String AUTH_PATH = HOST_PATH + "/auth";
	private static final String CALLBACK_PATH = HOST_PATH + "/auth/callback";
	private static final String CONFIRM_PATH = HOST_PATH + "/confirm";

	// ===== Login page =====

	@Test
	public void testLoginPageRendersNoProviders() throws IOException, InterruptedException {
		// No OAuth providers configured in test environment
		HttpResponse<String> response = get(AUTH_PATH);
		assertEquals(200, response.statusCode());
		String body = response.body();
		assertTrue(body.contains("Sign In") || body.contains("sign in"),
			"Login page should contain sign in text");
		assertTrue(body.contains("No OAuth providers configured")
			|| body.contains("not available"),
			"Should indicate no providers configured");
	}

	// ===== Callback error handling =====

	@Test
	public void testCallbackErrorParam() throws IOException, InterruptedException {
		HttpResponse<String> response = get(CALLBACK_PATH + "?error=access_denied");
		assertEquals(200, response.statusCode());
		String body = response.body();
		assertTrue(body.contains("access_denied") || body.contains("Failed"),
			"Should show error from provider");
	}

	@Test
	public void testCallbackMissingState() throws IOException, InterruptedException {
		HttpResponse<String> response = get(CALLBACK_PATH + "?code=test_code");
		assertEquals(200, response.statusCode());
		String body = response.body();
		assertTrue(body.contains("Missing") || body.contains("Failed"),
			"Should indicate missing parameters");
	}

	@Test
	public void testCallbackInvalidState() throws IOException, InterruptedException {
		HttpResponse<String> response = get(CALLBACK_PATH + "?code=test_code&state=invalid_state_123");
		assertEquals(200, response.statusCode());
		String body = response.body();
		assertTrue(body.contains("Invalid") || body.contains("expired"),
			"Should indicate invalid state");
	}

	@Test
	public void testCallbackMissingCode() throws IOException, InterruptedException {
		HttpResponse<String> response = get(CALLBACK_PATH + "?state=some_state");
		assertEquals(200, response.statusCode());
		String body = response.body();
		assertTrue(body.contains("Missing") || body.contains("Failed"),
			"Should indicate missing code");
	}

	// ===== PKCE helpers =====

	@Test
	public void testGenerateCodeVerifier() {
		String v1 = OAuthService.generateCodeVerifier();
		String v2 = OAuthService.generateCodeVerifier();
		assertNotNull(v1);
		assertNotNull(v2);
		// Base64URL encoded 32 bytes = 43 chars
		assertEquals(43, v1.length(), "Code verifier should be 43 chars");
		assertNotEquals(v1, v2, "Verifiers should be unique");
		// Should be Base64URL safe (no +, /, =)
		assertFalse(v1.contains("+"), "Should not contain +");
		assertFalse(v1.contains("/"), "Should not contain /");
	}

	@Test
	public void testComputeCodeChallenge() {
		String verifier = "test-verifier-for-determinism";
		String c1 = OAuthService.computeCodeChallenge(verifier);
		String c2 = OAuthService.computeCodeChallenge(verifier);
		assertNotNull(c1);
		assertEquals(c1, c2, "Code challenge should be deterministic");
		// SHA-256 → 32 bytes → Base64URL = 43 chars
		assertEquals(43, c1.length(), "Code challenge should be 43 chars");
		// Different verifier → different challenge
		String c3 = OAuthService.computeCodeChallenge("different-verifier");
		assertNotEquals(c1, c3, "Different verifiers should produce different challenges");
	}

	@Test
	public void testGenerateState() {
		String s1 = OAuthService.generateState();
		String s2 = OAuthService.generateState();
		assertNotNull(s1);
		assertNotNull(s2);
		assertEquals(32, s1.length(), "State should be 32 hex chars");
		assertNotEquals(s1, s2, "States should be unique");
		// Should be all hex
		assertTrue(s1.matches("[0-9a-f]{32}"), "State should be lowercase hex");
	}

	// ===== Identity format =====

	@Test
	public void testBuildIdentityGoogle() {
		AString identity = OAuthService.buildIdentity("peer.example.com", Provider.GOOGLE, "118234567890");
		assertEquals(Strings.create("did:web:peer.example.com:oauth:google:118234567890"), identity);
	}

	@Test
	public void testBuildIdentityGitHub() {
		AString identity = OAuthService.buildIdentity("peer.example.com", Provider.GITHUB, "12345");
		assertEquals(Strings.create("did:web:peer.example.com:oauth:github:12345"), identity);
	}

	@Test
	public void testBuildIdentityLocalhost() {
		AString identity = OAuthService.buildIdentity("localhost", Provider.GOOGLE, "999");
		assertEquals(Strings.create("did:web:localhost:oauth:google:999"), identity);
	}

	// ===== Provider enum =====

	@Test
	public void testProviderById() {
		assertEquals(Provider.GOOGLE, Provider.byId("google"));
		assertEquals(Provider.GITHUB, Provider.byId("github"));
		assertNull(Provider.byId("nonexistent"));
	}

	@Test
	public void testProviderProperties() {
		assertTrue(Provider.GOOGLE.isOidc(), "Google should be OIDC");
		assertFalse(Provider.GITHUB.isOidc(), "GitHub should not be OIDC");
		assertEquals("Google", Provider.GOOGLE.displayName());
		assertEquals("GitHub", Provider.GITHUB.displayName());
		assertEquals("google", Provider.GOOGLE.id());
		assertEquals("github", Provider.GITHUB.id());
	}

	// ===== OAuthService state management =====

	@Test
	public void testConsumeStateValid() {
		OAuthService oauth = server.getOAuthService();
		String state = OAuthService.generateState();
		oauth.storePendingAuth(state, Provider.GOOGLE, "verifier123", "http://localhost/callback");

		OAuthService.PendingAuth pending = oauth.consumeState(state);
		assertNotNull(pending, "Should return pending auth");
		assertEquals(Provider.GOOGLE, pending.provider());
		assertEquals("verifier123", pending.codeVerifier());
		assertEquals("http://localhost/callback", pending.redirectUri());
	}

	@Test
	public void testConsumeStateSingleUse() {
		OAuthService oauth = server.getOAuthService();
		String state = OAuthService.generateState();
		oauth.storePendingAuth(state, Provider.GITHUB, "v", "http://localhost/callback");

		assertNotNull(oauth.consumeState(state), "First consume should succeed");
		assertNull(oauth.consumeState(state), "Second consume should return null");
	}

	@Test
	public void testConsumeStateInvalid() {
		OAuthService oauth = server.getOAuthService();
		assertNull(oauth.consumeState("nonexistent_state"));
		assertNull(oauth.consumeState(null));
	}

	// ===== ConfirmAPI j2html regression =====

	@Test
	public void testConfirmPageRendersHtml() throws IOException, InterruptedException {
		// GET /confirm without token should return 400 with HTML from j2html
		HttpResponse<String> response = get(CONFIRM_PATH);
		assertEquals(400, response.statusCode());
		String body = response.body();
		assertTrue(body.contains("Missing Token") || body.contains("No confirmation token"),
			"Should indicate missing token");
	}

	@Test
	public void testConfirmPageInvalidToken() throws IOException, InterruptedException {
		HttpResponse<String> response = get(CONFIRM_PATH + "?token=invalid_token_123");
		assertEquals(404, response.statusCode());
	}

	// ===== No configured providers =====

	@Test
	public void testNoProvidersConfigured() {
		OAuthService oauth = server.getOAuthService();
		assertNotNull(oauth, "OAuthService should always be created");
		assertTrue(oauth.getConfiguredProviders().isEmpty(),
			"Test environment should have no OAuth providers configured");
		assertFalse(oauth.isConfigured(Provider.GOOGLE));
		assertFalse(oauth.isConfigured(Provider.GITHUB));
	}
}
