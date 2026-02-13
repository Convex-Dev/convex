package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.json.JWT;
import convex.peer.auth.PeerAuth;
import convex.restapi.auth.AuthMiddleware;

/**
 * Tests for AuthMiddleware — bearer token extraction and verification via HTTP.
 *
 * Uses the shared REST server from ARESTTest. Tests the PeerAuth + AuthMiddleware
 * integration by making HTTP requests with various Authorization headers.
 */
public class AuthMiddlewareTest extends ARESTTest {

	// ===== PeerAuth Unit Tests =====
	// (These verify the auth logic independently of HTTP)

	@Test
	public void testPeerAuthCreation() {
		PeerAuth auth = new PeerAuth(KP);
		assertEquals(KP.getAccountKey(), auth.getPeerKey());
	}

	@Test
	public void testSelfIssuedTokenRoundTrip() {
		PeerAuth auth = new PeerAuth(KP);

		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, 300);

		AString identity = auth.verifyBearerToken(jwt);
		assertNotNull(identity);

		AString expectedDID = Strings.create("did:key:").append(Multikey.encodePublicKey(clientKP.getAccountKey()));
		assertEquals(expectedDID, identity);
	}

	@Test
	public void testPeerSignedTokenRoundTrip() {
		PeerAuth auth = new PeerAuth(KP);

		AString identity = Strings.create("did:web:localhost:oauth:google:12345");
		AString jwt = auth.issuePeerToken(identity, 3600);

		AString result = auth.verifyBearerToken(jwt);
		assertNotNull(result);
		assertEquals(identity, result);
	}

	// ===== AuthMiddleware Unit Tests =====

	@Test
	public void testAuthMiddlewareCreation() {
		PeerAuth auth = new PeerAuth(KP);
		AuthMiddleware mw = new AuthMiddleware(auth);
		assertNotNull(mw.handler());
		assertNotNull(mw.requiredHandler());
		assertEquals(auth, mw.getPeerAuth());
	}

	@Test
	public void testAuthMiddlewareNullArg() {
		assertThrows(IllegalArgumentException.class, () -> new AuthMiddleware(null));
	}

	// ===== HTTP Integration Tests =====
	// (These verify the middleware works end-to-end via actual HTTP requests)

	@Test
	public void testPublicEndpointNoAuth() throws IOException, InterruptedException {
		// DID endpoints should be accessible without auth
		HttpResponse<String> resp = get(HOST_PATH + "/.well-known/did.json");
		assertEquals(200, resp.statusCode());
	}

	@Test
	public void testMcpWithSelfIssuedJWT() throws IOException, InterruptedException {
		// Create a self-issued JWT
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, 300);

		// MCP request with valid bearer token should succeed
		String mcpRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}";
		HttpResponse<String> resp = postWithAuth(HOST_PATH + "/mcp", mcpRequest, jwt.toString());
		assertEquals(200, resp.statusCode());
	}

	@Test
	public void testMcpWithPeerSignedJWT() throws IOException, InterruptedException {
		PeerAuth auth = new PeerAuth(KP);
		AString identity = Strings.create("did:web:localhost:oauth:google:67890");
		AString jwt = auth.issuePeerToken(identity, 3600);

		String mcpRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}";
		HttpResponse<String> resp = postWithAuth(HOST_PATH + "/mcp", mcpRequest, jwt.toString());
		assertEquals(200, resp.statusCode());
	}

	@Test
	public void testMcpWithNoAuth() throws IOException, InterruptedException {
		// MCP request without auth should still work (initialize doesn't require auth)
		String mcpRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}";
		HttpResponse<String> resp = post(HOST_PATH + "/mcp", mcpRequest);
		assertEquals(200, resp.statusCode());
	}

	@Test
	public void testMcpWithExpiredJWT() throws IOException, InterruptedException {
		AKeyPair clientKP = AKeyPair.generate();
		AString jwt = createSelfIssuedJWT(clientKP, -60); // expired

		// Should still get 200 for MCP (auth is optional on MCP route currently)
		// but identity should not be set
		String mcpRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}";
		HttpResponse<String> resp = postWithAuth(HOST_PATH + "/mcp", mcpRequest, jwt.toString());
		assertEquals(200, resp.statusCode());
	}

	@Test
	public void testMcpWithGarbageAuth() throws IOException, InterruptedException {
		String mcpRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"params\":{},\"id\":1}";
		HttpResponse<String> resp = postWithAuth(HOST_PATH + "/mcp", mcpRequest, "not-a-jwt");
		// Should still get 200 — garbage token means no identity, but MCP allows unauthenticated
		assertEquals(200, resp.statusCode());
	}

	// ===== Helpers =====

	private static AString createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds) {
		AString didKey = Strings.create("did:key:").append(Multikey.encodePublicKey(kp.getAccountKey()));
		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), didKey,
			Strings.create("iss"), didKey,
			Strings.create("iat"), CVMLong.create(now),
			Strings.create("exp"), CVMLong.create(now + lifetimeSeconds)
		);
		return JWT.signPublic(claims, kp);
	}

	protected static HttpResponse<String> postWithAuth(String url, String jsonBody, String bearerToken)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + bearerToken)
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
