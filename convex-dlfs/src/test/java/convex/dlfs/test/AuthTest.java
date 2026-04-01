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
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.auth.did.DID;
import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
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

	// ==================== MCP Identity Tests ====================

	/**
	 * Make an MCP tools/call request, optionally with a bearer token.
	 */
	private AMap<AString, ACell> mcpToolCall(String toolName, AMap<AString, ACell> arguments, String token) throws Exception {
		String mcpURL = "http://localhost:" + server.getPort() + "/mcp";
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0",
			"method", "tools/call",
			"params", Maps.of("name", toolName, "arguments", arguments),
			"id", "test"
		);
		String body = JSON.print(request).toString();
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(mcpURL))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json");
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode(), "MCP request should return 200");
		return RT.ensureMap(JSON.parse(resp.body()));
	}

	@Test
	void testMcpDriveIsolation() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);
		String identity = getDID(clientKeyPair);

		// Seed a drive for the authenticated user
		server.getDriveManager().createDrive(identity, "my-private");

		// Authenticated MCP: should see "my-private"
		AMap<AString, ACell> authedResult = mcpToolCall("dlfs_list_drives", Maps.empty(), token);
		String authedJson = JSON.print(authedResult).toString();
		assertTrue(authedJson.contains("my-private"), "Authenticated user should see their own drive");
		assertFalse(authedJson.contains("\"auth\""), "Authenticated user should NOT see anonymous drives");

		// Anonymous MCP: should see "auth" (seeded in setUp) but not "my-private"
		AMap<AString, ACell> anonResult = mcpToolCall("dlfs_list_drives", Maps.empty(), null);
		String anonJson = JSON.print(anonResult).toString();
		assertTrue(anonJson.contains("auth"), "Anonymous user should see anonymous drives");
		assertFalse(anonJson.contains("my-private"), "Anonymous user should NOT see authenticated drives");
	}

	@Test
	void testMcpWriteReadWithIdentity() throws Exception {
		String token = createSelfIssuedJWT(clientKeyPair, 3600);
		String identity = getDID(clientKeyPair);

		// Create a drive for the authenticated user
		server.getDriveManager().createDrive(identity, "mcp-auth-drive");

		// Write via MCP with auth
		AMap<AString, ACell> writeResult = mcpToolCall("dlfs_write",
			Maps.of("drive", "mcp-auth-drive", "path", "secret.txt", "content", "authenticated content"),
			token);
		String writeJson = JSON.print(writeResult).toString();
		assertTrue(writeJson.contains("\"created\":true"), "Write should create new file");

		// Read via MCP with auth — should succeed
		AMap<AString, ACell> readResult = mcpToolCall("dlfs_read",
			Maps.of("drive", "mcp-auth-drive", "path", "secret.txt"),
			token);
		String readJson = JSON.print(readResult).toString();
		assertTrue(readJson.contains("authenticated content"), "Should read the written content");

		// Read via MCP without auth — drive should not be found
		AMap<AString, ACell> anonResult = mcpToolCall("dlfs_read",
			Maps.of("drive", "mcp-auth-drive", "path", "secret.txt"),
			null);
		String anonJson = JSON.print(anonResult).toString();
		assertTrue(anonJson.contains("Drive not found") || anonJson.contains("isError"),
			"Anonymous should not access authenticated user's drive");
	}

	@Test
	void testMcpWriteWithBadlySignedJWT() throws Exception {
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

	// ==================== UCAN Delegated Access Tests ====================

	// Alice = drive owner (uses clientKeyPair)
	// Bob = delegatee (needs a separate keypair)
	private static final AKeyPair bobKeyPair = AKeyPair.generate();
	private static final AKeyPair attackerKeyPair = AKeyPair.generate();

	/**
	 * Creates a UCAN JWT granting access from issuer to audience on a DLFS resource.
	 * Returns a JWT string (not a CVM map).
	 */
	private static AString createDlfsUcanJWT(AKeyPair issuer, AKeyPair audience,
			String driveName, String path, String ability, long lifetimeSeconds) {
		String issuerDID = getDID(issuer);
		String resource = "dlfs://" + issuerDID + "/drives/" + driveName;
		if (path != null && !path.isEmpty()) {
			resource += "/" + path;
		}
		AVector<ACell> capabilities = Vectors.of(
			Capability.create(Strings.create(resource), Strings.create(ability))
		);
		long expiry = System.currentTimeMillis() / 1000 + lifetimeSeconds;
		return UCAN.createJWT(issuer, audience.getAccountKey(), expiry, capabilities, null);
	}

	/**
	 * Makes an MCP tool call with UCAN JWT proofs in the arguments.
	 */
	private AMap<AString, ACell> mcpToolCallWithUcans(String toolName, AMap<AString, ACell> arguments,
			String jwtToken, AVector<ACell> ucans) throws Exception {
		if (ucans != null) {
			arguments = arguments.assoc(Strings.intern("ucans"), ucans);
		}
		return mcpToolCall(toolName, arguments, jwtToken);
	}

	private String bobJWT() {
		return createSelfIssuedJWT(bobKeyPair, 3600);
	}

	private String aliceJWT() {
		return createSelfIssuedJWT(clientKeyPair, 3600);
	}

	// --- Happy path ---

	@Test
	void testUcanDelegatedRead() throws Exception {
		// Alice creates a drive and writes a file
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-shared");
		var aliceFs = server.getDriveManager().getDrive(aliceDID, "ucan-shared");
		java.nio.file.Files.write(aliceFs.getPath("/secret.txt"), "Alice's secret".getBytes(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);

		// Alice issues a UCAN granting Bob read access
		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-shared", null, "crud/read", 3600);

		// Bob reads Alice's file using the UCAN
		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-shared", "path", "secret.txt"),
			bobJWT(), Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Alice's secret"), "Bob should read Alice's file via UCAN");
	}

	@Test
	void testUcanDelegatedList() throws Exception {
		// Reuses ucan-shared drive from above (or creates if not present)
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-list");
		var fs = server.getDriveManager().getDrive(aliceDID, "ucan-list");
		java.nio.file.Files.write(fs.getPath("/doc.txt"), "hello".getBytes(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);

		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-list", null, "crud/read", 3600);

		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_list",
			Maps.of("drive", "ucan-list"),
			bobJWT(), Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("doc.txt"), "Bob should list Alice's drive via UCAN");
	}

	@Test
	void testUcanDelegatedWrite() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-writable");

		// Alice grants Bob write access
		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-writable", null, "crud/write", 3600);

		// Bob writes to Alice's drive
		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_write",
			Maps.of("drive", "ucan-writable", "path", "bob-wrote-this.txt", "content", "Hello from Bob"),
			bobJWT(), Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("\"created\":true"), "Bob should write to Alice's drive via UCAN");

		// Alice can see Bob's file
		AMap<AString, ACell> readResult = mcpToolCall("dlfs_read",
			Maps.of("drive", "ucan-writable", "path", "bob-wrote-this.txt"),
			aliceJWT());
		String readJson = JSON.print(readResult).toString();
		assertTrue(readJson.contains("Hello from Bob"), "Alice should see Bob's file");
	}

	@Test
	void testUcanWildcardAbility() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-wildcard");

		// "crud" covers "crud/read", "crud/write", "crud/delete" (UCAN prefix hierarchy)
		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-wildcard", null, "crud", 3600);

		// Write should work
		AMap<AString, ACell> writeResult = mcpToolCallWithUcans("dlfs_write",
			Maps.of("drive", "ucan-wildcard", "path", "wild.txt", "content", "wildcard"),
			bobJWT(), Vectors.of(ucan));
		assertTrue(JSON.print(writeResult).toString().contains("\"created\":true"));

		// Read should also work
		AMap<AString, ACell> readResult = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-wildcard", "path", "wild.txt"),
			bobJWT(), Vectors.of(ucan));
		assertTrue(JSON.print(readResult).toString().contains("wildcard"));
	}

	// --- Adversarial: expired UCAN ---

	@Test
	void testUcanExpiredToken() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-expired");

		// Issue an already-expired UCAN
		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-expired", null, "crud/read", -3600);

		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-expired", "path", "anything.txt"),
			bobJWT(), Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Drive not found") || json.contains("isError"),
			"Expired UCAN should be rejected");
	}

	// --- Adversarial: forged signature ---

	@Test
	void testUcanForgedSignature() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-forged");

		// Attacker creates a valid JWT but tampers the payload after signing.
		// Get a legitimate JWT from the attacker, then flip a payload byte.
		AString legitimateJwt = createDlfsUcanJWT(attackerKeyPair, bobKeyPair,
			"ucan-forged", null, "crud/read", 3600);

		// Tamper the payload section (flip a character)
		String s = legitimateJwt.toString();
		int dot1 = s.indexOf('.');
		int dot2 = s.indexOf('.', dot1 + 1);
		char c = s.charAt(dot1 + 5);
		char flipped = (c == 'A') ? 'B' : 'A';
		AString tampered = Strings.create(s.substring(0, dot1 + 5) + flipped + s.substring(dot1 + 6));

		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-forged", "path", "anything.txt"),
			bobJWT(), Vectors.of(tampered));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Drive not found") || json.contains("isError"),
			"UCAN JWT with tampered payload should be rejected");
	}

	// --- Adversarial: wrong audience ---

	@Test
	void testUcanWrongAudience() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-wrong-aud");

		// Alice issues UCAN to some other party, not Bob
		AKeyPair otherKP = AKeyPair.generate();
		AString ucan = createDlfsUcanJWT(clientKeyPair, otherKP,
			"ucan-wrong-aud", null, "crud/read", 3600);

		// Bob tries to use it — audience doesn't match Bob's DID
		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-wrong-aud", "path", "anything.txt"),
			bobJWT(), Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Drive not found") || json.contains("isError"),
			"UCAN with wrong audience should be rejected");
	}

	// --- Adversarial: ability escalation (read grant used for write) ---

	@Test
	void testUcanAbilityEscalation() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-readonly");

		// Alice grants Bob read-only access
		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-readonly", null, "crud/read", 3600);

		// Bob tries to write — should be denied
		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_write",
			Maps.of("drive", "ucan-readonly", "path", "evil.txt", "content", "pwned"),
			bobJWT(), Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Drive not found") || json.contains("isError"),
			"Read-only UCAN should not permit writes");

		// But reading should work
		var fs = server.getDriveManager().getDrive(aliceDID, "ucan-readonly");
		java.nio.file.Files.write(fs.getPath("/readable.txt"), "ok".getBytes(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);

		AMap<AString, ACell> readResult = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-readonly", "path", "readable.txt"),
			bobJWT(), Vectors.of(ucan));
		assertTrue(JSON.print(readResult).toString().contains("ok"),
			"Read-only UCAN should permit reads");
	}

	// --- Adversarial: wrong resource (UCAN for drive X used on drive Y) ---

	@Test
	void testUcanWrongDrive() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-drive-a");
		server.getDriveManager().createDrive(aliceDID, "ucan-drive-b");
		var fsB = server.getDriveManager().getDrive(aliceDID, "ucan-drive-b");
		java.nio.file.Files.write(fsB.getPath("/private.txt"), "B's secret".getBytes(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);

		// Alice grants Bob access to drive-a only
		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-drive-a", null, "crud/read", 3600);

		// Bob tries to read from drive-b — resource doesn't match
		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-drive-b", "path", "private.txt"),
			bobJWT(), Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Drive not found") || json.contains("isError"),
			"UCAN for drive-a should not grant access to drive-b");
	}

	// --- Adversarial: non-owner issues root UCAN ---

	@Test
	void testUcanNonOwnerIssuer() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-alice-only");

		// Attacker creates a valid UCAN JWT claiming to grant access to Alice's drive
		// but the attacker doesn't own the drive — issuer is attacker, not Alice
		AString illegitimateJwt = createDlfsUcanJWT(attackerKeyPair, bobKeyPair,
			"ucan-alice-only", null, "crud/read", 3600);

		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-alice-only", "path", "anything.txt"),
			bobJWT(), Vectors.of(illegitimateJwt));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Drive not found") || json.contains("isError"),
			"UCAN from non-owner should not grant drive access");
	}

	// --- Adversarial: anonymous caller with UCAN ---

	@Test
	void testUcanAnonymousPresenter() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-anon-test");

		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-anon-test", null, "crud/read", 3600);

		// Anonymous caller (no JWT) presents a UCAN — should be rejected
		AMap<AString, ACell> result = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-anon-test", "path", "anything.txt"),
			null, Vectors.of(ucan));
		String json = JSON.print(result).toString();
		assertTrue(json.contains("Drive not found") || json.contains("isError"),
			"Anonymous caller should not be able to use UCANs");
	}

	// --- Adversarial: path scoping ---

	@Test
	void testUcanPathScoping() throws Exception {
		String aliceDID = getDID(clientKeyPair);
		server.getDriveManager().createDrive(aliceDID, "ucan-scoped");
		var fs = server.getDriveManager().getDrive(aliceDID, "ucan-scoped");
		java.nio.file.Files.createDirectory(fs.getPath("/public"));
		java.nio.file.Files.write(fs.getPath("/public/ok.txt"), "visible".getBytes(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
		java.nio.file.Files.write(fs.getPath("/secret.txt"), "hidden".getBytes(),
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);

		// Alice grants Bob read access only to the "public" subdirectory
		AString ucan = createDlfsUcanJWT(clientKeyPair, bobKeyPair,
			"ucan-scoped", "public", "crud/read", 3600);

		// Bob can read from public/
		AMap<AString, ACell> okResult = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-scoped", "path", "public/ok.txt"),
			bobJWT(), Vectors.of(ucan));
		assertTrue(JSON.print(okResult).toString().contains("visible"),
			"Bob should read from scoped path");

		// Bob cannot read from root (outside the grant)
		AMap<AString, ACell> deniedResult = mcpToolCallWithUcans("dlfs_read",
			Maps.of("drive", "ucan-scoped", "path", "secret.txt"),
			bobJWT(), Vectors.of(ucan));
		String deniedJson = JSON.print(deniedResult).toString();
		assertTrue(deniedJson.contains("Drive not found") || deniedJson.contains("isError"),
			"Bob should not read files outside the scoped path");
	}
}
