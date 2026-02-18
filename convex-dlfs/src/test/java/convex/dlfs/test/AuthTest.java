package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import convex.dlfs.DLFSServer;

/**
 * Tests for Ed25519 JWT bearer token authentication on the DLFS WebDAV server.
 */
public class AuthTest {

	private static DLFSServer server;
	private static HttpClient client;
	private static String baseURL;
	private static AKeyPair serverKeyPair;
	private static AKeyPair clientKeyPair;

	@BeforeAll
	static void setUp() {
		serverKeyPair = AKeyPair.generate();
		clientKeyPair = AKeyPair.generate();

		server = DLFSServer.create(serverKeyPair);
		server.getWebDAV().setRequireAuthForWrites(true);
		server.start(0);
		baseURL = "http://localhost:" + server.getPort() + "/dlfs/";
		client = HttpClient.newHttpClient();
	}

	@AfterAll
	static void tearDown() {
		if (server != null) server.close();
	}

	// ==================== Helper ====================

	/**
	 * Creates a self-signed JWT for the given key pair.
	 */
	static String createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds) {
		long now = System.currentTimeMillis() / 1000;
		AString multikey = Multikey.encodePublicKey(kp.getAccountKey());
		AString did = Strings.create("did:key:").append(multikey);
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), did,
			Strings.create("iss"), did,
			Strings.create("iat"), CVMLong.create(now),
			Strings.create("exp"), CVMLong.create(now + lifetimeSeconds)
		);
		return JWT.signPublic(claims, kp).toString();
	}

	// ==================== Tests ====================

	@Test
	void testReadWithoutAuth() throws Exception {
		// GET root should succeed without auth
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL))
				.GET()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode(), "Read should succeed without auth");
	}

	@Test
	void testWriteWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "unauth.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("test"))
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write without auth should return 401");
	}

	@Test
	void testDeleteWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "some-file.txt"))
				.DELETE()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Delete without auth should return 401");
	}

	@Test
	void testMkcolWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "unauth-dir/"))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "MKCOL without auth should return 401");
	}

	@Test
	void testWriteWithValidJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);
		String path = baseURL + "authed.txt";

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("authenticated content"))
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, resp.statusCode(), "Write with valid JWT should succeed");

		// Verify content via GET (no auth needed)
		HttpRequest getReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.GET()
				.build();
		HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
		assertEquals("authenticated content", getResp.body());
	}

	@Test
	void testWriteWithExpiredJWT() throws Exception {
		// Create a JWT that expired 1 hour ago
		String token = createSelfIssuedJWT(clientKeyPair, -3600);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "expired.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("expired"))
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write with expired JWT should return 401");
	}

	@Test
	void testWriteWithGarbageToken() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "garbage.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("garbage"))
				.header("Authorization", "Bearer not.a.real.token")
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write with garbage token should return 401");
	}

	@Test
	void testMkcolWithValidJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "auth-dir/"))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, resp.statusCode(), "MKCOL with valid JWT should succeed");
	}

	@Test
	void testDeleteWithValidJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);
		String path = baseURL + "to-auth-delete.txt";

		// Create file first
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("delete me"))
				.header("Authorization", "Bearer " + token)
				.build();
		client.send(putReq, HttpResponse.BodyHandlers.ofString());

		// Delete with auth
		HttpRequest delReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.DELETE()
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> delResp = client.send(delReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(204, delResp.statusCode(), "Delete with valid JWT should succeed");
	}

	@Test
	void testPropfindWithoutAuth() throws Exception {
		// PROPFIND should succeed without auth (read-only)
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL))
				.method("PROPFIND", HttpRequest.BodyPublishers.noBody())
				.header("Depth", "0")
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(207, resp.statusCode(), "PROPFIND should succeed without auth");
	}
}
