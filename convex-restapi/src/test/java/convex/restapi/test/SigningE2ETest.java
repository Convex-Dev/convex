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
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.json.JWT;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.auth.PeerAuth;
import convex.restapi.mcp.McpAPI;

/**
 * End-to-end integration tests (Stage 13).
 *
 * <p>Each test exercises a multi-step workflow through the full HTTP/MCP stack:
 * auth middleware, signing service, on-chain execution, and JWT verification.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Self-issued JWT → createKey → sign → verify signature</li>
 *   <li>Auth → createAccount → transact → verify on-chain result</li>
 *   <li>Auth → createKey → getSelfSignedJWT → use that JWT for auth</li>
 *   <li>Export key (with confirmation) → import on different identity</li>
 *   <li>Two identities have independent, isolated key stores</li>
 *   <li>Account creation → deploy contract → call contract function</li>
 * </ol>
 */
public class SigningE2ETest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";
	private static final PeerAuth PEER_AUTH = new PeerAuth(KP);

	// Unique identities for E2E tests to avoid collisions with other test classes
	private static final AString E2E_ALICE = Strings.create("did:web:test.example.com:user:e2e-alice");
	private static final AString E2E_BOB = Strings.create("did:web:test.example.com:user:e2e-bob");
	private static final String ALICE_JWT = PEER_AUTH.issuePeerToken(E2E_ALICE, 3600).toString();
	private static final String BOB_JWT = PEER_AUTH.issuePeerToken(E2E_BOB, 3600).toString();
	private static final AString PASS_A = Strings.create("e2e-pass-alice-001");
	private static final AString PASS_B = Strings.create("e2e-pass-bob-002");

	// ===== Scenario 1: Self-issued JWT auth → createKey → sign → verify =====

	@Test
	public void testSelfIssuedJwtCreateKeySignVerify() throws IOException, InterruptedException {
		// Generate a client-side Ed25519 keypair for self-issued JWT
		AKeyPair clientKP = AKeyPair.generate();
		AccountKey clientPK = clientKP.getAccountKey();
		AString clientDID = Strings.create("did:key:" + Multikey.encodePublicKey(clientPK));

		// Build self-issued JWT
		long now = System.currentTimeMillis() / 1000;
		AMap<AString, ACell> claims = Maps.of(
			"sub", clientDID,
			"iss", clientDID,
			"iat", CVMLong.create(now),
			"exp", CVMLong.create(now + 3600)
		);
		String selfJWT = JWT.signPublic(claims, clientKP).toString();

		// Use self-issued JWT to create a key in signing service
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", PASS_A), selfJWT));
		AString signingPK = RT.ensureString(createResult.get(Strings.create("publicKey")));
		assertNotNull(signingPK, "Should return publicKey");

		// Sign data using the signing service key
		AString testData = Strings.create("48656c6c6f20576f726c64"); // "Hello World" hex
		AMap<AString, ACell> signResult = expectResult(
			makeAuthToolCall("signingSign", Maps.of(
				"publicKey", signingPK,
				"passphrase", PASS_A,
				"value", testData
			), selfJWT));
		AString signature = RT.ensureString(signResult.get(Strings.create("signature")));
		assertNotNull(signature, "Should return signature");

		// Verify signature using the validate tool (no auth required)
		AMap<AString, ACell> validateResult = expectResult(
			makeToolCall("validate", Maps.of(
				"publicKey", signingPK,
				"signature", signature,
				"bytes", testData
			)));
		assertEquals(CVMBool.TRUE, validateResult.get(Strings.create("value")),
			"Signature should verify correctly");
	}

	// ===== Scenario 2: createAccount → transact → verify on-chain =====

	@Test
	public void testCreateAccountTransactVerifyOnChain() throws IOException, InterruptedException {
		// Create funded account
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateAccount", Maps.of(
				"passphrase", PASS_A,
				"faucet", CVMLong.create(1_000_000_000L)
			), ALICE_JWT));
		long address = ((CVMLong) createResult.get(Strings.create("address"))).longValue();
		String addrStr = "#" + address;

		// Execute (def greeting "Hello Convex") on-chain
		AMap<AString, ACell> txResult = expectResult(
			makeAuthToolCall("signingTransact", Maps.of(
				"source", Strings.create("(def greeting \"Hello Convex\")"),
				"address", Strings.create(addrStr),
				"passphrase", PASS_A
			), ALICE_JWT));
		// def returns the value
		AString txValue = RT.ensureString(txResult.get(McpAPI.KEY_VALUE));
		assertNotNull(txValue);

		// Verify on-chain using query tool (no auth needed)
		AMap<AString, ACell> queryResult = expectResult(
			makeToolCall("query", Maps.of(
				"source", Strings.create("greeting"),
				"address", Strings.create(addrStr)
			)));
		AString queryValue = RT.ensureString(queryResult.get(McpAPI.KEY_VALUE));
		assertEquals(Strings.create("Hello Convex"), queryValue,
			"On-chain value should match what was set");
	}

	// ===== Scenario 3: createKey → getSelfSignedJWT → use JWT for auth =====

	@Test
	public void testJwtChainSigningServiceJwtUsedForAuth() throws IOException, InterruptedException {
		// Alice creates a key in the signing service
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", PASS_A), ALICE_JWT));
		AString publicKey = RT.ensureString(createResult.get(Strings.create("publicKey")));
		assertNotNull(publicKey);

		// Alice gets a self-issued JWT from the signing service key
		AMap<AString, ACell> jwtResult = expectResult(
			makeAuthToolCall("signingGetJWT", Maps.of(
				"publicKey", publicKey,
				"passphrase", PASS_A,
				"lifetime", CVMLong.create(3600)
			), ALICE_JWT));
		String signingJWT = RT.ensureString(jwtResult.get(Strings.create("jwt"))).toString();
		assertNotNull(signingJWT);

		// The signing service JWT is a self-issued did:key JWT.
		// Use it directly as a bearer token for a new request.
		// This should authenticate as the did:key identity of the signing key.
		AMap<AString, ACell> listResult = expectResult(
			makeAuthToolCall("signingListKeys", Maps.empty(), signingJWT));
		AVector<ACell> keys = RT.ensureVector(listResult.get(Strings.create("keys")));
		assertNotNull(keys, "Should be able to list keys using signing-service-issued JWT");

		// The did:key identity is different from Alice's did:web identity,
		// so this is a fresh identity with no keys of its own yet.
		// But it proves the JWT chain works: peer-issued JWT → signing service → self-issued JWT → auth.
	}

	// ===== Scenario 4: Export with confirmation → import on different identity =====

	@Test
	public void testExportImportAcrossIdentities() throws IOException, InterruptedException {
		// Alice creates a key
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", PASS_A), ALICE_JWT));
		AString aliceKey = RT.ensureString(createResult.get(Strings.create("publicKey")));

		// Alice exports the key (elevated: requires confirmation)
		AMap<AString, ACell> exportArgs = Maps.of("publicKey", aliceKey, "passphrase", PASS_A);
		AMap<AString, ACell> exportResult = expectResult(
			makeAuthToolCall("signingExportKey", exportArgs, ALICE_JWT));
		AString confirmToken = RT.ensureString(exportResult.get(Strings.create("confirmToken")));
		AString confirmUrl = RT.ensureString(exportResult.get(Strings.create("confirmUrl")));
		assertNotNull(confirmToken);

		// Approve confirmation
		postForm(confirmUrl.toString());

		// Retry export with confirmation token
		AMap<AString, ACell> retryArgs = Maps.of(
			"publicKey", aliceKey, "passphrase", PASS_A, "confirmToken", confirmToken);
		AMap<AString, ACell> retryResult = expectResult(
			makeAuthToolCall("signingExportKey", retryArgs, ALICE_JWT));
		AString seed = RT.ensureString(retryResult.get(Strings.create("seed")));
		assertNotNull(seed, "Export should return the seed");

		// Bob imports the same key with his own passphrase (elevated: requires confirmation)
		AMap<AString, ACell> importArgs = Maps.of("seed", seed, "passphrase", PASS_B);
		AMap<AString, ACell> importResult = expectResult(
			makeAuthToolCall("signingImportKey", importArgs, BOB_JWT));
		AString importConfirmToken = RT.ensureString(importResult.get(Strings.create("confirmToken")));
		AString importConfirmUrl = RT.ensureString(importResult.get(Strings.create("confirmUrl")));

		// Approve
		postForm(importConfirmUrl.toString());

		// Retry import
		AMap<AString, ACell> importRetryArgs = Maps.of(
			"seed", seed, "passphrase", PASS_B, "confirmToken", importConfirmToken);
		AMap<AString, ACell> importRetryResult = expectResult(
			makeAuthToolCall("signingImportKey", importRetryArgs, BOB_JWT));
		AString bobKey = RT.ensureString(importRetryResult.get(Strings.create("publicKey")));
		assertEquals(aliceKey.toString(), bobKey.toString(),
			"Imported key should have same public key");

		// Bob can sign with his passphrase
		AMap<AString, ACell> bobSignResult = expectResult(
			makeAuthToolCall("signingSign", Maps.of(
				"publicKey", bobKey,
				"passphrase", PASS_B,
				"value", Strings.create("cafebabe")
			), BOB_JWT));
		assertNotNull(RT.ensureString(bobSignResult.get(Strings.create("signature"))));

		// Bob cannot use Alice's passphrase
		AMap<AString, ACell> bobWrongPass = expectError(
			makeAuthToolCall("signingSign", Maps.of(
				"publicKey", bobKey,
				"passphrase", PASS_A,
				"value", Strings.create("cafebabe")
			), BOB_JWT));
		assertNotNull(bobWrongPass);
	}

	// ===== Scenario 5: Two identities have isolated key stores =====

	@Test
	public void testIdentityIsolation() throws IOException, InterruptedException {
		// Alice creates a key
		AMap<AString, ACell> aliceCreate = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", PASS_A), ALICE_JWT));
		AString aliceKey = RT.ensureString(aliceCreate.get(Strings.create("publicKey")));

		// Bob creates a key
		AMap<AString, ACell> bobCreate = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", PASS_B), BOB_JWT));
		AString bobKey = RT.ensureString(bobCreate.get(Strings.create("publicKey")));

		// Alice lists keys — should see her key but not Bob's
		AMap<AString, ACell> aliceList = expectResult(
			makeAuthToolCall("signingListKeys", Maps.empty(), ALICE_JWT));
		AVector<ACell> aliceKeys = RT.ensureVector(aliceList.get(Strings.create("keys")));
		assertTrue(containsKey(aliceKeys, aliceKey), "Alice should see her own key");
		assertFalse(containsKey(aliceKeys, bobKey), "Alice should NOT see Bob's key");

		// Bob lists keys — should see his key but not Alice's
		AMap<AString, ACell> bobList = expectResult(
			makeAuthToolCall("signingListKeys", Maps.empty(), BOB_JWT));
		AVector<ACell> bobKeys = RT.ensureVector(bobList.get(Strings.create("keys")));
		assertTrue(containsKey(bobKeys, bobKey), "Bob should see his own key");
		assertFalse(containsKey(bobKeys, aliceKey), "Bob should NOT see Alice's key");

		// Alice cannot sign with Bob's key
		AMap<AString, ACell> crossSign = expectError(
			makeAuthToolCall("signingSign", Maps.of(
				"publicKey", bobKey,
				"passphrase", PASS_B,
				"value", Strings.create("deadbeef")
			), ALICE_JWT));
		assertNotNull(crossSign, "Cross-identity signing should fail");
	}

	// ===== Scenario 6: Create account → deploy actor → interact with actor =====

	@Test
	public void testDeployActorAndInteract() throws IOException, InterruptedException {
		// Create funded account
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateAccount", Maps.of(
				"passphrase", PASS_A,
				"faucet", CVMLong.create(1_000_000_000L)
			), ALICE_JWT));
		long address = ((CVMLong) createResult.get(Strings.create("address"))).longValue();
		String addrStr = "#" + address;

		// Deploy a simple actor that stores a value
		AMap<AString, ACell> deployResult = expectResult(
			makeAuthToolCall("signingTransact", Maps.of(
				"source", Strings.create("(deploy '(do (def greeting \"hello from actor\")))"),
				"address", Strings.create(addrStr),
				"passphrase", PASS_A
			), ALICE_JWT));
		// deploy returns the new actor address
		ACell deployValue = deployResult.get(McpAPI.KEY_VALUE);
		assertNotNull(deployValue, "Deploy should return actor address");
		String actorAddr = "#" + deployValue.toString();

		// Verify the actor exists using describeAccount tool
		AMap<AString, ACell> describeResult = expectResult(
			makeToolCall("describeAccount", Maps.of(
				"address", Strings.create(actorAddr)
			)));
		assertNotNull(describeResult, "Should describe the deployed actor");

		// Look up the greeting symbol on the actor using lookup tool
		AMap<AString, ACell> lookupResult = expectResult(
			makeToolCall("lookup", Maps.of(
				"address", Strings.create(actorAddr),
				"symbol", Strings.create("greeting")
			)));
		assertEquals(CVMBool.TRUE, lookupResult.get(Strings.create("exists")),
			"greeting symbol should exist on actor");
		AString lookupValue = RT.ensureString(lookupResult.get(Strings.create("value")));
		assertNotNull(lookupValue, "Should have a value");
		assertTrue(lookupValue.toString().contains("hello from actor"),
			"Actor value should match deployed definition");

		// Execute another transaction that modifies state in the user account
		AMap<AString, ACell> txResult = expectResult(
			makeAuthToolCall("signingTransact", Maps.of(
				"source", Strings.create("(def my-actor " + actorAddr + ")"),
				"address", Strings.create(addrStr),
				"passphrase", PASS_A
			), ALICE_JWT));
		assertNotNull(txResult);

		// Query back the user's reference to the actor
		AMap<AString, ACell> queryResult = expectResult(
			makeToolCall("query", Maps.of(
				"source", Strings.create("my-actor"),
				"address", Strings.create(addrStr)
			)));
		assertNotNull(queryResult.get(McpAPI.KEY_VALUE),
			"User should have reference to deployed actor");
	}

	// ===== Helpers =====

	private boolean containsKey(AVector<ACell> keys, AString target) {
		if (keys == null) return false;
		for (long i = 0; i < keys.count(); i++) {
			if (target.equals(keys.get(i))) return true;
		}
		return false;
	}

	private AMap<AString, ACell> makeToolCall(String toolName, AMap<AString, ACell> arguments)
			throws IOException, InterruptedException {
		AMap<AString, ACell> params = Maps.of("name", toolName, "arguments", arguments);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0", "method", "tools/call", "params", params, "id", "e2e-" + toolName);
		HttpResponse<String> response = post(MCP_PATH, JSON.toString(request));
		assertEquals(200, response.statusCode());
		return RT.ensureMap(JSON.parse(response.body()));
	}

	private AMap<AString, ACell> makeAuthToolCall(String toolName, AMap<AString, ACell> arguments, String bearerToken)
			throws IOException, InterruptedException {
		AMap<AString, ACell> params = Maps.of("name", toolName, "arguments", arguments);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0", "method", "tools/call", "params", params, "id", "e2e-" + toolName);
		HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(MCP_PATH))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + bearerToken)
				.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(request)))
				.build();
		HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		return RT.ensureMap(JSON.parse(response.body()));
	}

	private HttpResponse<String> postForm(String url) throws IOException, InterruptedException {
		HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(""))
				.build();
		return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
	}

	private AMap<AString, ACell> expectResult(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR), () -> "Unexpected protocol error: " + responseMap);
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result, () -> "RPC result missing in: " + responseMap);
		assertEquals(CVMBool.FALSE, result.get(McpAPI.FIELD_IS_ERROR), () -> "Unexpected failure: " + responseMap);
		AMap<AString, ACell> structured = RT.ensureMap(result.get(McpAPI.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}

	private AMap<AString, ACell> expectError(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR), () -> "Unexpected protocol error: " + responseMap);
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpAPI.FIELD_IS_ERROR));
		AMap<AString, ACell> structured = RT.ensureMap(result.get(McpAPI.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}
}
