package convex.restapi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTConfig;
import convex.restapi.RESTServer;
import convex.restapi.SeedTransport;

/**
 * Tests for MCP endpoint security controls: Origin validation (#552) and
 * transport security for seed-carrying tools (#554).
 */
public class McpSecurityTest {

	// ===== #554: seed transport rules (pure decision matrix) =====

	@Test
	public void testSeedTransportRules() {
		// HTTPS is always acceptable
		assertTrue(McpAPI.isSecureSeedTransport("https", null, "203.0.113.7"));
		// TLS-terminating proxy forwards the client-facing scheme
		assertTrue(McpAPI.isSecureSeedTransport("http", "https", "203.0.113.7"));
		assertTrue(McpAPI.isSecureSeedTransport("http", "https, http", "203.0.113.7"));
		// Loopback clients are exempt (local development), IPv4 and IPv6
		assertTrue(McpAPI.isSecureSeedTransport("http", null, "127.0.0.1"));
		assertTrue(McpAPI.isSecureSeedTransport("http", null, "::1"));
		// A local reverse proxy must preserve a cleartext client-facing scheme.
		assertFalse(McpAPI.isSecureSeedTransport("http", "http", "127.0.0.1"));
		// Cleartext HTTP from a remote address is refused
		assertFalse(McpAPI.isSecureSeedTransport("http", null, "203.0.113.7"));
		assertFalse(McpAPI.isSecureSeedTransport("http", "http", "203.0.113.7"));
		assertFalse(McpAPI.isSecureSeedTransport("http", null, null));
		assertTrue(SeedTransport.rejectedIncomingMessage().contains("key rotation is suggested"));
		assertFalse(SeedTransport.rejectedOutputMessage().contains("rotation"));
	}

	// ===== #552: Origin allow-list =====

	@Test
	public void testOriginAllowList() {
		McpServer ms = new McpServer(Maps.of("name", "t", "title", "t", "version", "0"));
		// Default: all origins allowed (public peer)
		assertTrue(ms.isOriginAllowed("https://anything.example"));
		ms.setAllowedOrigins(List.of("https://app.example.com"));
		assertTrue(ms.isOriginAllowed("https://app.example.com"));
		assertFalse(ms.isOriginAllowed("https://evil.example.com"));
		// Clearing restores allow-all
		ms.setAllowedOrigins(null);
		assertTrue(ms.isOriginAllowed("https://evil.example.com"));
	}

	/**
	 * End-to-end: a server configured with an Origin allow-list rejects MCP
	 * requests from other origins with 403, while allowed origins and
	 * origin-less (non-browser) requests pass through.
	 */
	@Test
	public void testOriginValidationEndToEnd() throws Exception {
		RESTConfig restConfig=RESTConfig.parse("""
			{peer:{keypair:"%s"},mcp:{allowedOrigins:["https://app.example.com"]}}
			""".formatted(AKeyPair.generate().getSeed().toHexString()));
		HashMap<Keyword, Object> config = restConfig.toLegacy();
		Server s = API.launchPeer(config);
		try (RESTServer rs = RESTServer.create(s)) {
			rs.start(0);
			String url = "http://localhost:" + rs.getPort() + "/mcp";
			String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
			HttpClient client = HttpClient.newHttpClient();

			HttpResponse<String> forbidden = client.send(HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("Content-Type", "application/json")
					.header("Origin", "https://evil.example.com")
					.POST(HttpRequest.BodyPublishers.ofString(ping)).build(),
				HttpResponse.BodyHandlers.ofString());
			assertEquals(403, forbidden.statusCode(), "Disallowed Origin should be rejected");

			HttpResponse<String> allowed = client.send(HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("Content-Type", "application/json")
					.header("Origin", "https://app.example.com")
					.POST(HttpRequest.BodyPublishers.ofString(ping)).build(),
				HttpResponse.BodyHandlers.ofString());
			assertEquals(200, allowed.statusCode(), "Allowed Origin should pass");

			HttpResponse<String> noOrigin = client.send(HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(ping)).build(),
				HttpResponse.BodyHandlers.ofString());
			assertNotEquals(403, noOrigin.statusCode(), "Non-browser (no Origin) requests should pass");
		} finally {
			s.close();
		}
	}
}
