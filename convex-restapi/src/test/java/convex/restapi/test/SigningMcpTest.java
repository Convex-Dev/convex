package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Providers;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.auth.jwt.JWT;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.auth.PeerAuth;
import convex.restapi.mcp.McpAPI;
import convex.restapi.mcp.McpProtocol;

/**
 * Tests for the MCP signing service tools (Stage 9).
 *
 * Exercises the signingServiceInfo, signingCreateKey, signingListKeys,
 * signingSign, and signingGetJWT tools via the MCP JSON-RPC endpoint,
 * verifying auth requirements and end-to-end cryptographic correctness.
 */
public class SigningMcpTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";
	private static final AString TEST_PASSPHRASE = Strings.create("test-passphrase-123");
	private static final AString VALUE_HELLO = Strings.create("68656c6c6f");

	// Identities for test isolation
	private static final AString ALICE = Strings.create("did:web:test.example.com:user:alice");
	private static final AString BOB = Strings.create("did:web:test.example.com:user:bob");

	// Peer-signed JWTs for the test identities
	private static final PeerAuth PEER_AUTH = new PeerAuth(KP);
	private static final String ALICE_JWT = PEER_AUTH.issuePeerToken(ALICE, 3600).toString();
	private static final String BOB_JWT = PEER_AUTH.issuePeerToken(BOB, 3600).toString();

	// ===== signingServiceInfo =====

	@Test
	public void testSigningServiceInfo() throws IOException, InterruptedException {
		// No auth required
		AMap<AString, ACell> response = makeToolCall("signingServiceInfo", Maps.empty());
		AMap<AString, ACell> structured = expectResult(response);
		assertEquals(CVMBool.TRUE, RT.getIn(structured, "available"));
	}

	// ===== signingCreateKey + signingListKeys =====

	@Test
	public void testCreateKeyAndListKeys() throws IOException, InterruptedException {
		AMap<AString, ACell> createArgs = Maps.of("passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> createResponse = makeAuthToolCall("signingCreateKey", createArgs, ALICE_JWT);
		AMap<AString, ACell> createResult = expectResult(createResponse);

		AString publicKeyHex = RT.getIn(createResult, "publicKey");
		assertNotNull(publicKeyHex, "createKey should return publicKey");
		assertTrue(publicKeyHex.toString().startsWith("0x"), "Public key should be hex");

		// List keys — should contain the created key
		AMap<AString, ACell> listResponse = makeAuthToolCall("signingListKeys", Maps.empty(), ALICE_JWT);
		AMap<AString, ACell> listResult = expectResult(listResponse);

		AVector<ACell> keys = RT.ensureVector(listResult.get(Strings.create("keys")));
		assertNotNull(keys, "listKeys should return keys array");
		assertTrue(keys.count() >= 1, "Should have at least one key");

		// Verify our key is in the list
		boolean found = false;
		for (long i = 0; i < keys.count(); i++) {
			if (publicKeyHex.equals(keys.get(i))) found = true;
		}
		assertTrue(found, "Created key should appear in listKeys");
	}

	@Test
	public void testCreateMultipleKeys() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("passphrase", Strings.create("multi-key-pass"));

		AMap<AString, ACell> r1 = makeAuthToolCall("signingCreateKey", args, ALICE_JWT);
		AString pk1 = RT.getIn(expectResult(r1), "publicKey");

		AMap<AString, ACell> r2 = makeAuthToolCall("signingCreateKey", args, ALICE_JWT);
		AString pk2 = RT.getIn(expectResult(r2), "publicKey");

		assertNotEquals(pk1, pk2, "Each createKey should generate a different key");
	}

	// ===== signingSign =====

	@Test
	public void testSignWithStoredKey() throws IOException, InterruptedException {
		// Create a key
		AMap<AString, ACell> createArgs = Maps.of("passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", createArgs, ALICE_JWT));
		AString publicKeyHex = RT.getIn(createResult, "publicKey");

		// Sign data
		AMap<AString, ACell> signArgs = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"value", VALUE_HELLO
		);
		AMap<AString, ACell> signResult = expectResult(
			makeAuthToolCall("signingSign", signArgs, ALICE_JWT));

		AString signatureHex = RT.getIn(signResult, "signature");
		assertNotNull(signatureHex, "Should return a signature");

		// Verify signature externally
		AccountKey pk = AccountKey.parse(publicKeyHex.toString());
		Blob sigBlob = Blob.parse(signatureHex.toString());
		Blob payload = Blob.parse(VALUE_HELLO.toString());
		assertNotNull(sigBlob);

		ASignature sig = convex.core.crypto.Ed25519Signature.fromBlob(sigBlob);
		assertTrue(Providers.verify(sig, payload, pk), "Signature should verify externally");
	}

	@Test
	public void testSignWrongPassphrase() throws IOException, InterruptedException {
		AMap<AString, ACell> createArgs = Maps.of("passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", createArgs, ALICE_JWT));
		AString publicKeyHex = RT.getIn(createResult, "publicKey");

		// Sign with wrong passphrase
		AMap<AString, ACell> signArgs = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", Strings.create("wrong-passphrase"),
			"value", VALUE_HELLO
		);
		AMap<AString, ACell> signResponse = makeAuthToolCall("signingSign", signArgs, ALICE_JWT);
		AMap<AString, ACell> error = expectError(signResponse);
		assertNotNull(error, "Wrong passphrase should return error");
	}

	// ===== signingGetJWT =====

	@Test
	public void testGetSelfSignedJWT() throws IOException, InterruptedException {
		AMap<AString, ACell> createArgs = Maps.of("passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", createArgs, ALICE_JWT));
		AString publicKeyHex = RT.getIn(createResult, "publicKey");

		// Get JWT
		AMap<AString, ACell> jwtArgs = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"lifetime", CVMLong.create(600)
		);
		AMap<AString, ACell> jwtResult = expectResult(
			makeAuthToolCall("signingGetJWT", jwtArgs, ALICE_JWT));

		AString jwt = RT.getIn(jwtResult, "jwt");
		assertNotNull(jwt, "Should return a JWT");

		// Verify JWT externally
		AccountKey pk = AccountKey.parse(publicKeyHex.toString());
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt, pk);
		assertNotNull(claims, "JWT should verify with the signing key");

		// Check sub claim is did:key for the public key
		AString sub = RT.ensureString(claims.get(Strings.create("sub")));
		assertNotNull(sub);
		assertTrue(sub.toString().startsWith("did:key:"), "sub should be did:key");
	}

	@Test
	public void testGetJWTWithAudience() throws IOException, InterruptedException {
		AMap<AString, ACell> createArgs = Maps.of("passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", createArgs, ALICE_JWT));
		AString publicKeyHex = RT.getIn(createResult, "publicKey");

		AMap<AString, ACell> jwtArgs = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"audience", Strings.create("https://example.com")
		);
		AMap<AString, ACell> jwtResult = expectResult(
			makeAuthToolCall("signingGetJWT", jwtArgs, ALICE_JWT));

		AString jwt = RT.getIn(jwtResult, "jwt");
		assertNotNull(jwt);

		// Verify aud claim
		AccountKey pk = AccountKey.parse(publicKeyHex.toString());
		AMap<AString, ACell> claims = JWT.verifyPublic(jwt, pk);
		assertNotNull(claims);
		AString aud = RT.ensureString(claims.get(Strings.create("aud")));
		assertEquals(Strings.create("https://example.com"), aud);
	}

	// ===== Auth requirements =====

	@Test
	public void testCreateKeyNoAuth() throws IOException, InterruptedException {
		// No auth header — should get error
		AMap<AString, ACell> args = Maps.of("passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> response = makeToolCall("signingCreateKey", args);
		AMap<AString, ACell> error = expectError(response);
		assertNotNull(error, "No auth should return error");
	}

	@Test
	public void testListKeysNoAuth() throws IOException, InterruptedException {
		AMap<AString, ACell> response = makeToolCall("signingListKeys", Maps.empty());
		AMap<AString, ACell> error = expectError(response);
		assertNotNull(error, "No auth should return error");
	}

	@Test
	public void testSignNoAuth() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of(
			"publicKey", Strings.create("0x0000000000000000000000000000000000000000000000000000000000000000"),
			"passphrase", TEST_PASSPHRASE,
			"value", VALUE_HELLO
		);
		AMap<AString, ACell> response = makeToolCall("signingSign", args);
		AMap<AString, ACell> error = expectError(response);
		assertNotNull(error, "No auth should return error");
	}

	// ===== Identity compartmentalisation =====

	@Test
	public void testCompartmentalisedIdentities() throws IOException, InterruptedException {
		// Alice creates a key
		AMap<AString, ACell> createArgs = Maps.of("passphrase", Strings.create("compartment-pass"));
		AMap<AString, ACell> aliceResult = expectResult(
			makeAuthToolCall("signingCreateKey", createArgs, ALICE_JWT));
		AString aliceKey = RT.getIn(aliceResult, "publicKey");

		// Bob should not see Alice's keys
		AMap<AString, ACell> bobList = expectResult(
			makeAuthToolCall("signingListKeys", Maps.empty(), BOB_JWT));
		AVector<ACell> bobKeys = RT.ensureVector(bobList.get(Strings.create("keys")));
		assertNotNull(bobKeys);

		// Alice's key should not be in Bob's list
		boolean found = false;
		for (long i = 0; i < bobKeys.count(); i++) {
			if (aliceKey.equals(bobKeys.get(i))) found = true;
		}
		assertFalse(found, "Bob should not see Alice's keys");

		// Bob cannot sign with Alice's key (different identity → different lookup hash)
		AMap<AString, ACell> signArgs = Maps.of(
			"publicKey", aliceKey,
			"passphrase", Strings.create("compartment-pass"),
			"value", VALUE_HELLO
		);
		AMap<AString, ACell> signResponse = makeAuthToolCall("signingSign", signArgs, BOB_JWT);
		AMap<AString, ACell> signError = expectError(signResponse);
		assertNotNull(signError, "Bob should not be able to sign with Alice's key");
	}

	// ===== Helpers =====

	private AMap<AString, ACell> makeToolCall(String toolName, AMap<AString, ACell> arguments)
			throws IOException, InterruptedException {
		String id = "test-" + toolName;
		AMap<AString, ACell> params = Maps.of(
			"name", toolName,
			"arguments", arguments
		);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0",
			"method", "tools/call",
			"params", params,
			"id", id
		);

		HttpResponse<String> response = post(MCP_PATH, JSON.toString(request));
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, () -> "Expected map response but got " + RT.getType(parsed));
		return RT.ensureMap(parsed);
	}

	private AMap<AString, ACell> makeAuthToolCall(String toolName, AMap<AString, ACell> arguments, String bearerToken)
			throws IOException, InterruptedException {
		String id = "test-" + toolName;
		AMap<AString, ACell> params = Maps.of(
			"name", toolName,
			"arguments", arguments
		);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0",
			"method", "tools/call",
			"params", params,
			"id", id
		);

		HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(MCP_PATH))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + bearerToken)
				.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(request)))
				.build();
		HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, () -> "Expected map response but got " + RT.getType(parsed));
		return RT.ensureMap(parsed);
	}

	private AMap<AString, ACell> expectResult(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpProtocol.FIELD_ERROR), () -> "Unexpected protocol error: " + responseMap);
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, () -> "RPC result missing in: " + responseMap);
		assertEquals(CVMBool.FALSE, result.get(McpProtocol.FIELD_IS_ERROR), () -> "Unexpected failure: " + responseMap);
		AMap<AString, ACell> structured = RT.ensureMap(result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}

	private AMap<AString, ACell> expectError(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpProtocol.FIELD_ERROR), () -> "Unexpected protocol error: " + responseMap);
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
		AMap<AString, ACell> structured = RT.ensureMap(result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}
}
