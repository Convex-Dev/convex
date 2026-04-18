package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.auth.PeerAuth;
import convex.restapi.mcp.McpProtocol;

/**
 * Tests for the signingDelegate MCP tool.
 *
 * Exercises UCAN token creation via the signing service, verifying
 * end-to-end correctness of token structure, signatures, and chain validation.
 */
public class DelegateMcpTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";
	private static final AString TEST_PASSPHRASE = Strings.create("delegate-test-pass");

	// Test identities
	private static final AString ALICE = Strings.create("did:web:test.example.com:user:delegate-alice");
	private static final AString BOB = Strings.create("did:web:test.example.com:user:delegate-bob");

	// Peer-signed JWTs for the test identities
	private static final PeerAuth PEER_AUTH = new PeerAuth(KP);
	private static final String ALICE_JWT = PEER_AUTH.issuePeerToken(ALICE, 3600).toString();
	private static final String BOB_JWT = PEER_AUTH.issuePeerToken(BOB, 3600).toString();

	// Audience key for delegation targets
	private static final AKeyPair DELEGATE_KP = AKeyPair.createSeeded(7001);
	private static final AccountKey DELEGATE_KEY = DELEGATE_KP.getAccountKey();

	private static final long FUTURE_EXPIRY = System.currentTimeMillis() / 1000 + 3600;

	// ===== Root Delegation =====

	@Test
	public void testCreateRootDelegation() throws IOException, InterruptedException {
		// Create a signing key
		AString publicKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		// Create a UCAN delegation token
		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", DELEGATE_KEY.toString(),
			"exp", CVMLong.create(FUTURE_EXPIRY)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", ucanArgs
		);
		AMap<AString, ACell> result = expectResult(
			makeAuthToolCall("signingDelegate", args, ALICE_JWT));

		// Verify response structure
		AMap<AString, ACell> tokenMap = RT.ensureMap(result.get(Strings.create("token")));
		assertNotNull(tokenMap, "Response should contain token map");

		AString cad3 = RT.ensureString(result.get(Strings.create("cad3")));
		assertNotNull(cad3, "Response should contain cad3 encoding");

		AString hash = RT.ensureString(result.get(Strings.create("hash")));
		assertNotNull(hash, "Response should contain hash");

		// Parse and verify the token
		UCAN ucan = UCAN.parse(tokenMap);
		assertNotNull(ucan, "Token should parse successfully");
		assertTrue(ucan.verifySignature(), "Token signature should verify");
		assertEquals(FUTURE_EXPIRY, ucan.getExpiry());
		assertEquals(DELEGATE_KEY, ucan.getAudienceKey());
	}

	// ===== Audience Format Tests =====

	@Test
	public void testDelegateWithHexAud() throws IOException, InterruptedException {
		AString publicKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		// Pass aud as hex public key (no did:key: prefix)
		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", Strings.create(DELEGATE_KEY.toString()),
			"exp", CVMLong.create(FUTURE_EXPIRY)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", ucanArgs
		);
		AMap<AString, ACell> result = expectResult(
			makeAuthToolCall("signingDelegate", args, ALICE_JWT));

		AMap<AString, ACell> tokenMap = RT.ensureMap(result.get(Strings.create("token")));
		UCAN ucan = UCAN.parse(tokenMap);
		assertNotNull(ucan);
		assertEquals(DELEGATE_KEY, ucan.getAudienceKey());
	}

	@Test
	public void testDelegateWithDIDKeyAud() throws IOException, InterruptedException {
		AString publicKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		// Pass aud as did:key string
		AString didKey = UCAN.toDIDKey(DELEGATE_KEY);
		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", didKey,
			"exp", CVMLong.create(FUTURE_EXPIRY)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", ucanArgs
		);
		AMap<AString, ACell> result = expectResult(
			makeAuthToolCall("signingDelegate", args, ALICE_JWT));

		AMap<AString, ACell> tokenMap = RT.ensureMap(result.get(Strings.create("token")));
		UCAN ucan = UCAN.parse(tokenMap);
		assertNotNull(ucan);
		assertEquals(DELEGATE_KEY, ucan.getAudienceKey());
	}

	// ===== Optional Fields =====

	@Test
	public void testDelegateWithCapabilities() throws IOException, InterruptedException {
		AString publicKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		AMap<AString, ACell> cap = Capability.create(
			Capability.resourceURI("account", 42),
			Capability.CONVEX_TRANSFER
		);
		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", DELEGATE_KEY.toString(),
			"exp", CVMLong.create(FUTURE_EXPIRY),
			"att", Vectors.of(cap)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", ucanArgs
		);
		AMap<AString, ACell> result = expectResult(
			makeAuthToolCall("signingDelegate", args, ALICE_JWT));

		AMap<AString, ACell> tokenMap = RT.ensureMap(result.get(Strings.create("token")));
		UCAN ucan = UCAN.parse(tokenMap);
		assertNotNull(ucan);
		assertTrue(ucan.verifySignature());
		assertEquals(1, ucan.getCapabilities().count());
	}

	@Test
	public void testDelegateWithNotBefore() throws IOException, InterruptedException {
		AString publicKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		long nbf = FUTURE_EXPIRY - 1800; // 30 minutes before expiry
		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", DELEGATE_KEY.toString(),
			"exp", CVMLong.create(FUTURE_EXPIRY),
			"nbf", CVMLong.create(nbf)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", ucanArgs
		);
		AMap<AString, ACell> result = expectResult(
			makeAuthToolCall("signingDelegate", args, ALICE_JWT));

		AMap<AString, ACell> tokenMap = RT.ensureMap(result.get(Strings.create("token")));
		UCAN ucan = UCAN.parse(tokenMap);
		assertNotNull(ucan);
		assertNotNull(ucan.getNotBefore());
		assertEquals(nbf, ucan.getNotBefore().longValue());
	}

	// ===== Error Cases =====

	@Test
	public void testDelegateNoAuth() throws IOException, InterruptedException {
		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", DELEGATE_KEY.toString(),
			"exp", CVMLong.create(FUTURE_EXPIRY)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", Strings.create("0x0000000000000000000000000000000000000000000000000000000000000000"),
			"passphrase", TEST_PASSPHRASE,
			"ucan", ucanArgs
		);
		AMap<AString, ACell> response = makeToolCall("signingDelegate", args);
		AMap<AString, ACell> error = expectError(response);
		assertNotNull(error, "No auth should return error");
	}

	@Test
	public void testDelegateWrongPassphrase() throws IOException, InterruptedException {
		AString publicKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", DELEGATE_KEY.toString(),
			"exp", CVMLong.create(FUTURE_EXPIRY)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", Strings.create("wrong-passphrase"),
			"ucan", ucanArgs
		);
		AMap<AString, ACell> response = makeAuthToolCall("signingDelegate", args, ALICE_JWT);
		AMap<AString, ACell> error = expectError(response);
		assertNotNull(error, "Wrong passphrase should return error");
	}

	@Test
	public void testDelegateInvalidAud() throws IOException, InterruptedException {
		AString publicKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		AMap<AString, ACell> ucanArgs = Maps.of(
			"aud", Strings.create("not-a-valid-key"),
			"exp", CVMLong.create(FUTURE_EXPIRY)
		);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", ucanArgs
		);
		AMap<AString, ACell> response = makeAuthToolCall("signingDelegate", args, ALICE_JWT);
		AMap<AString, ACell> error = expectError(response);
		assertNotNull(error, "Invalid aud should return error");
	}

	// ===== Chain Delegation =====

	@Test
	public void testDelegateChainDelegation() throws IOException, InterruptedException {
		// Alice creates a signing key
		AString aliceKeyHex = createSigningKey(ALICE_JWT, TEST_PASSPHRASE);

		// Bob creates a signing key
		AString bobKeyHex = createSigningKey(BOB_JWT, TEST_PASSPHRASE);
		AccountKey bobKey = AccountKey.parse(bobKeyHex.toString());

		// Alice delegates to Bob
		AMap<AString, ACell> rootUcanArgs = Maps.of(
			"aud", bobKey.toString(),
			"exp", CVMLong.create(FUTURE_EXPIRY)
		);
		AMap<AString, ACell> rootArgs = Maps.of(
			"publicKey", aliceKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", rootUcanArgs
		);
		AMap<AString, ACell> rootResult = expectResult(
			makeAuthToolCall("signingDelegate", rootArgs, ALICE_JWT));
		AMap<AString, ACell> rootTokenMap = RT.ensureMap(rootResult.get(Strings.create("token")));
		assertNotNull(rootTokenMap);

		// Bob sub-delegates to DELEGATE_KEY, with root token as proof
		AMap<AString, ACell> childUcanArgs = Maps.of(
			"aud", DELEGATE_KEY.toString(),
			"exp", CVMLong.create(FUTURE_EXPIRY),
			"prf", Vectors.of(rootTokenMap)
		);
		AMap<AString, ACell> childArgs = Maps.of(
			"publicKey", bobKeyHex,
			"passphrase", TEST_PASSPHRASE,
			"ucan", childUcanArgs
		);
		AMap<AString, ACell> childResult = expectResult(
			makeAuthToolCall("signingDelegate", childArgs, BOB_JWT));
		AMap<AString, ACell> childTokenMap = RT.ensureMap(childResult.get(Strings.create("token")));
		assertNotNull(childTokenMap);

		// Verify the chain validates
		UCAN childUcan = UCAN.parse(childTokenMap);
		assertNotNull(childUcan);
		assertTrue(childUcan.verifySignature());

		long now = System.currentTimeMillis() / 1000;
		assertNotNull(UCANValidator.validate(childUcan, now), "Chain should validate");
	}

	// ===== Helpers =====

	private AString createSigningKey(String jwt, AString passphrase)
			throws IOException, InterruptedException {
		AMap<AString, ACell> createArgs = Maps.of("passphrase", passphrase);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", createArgs, jwt));
		AString publicKeyHex = RT.getIn(createResult, "publicKey");
		assertNotNull(publicKeyHex, "createKey should return publicKey");
		return publicKeyHex;
	}

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
