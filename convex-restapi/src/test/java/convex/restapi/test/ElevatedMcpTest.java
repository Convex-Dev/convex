package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.auth.PeerAuth;
import convex.restapi.mcp.McpAPI;
import convex.restapi.mcp.McpProtocol;

/**
 * Tests for elevated MCP signing tools (Stage 10).
 *
 * Exercises the signingImportKey, signingExportKey, signingDeleteKey,
 * and signingChangePassphrase tools with the confirmation flow.
 */
public class ElevatedMcpTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";
	private static final AString TEST_PASSPHRASE = Strings.create("elevated-test-pass");

	private static final AString ALICE = Strings.create("did:web:test.example.com:user:alice-elevated");
	private static final PeerAuth PEER_AUTH = new PeerAuth(KP);
	private static final String ALICE_JWT = PEER_AUTH.issuePeerToken(ALICE, 3600).toString();

	// ===== Confirmation flow for exportKey =====

	@Test
	public void testExportKeyConfirmationFlow() throws IOException, InterruptedException {
		// Create a key first
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", TEST_PASSPHRASE), ALICE_JWT));
		AString publicKey = RT.getIn(createResult, "publicKey");
		assertNotNull(publicKey);

		// Call exportKey without confirmToken — should get confirmation_required
		AMap<AString, ACell> exportArgs = Maps.of(
			"publicKey", publicKey,
			"passphrase", TEST_PASSPHRASE
		);
		AMap<AString, ACell> exportResponse = makeAuthToolCall("signingExportKey", exportArgs, ALICE_JWT);
		AMap<AString, ACell> exportResult = expectResult(exportResponse);

		AString status = RT.ensureString(exportResult.get(Strings.create("status")));
		assertEquals("confirmation_required", status.toString());

		AString confirmToken = RT.ensureString(exportResult.get(Strings.create("confirmToken")));
		assertNotNull(confirmToken);
		assertTrue(confirmToken.toString().startsWith("ct_"), "Token should start with ct_");

		AString confirmUrl = RT.ensureString(exportResult.get(Strings.create("confirmUrl")));
		assertNotNull(confirmUrl);
		assertTrue(confirmUrl.toString().contains("/confirm?token="), "Should contain confirm URL");

		// Approve the confirmation via POST /confirm
		HttpResponse<String> approveResponse = postForm(confirmUrl.toString());
		assertEquals(200, approveResponse.statusCode());

		// Retry exportKey with confirmToken — should succeed
		AMap<AString, ACell> retryArgs = Maps.of(
			"publicKey", publicKey,
			"passphrase", TEST_PASSPHRASE,
			"confirmToken", confirmToken
		);
		AMap<AString, ACell> retryResponse = makeAuthToolCall("signingExportKey", retryArgs, ALICE_JWT);
		AMap<AString, ACell> retryResult = expectResult(retryResponse);

		AString seed = RT.ensureString(retryResult.get(Strings.create("seed")));
		assertNotNull(seed, "Should return the seed after confirmation");
		assertTrue(seed.toString().startsWith("0x"), "Seed should be hex");
	}

	// ===== Import and export round-trip =====

	@Test
	public void testImportKeyConfirmationFlow() throws IOException, InterruptedException {
		// Generate a known keypair
		AKeyPair kp = AKeyPair.generate();
		Blob seed = kp.getSeed();
		AccountKey expectedKey = kp.getAccountKey();

		// Call importKey without confirmToken
		AMap<AString, ACell> importArgs = Maps.of(
			"seed", Strings.create(seed.toString()),
			"passphrase", TEST_PASSPHRASE
		);
		AMap<AString, ACell> importResponse = makeAuthToolCall("signingImportKey", importArgs, ALICE_JWT);
		AMap<AString, ACell> importResult = expectResult(importResponse);

		AString confirmToken = RT.ensureString(importResult.get(Strings.create("confirmToken")));
		assertNotNull(confirmToken);
		AString confirmUrl = RT.ensureString(importResult.get(Strings.create("confirmUrl")));

		// Approve
		postForm(confirmUrl.toString());

		// Retry with confirmToken
		AMap<AString, ACell> retryArgs = Maps.of(
			"seed", Strings.create(seed.toString()),
			"passphrase", TEST_PASSPHRASE,
			"confirmToken", confirmToken
		);
		AMap<AString, ACell> retryResult = expectResult(
			makeAuthToolCall("signingImportKey", retryArgs, ALICE_JWT));

		AString publicKey = RT.ensureString(retryResult.get(Strings.create("publicKey")));
		assertNotNull(publicKey);
		assertEquals(expectedKey.toString(), publicKey.toString(), "Imported key should match");

		// Verify key appears in listKeys
		AMap<AString, ACell> listResult = expectResult(
			makeAuthToolCall("signingListKeys", Maps.empty(), ALICE_JWT));
		AVector<ACell> keys = RT.ensureVector(listResult.get(Strings.create("keys")));
		boolean found = false;
		for (long i = 0; i < keys.count(); i++) {
			if (publicKey.equals(keys.get(i))) found = true;
		}
		assertTrue(found, "Imported key should appear in listKeys");
	}

	// ===== Delete key with confirmation =====

	@Test
	public void testDeleteKeyConfirmationFlow() throws IOException, InterruptedException {
		// Create a key
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", TEST_PASSPHRASE), ALICE_JWT));
		AString publicKey = RT.getIn(createResult, "publicKey");

		// Call deleteKey without confirmToken
		AMap<AString, ACell> deleteArgs = Maps.of(
			"publicKey", publicKey,
			"passphrase", TEST_PASSPHRASE
		);
		AMap<AString, ACell> deleteResult = expectResult(
			makeAuthToolCall("signingDeleteKey", deleteArgs, ALICE_JWT));
		AString confirmToken = RT.ensureString(deleteResult.get(Strings.create("confirmToken")));
		AString confirmUrl = RT.ensureString(deleteResult.get(Strings.create("confirmUrl")));
		assertNotNull(confirmToken);

		// Approve
		postForm(confirmUrl.toString());

		// Retry with confirmToken
		AMap<AString, ACell> retryArgs = Maps.of(
			"publicKey", publicKey,
			"passphrase", TEST_PASSPHRASE,
			"confirmToken", confirmToken
		);
		AMap<AString, ACell> retryResult = expectResult(
			makeAuthToolCall("signingDeleteKey", retryArgs, ALICE_JWT));
		assertEquals(CVMBool.TRUE, retryResult.get(Strings.create("deleted")));

		// Verify key is gone from listKeys
		AMap<AString, ACell> listResult = expectResult(
			makeAuthToolCall("signingListKeys", Maps.empty(), ALICE_JWT));
		AVector<ACell> keys = RT.ensureVector(listResult.get(Strings.create("keys")));
		for (long i = 0; i < keys.count(); i++) {
			assertNotEquals(publicKey, keys.get(i), "Deleted key should not appear in listKeys");
		}
	}

	// ===== Change passphrase with confirmation =====

	@Test
	public void testChangePassphraseConfirmationFlow() throws IOException, InterruptedException {
		AString oldPass = Strings.create("old-pass-change");
		AString newPass = Strings.create("new-pass-change");

		// Create key with old passphrase
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", oldPass), ALICE_JWT));
		AString publicKey = RT.getIn(createResult, "publicKey");

		// Call changePassphrase without confirmToken
		AMap<AString, ACell> changeArgs = Maps.of(
			"publicKey", publicKey,
			"passphrase", oldPass,
			"newPassphrase", newPass
		);
		AMap<AString, ACell> changeResult = expectResult(
			makeAuthToolCall("signingChangePassphrase", changeArgs, ALICE_JWT));
		AString confirmToken = RT.ensureString(changeResult.get(Strings.create("confirmToken")));
		AString confirmUrl = RT.ensureString(changeResult.get(Strings.create("confirmUrl")));
		assertNotNull(confirmToken);

		// Approve
		postForm(confirmUrl.toString());

		// Retry with confirmToken
		AMap<AString, ACell> retryArgs = Maps.of(
			"publicKey", publicKey,
			"passphrase", oldPass,
			"newPassphrase", newPass,
			"confirmToken", confirmToken
		);
		AMap<AString, ACell> retryResult = expectResult(
			makeAuthToolCall("signingChangePassphrase", retryArgs, ALICE_JWT));
		assertEquals(CVMBool.TRUE, retryResult.get(Strings.create("updated")));

		// Sign with new passphrase should work
		AMap<AString, ACell> signResult = expectResult(
			makeAuthToolCall("signingSign", Maps.of(
				"publicKey", publicKey,
				"passphrase", newPass,
				"value", Strings.create("68656c6c6f")
			), ALICE_JWT));
		assertNotNull(RT.ensureString(signResult.get(Strings.create("signature"))));

		// Sign with old passphrase should fail
		AMap<AString, ACell> signOldResult = expectError(
			makeAuthToolCall("signingSign", Maps.of(
				"publicKey", publicKey,
				"passphrase", oldPass,
				"value", Strings.create("68656c6c6f")
			), ALICE_JWT));
		assertNotNull(signOldResult);
	}

	// ===== Elevated ops without auth =====

	@Test
	public void testElevatedOpsNoAuth() throws IOException, InterruptedException {
		// All elevated tools should fail without auth
		AMap<AString, ACell> importResponse = makeToolCall("signingImportKey",
			Maps.of("seed", Strings.create("0x" + "00".repeat(32)), "passphrase", TEST_PASSPHRASE));
		assertNotNull(expectError(importResponse));

		AMap<AString, ACell> exportResponse = makeToolCall("signingExportKey",
			Maps.of("publicKey", Strings.create("0x" + "00".repeat(32)), "passphrase", TEST_PASSPHRASE));
		assertNotNull(expectError(exportResponse));

		AMap<AString, ACell> deleteResponse = makeToolCall("signingDeleteKey",
			Maps.of("publicKey", Strings.create("0x" + "00".repeat(32)), "passphrase", TEST_PASSPHRASE));
		assertNotNull(expectError(deleteResponse));

		AMap<AString, ACell> changeResponse = makeToolCall("signingChangePassphrase",
			Maps.of("publicKey", Strings.create("0x" + "00".repeat(32)),
				"passphrase", TEST_PASSPHRASE, "newPassphrase", Strings.create("new")));
		assertNotNull(expectError(changeResponse));
	}

	// ===== Reused confirmToken =====

	@Test
	public void testReusedConfirmToken() throws IOException, InterruptedException {
		// Create a key
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", TEST_PASSPHRASE), ALICE_JWT));
		AString publicKey = RT.getIn(createResult, "publicKey");

		// Get confirmation for exportKey
		AMap<AString, ACell> exportArgs = Maps.of("publicKey", publicKey, "passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> exportResult = expectResult(
			makeAuthToolCall("signingExportKey", exportArgs, ALICE_JWT));
		AString confirmToken = RT.ensureString(exportResult.get(Strings.create("confirmToken")));
		AString confirmUrl = RT.ensureString(exportResult.get(Strings.create("confirmUrl")));

		// Approve
		postForm(confirmUrl.toString());

		// First use — should succeed
		AMap<AString, ACell> firstRetry = Maps.of(
			"publicKey", publicKey, "passphrase", TEST_PASSPHRASE, "confirmToken", confirmToken);
		AMap<AString, ACell> firstResult = expectResult(
			makeAuthToolCall("signingExportKey", firstRetry, ALICE_JWT));
		assertNotNull(RT.ensureString(firstResult.get(Strings.create("seed"))));

		// Second use — should fail (single-use)
		AMap<AString, ACell> secondResult = expectError(
			makeAuthToolCall("signingExportKey", firstRetry, ALICE_JWT));
		assertNotNull(secondResult, "Reused confirmToken should be rejected");
	}

	// ===== confirmToken for wrong tool =====

	@Test
	public void testConfirmTokenWrongTool() throws IOException, InterruptedException {
		// Create a key
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", TEST_PASSPHRASE), ALICE_JWT));
		AString publicKey = RT.getIn(createResult, "publicKey");

		// Get confirmation for exportKey
		AMap<AString, ACell> exportArgs = Maps.of("publicKey", publicKey, "passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> exportResult = expectResult(
			makeAuthToolCall("signingExportKey", exportArgs, ALICE_JWT));
		AString confirmToken = RT.ensureString(exportResult.get(Strings.create("confirmToken")));
		AString confirmUrl = RT.ensureString(exportResult.get(Strings.create("confirmUrl")));

		// Approve
		postForm(confirmUrl.toString());

		// Try to use export's confirmToken for deleteKey — should fail
		AMap<AString, ACell> deleteArgs = Maps.of(
			"publicKey", publicKey, "passphrase", TEST_PASSPHRASE, "confirmToken", confirmToken);
		AMap<AString, ACell> deleteResult = expectError(
			makeAuthToolCall("signingDeleteKey", deleteArgs, ALICE_JWT));
		assertNotNull(deleteResult, "Token for export should not work for delete");
	}

	// ===== Unapproved confirmToken =====

	@Test
	public void testUnapprovedConfirmToken() throws IOException, InterruptedException {
		// Create a key
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateKey", Maps.of("passphrase", TEST_PASSPHRASE), ALICE_JWT));
		AString publicKey = RT.getIn(createResult, "publicKey");

		// Get confirmation for exportKey but DON'T approve
		AMap<AString, ACell> exportArgs = Maps.of("publicKey", publicKey, "passphrase", TEST_PASSPHRASE);
		AMap<AString, ACell> exportResult = expectResult(
			makeAuthToolCall("signingExportKey", exportArgs, ALICE_JWT));
		AString confirmToken = RT.ensureString(exportResult.get(Strings.create("confirmToken")));

		// Retry without approval — should fail
		AMap<AString, ACell> retryArgs = Maps.of(
			"publicKey", publicKey, "passphrase", TEST_PASSPHRASE, "confirmToken", confirmToken);
		AMap<AString, ACell> retryResult = expectError(
			makeAuthToolCall("signingExportKey", retryArgs, ALICE_JWT));
		assertNotNull(retryResult, "Unapproved token should be rejected");
	}

	// ===== Confirm endpoint tests =====

	@Test
	public void testConfirmGetMissingToken() throws IOException, InterruptedException {
		HttpResponse<String> response = get(HOST_PATH + "/confirm");
		assertEquals(400, response.statusCode());
	}

	@Test
	public void testConfirmGetInvalidToken() throws IOException, InterruptedException {
		HttpResponse<String> response = get(HOST_PATH + "/confirm?token=ct_invalid");
		assertEquals(404, response.statusCode());
	}

	@Test
	public void testConfirmPostInvalidToken() throws IOException, InterruptedException {
		HttpResponse<String> response = postForm(HOST_PATH + "/confirm?token=ct_invalid");
		assertEquals(404, response.statusCode());
	}

	// ===== Helpers =====

	private AMap<AString, ACell> makeToolCall(String toolName, AMap<AString, ACell> arguments)
			throws IOException, InterruptedException {
		AMap<AString, ACell> params = Maps.of("name", toolName, "arguments", arguments);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0", "method", "tools/call", "params", params, "id", "test-" + toolName);
		HttpResponse<String> response = post(MCP_PATH, JSON.toString(request));
		assertEquals(200, response.statusCode());
		return RT.ensureMap(JSON.parse(response.body()));
	}

	private AMap<AString, ACell> makeAuthToolCall(String toolName, AMap<AString, ACell> arguments, String bearerToken)
			throws IOException, InterruptedException {
		AMap<AString, ACell> params = Maps.of("name", toolName, "arguments", arguments);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0", "method", "tools/call", "params", params, "id", "test-" + toolName);
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
