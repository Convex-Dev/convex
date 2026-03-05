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
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.dlfs.DLFSServer;

/**
 * Tests for Ed25519 JWT bearer token authentication on the DLFS WebDAV server.
 * All write tests operate within pre-seeded or JWT-created drives.
 */
public class AuthTest {

	private static DLFSServer server;
	private static HttpClient client;
	private static String baseURL;
	/** Base URL for the pre-seeded "auth" drive (anonymous owner) */
	private static String driveURL;
	private static AKeyPair serverKeyPair;
	private static AKeyPair clientKeyPair;

	/** Expected audience — the server's DID (did:key:<multikey>) */
	private static String expectedAudience;


	@BeforeAll
	static void setUp() {
		serverKeyPair = AKeyPair.generate();
		clientKeyPair = AKeyPair.generate();
		expectedAudience = DID.forKey(serverKeyPair.getAccountKey()).toString();

		server = DLFSServer.create(serverKeyPair);
		server.getWebDAV().setRequireAuthForWrites(true);
		// Pre-seed a drive for anonymous read tests
		server.getDriveManager().createDrive(null, "auth");
		server.start(0);
		baseURL = "http://localhost:" + server.getPort() + "/dlfs/";
		driveURL = baseURL + "auth/";
		client = HttpClient.newHttpClient();
	}

	@AfterAll
	static void tearDown() {
		if (server != null) server.close();
	}

	// ==================== Helper ====================

	/**
	 * Creates a self-signed JWT for the given key pair with the server's audience.
	 */
	static String createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds) {
		return createSelfIssuedJWT(kp, lifetimeSeconds, expectedAudience);
	}

	/**
	 * Creates a self-signed JWT with a specific audience (or null for no aud claim).
	 */
	static String createSelfIssuedJWT(AKeyPair kp, long lifetimeSeconds, String audience) {
		long now = System.currentTimeMillis() / 1000;
		AString did = DID.forKey(kp.getAccountKey());
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, did,
			JWT.ISS, did,
			JWT.IAT, now,
			JWT.EXP, now + lifetimeSeconds
		);
		if (audience != null) {
			claims = claims.assoc(JWT.AUD, Strings.create(audience));
		}
		return JWT.signPublic(claims, kp).toString();
	}

	/**
	 * Returns the DID string for a key pair.
	 */
	static String getDID(AKeyPair kp) {
		return DID.forKey(kp.getAccountKey()).toString();
	}

	// ==================== Tests ====================

	@Test
	void testReadWithoutAuth() throws Exception {
		// GET drive listing should succeed without auth
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
				.uri(URI.create(driveURL + "unauth.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("test"))
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write without auth should return 401");
	}

	@Test
	void testDeleteWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "some-file.txt"))
				.DELETE()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Delete without auth should return 401");
	}

	@Test
	void testMkcolWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "unauth-dir/"))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "MKCOL without auth should return 401");
	}

	@Test
	void testWriteWithValidJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);
		String identity = getDID(clientKeyPair);

		// Create a drive for the authenticated user first
		server.getDriveManager().createDrive(identity, "jwt-write");
		String path = baseURL + "jwt-write/authed.txt";

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("authenticated content"))
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, resp.statusCode(), "Write with valid JWT should succeed");

		// Verify content via GET (same identity to access the drive)
		HttpRequest getReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.GET()
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
		assertEquals("authenticated content", getResp.body());
	}

	@Test
	void testWriteWithExpiredJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, -3600);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "expired.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("expired"))
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write with expired JWT should return 401");
	}

	@Test
	void testWriteWithGarbageToken() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "garbage.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("garbage"))
				.header("Authorization", "Bearer not.a.real.token")
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write with garbage token should return 401");
	}

	@Test
	void testMkcolDriveWithValidJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "jwt-newdrive/"))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, resp.statusCode(), "MKCOL drive with valid JWT should succeed");
	}

	@Test
	void testMkcolDirWithValidJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);
		String identity = getDID(clientKeyPair);

		// Create drive first
		server.getDriveManager().createDrive(identity, "jwt-dir");

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "jwt-dir/auth-subdir/"))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, resp.statusCode(), "MKCOL dir with valid JWT should succeed");
	}

	@Test
	void testDeleteWithValidJWT() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);
		String identity = getDID(clientKeyPair);

		// Create drive and file
		server.getDriveManager().createDrive(identity, "jwt-del");
		String path = baseURL + "jwt-del/to-auth-delete.txt";

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

	@Test
	void testWriteWithWrongAudience() throws Exception {
		// JWT signed correctly but with wrong aud
		String token = createSelfIssuedJWT(clientKeyPair, 3600, "did:key:zWRONGSERVER");

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "wrong-aud.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("wrong audience"))
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write with wrong aud should return 401");
	}

	@Test
	void testWriteWithMissingAudience() throws Exception {
		// JWT signed correctly but no aud claim at all
		String token = createSelfIssuedJWT(clientKeyPair, 3600, null);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "no-aud.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("missing audience"))
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write with missing aud should return 401");
	}

	@Test
	void testWriteWithBadlySignedJWT() throws Exception {
		// Attacker crafts claims with correct aud but signs with their own key
		// and puts a different key's DID as sub
		AKeyPair attackerKP = AKeyPair.generate();
		AString victimDID = DID.forKey(clientKeyPair.getAccountKey());

		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, victimDID,
			JWT.ISS, victimDID,
			JWT.AUD, expectedAudience,
			JWT.IAT, now,
			JWT.EXP, now + 3600
		);
		// signPublic sets kid to attackerKP's key, so kid != sub → rejected
		String token = JWT.signPublic(claims, attackerKP).toString();

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "forged.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("forged identity"))
				.header("Authorization", "Bearer " + token)
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode(), "Write with forged JWT (kid != sub) should return 401");
	}
}
