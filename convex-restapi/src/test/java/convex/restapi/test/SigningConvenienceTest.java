package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.auth.PeerAuth;
import convex.restapi.mcp.McpAPI;

/**
 * Tests for Stage 11 signing convenience tools: signingTransact,
 * signingCreateAccount, signingListAccounts.
 */
public class SigningConvenienceTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";
	private static final AString PASS = Strings.create("conv-test-pass-456");

	// Use unique identity to avoid collisions with other signing tests
	private static final AString CAROL = Strings.create("did:web:test.example.com:user:carol-convenience");
	private static final PeerAuth PEER_AUTH = new PeerAuth(KP);
	private static final String CAROL_JWT = PEER_AUTH.issuePeerToken(CAROL, 3600).toString();

	// ===== signingCreateAccount =====

	@Test
	public void testSigningCreateAccount() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of(
			"passphrase", PASS,
			"faucet", CVMLong.create(1000000000L)
		);
		AMap<AString, ACell> result = expectResult(
			makeAuthToolCall("signingCreateAccount", args, CAROL_JWT));

		// Verify address returned
		ACell addressCell = result.get(Strings.create("address"));
		assertNotNull(addressCell, "Should return address");
		assertTrue(addressCell instanceof CVMLong, "Address should be a number");

		// Verify publicKey returned
		AString publicKey = RT.ensureString(result.get(Strings.create("publicKey")));
		assertNotNull(publicKey, "Should return publicKey");
		assertTrue(publicKey.toString().startsWith("0x"), "Public key should be hex");
	}

	@Test
	public void testSigningCreateAccountNoAuth() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("passphrase", PASS);
		AMap<AString, ACell> error = expectError(makeToolCall("signingCreateAccount", args));
		assertNotNull(error, "No auth should return error");
	}

	// ===== signingTransact =====

	@Test
	public void testSigningTransact() throws IOException, InterruptedException {
		// Create account with funds
		AMap<AString, ACell> createArgs = Maps.of(
			"passphrase", PASS,
			"faucet", CVMLong.create(1000000000L)
		);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateAccount", createArgs, CAROL_JWT));

		ACell addressNum = createResult.get(Strings.create("address"));
		String addrStr = "#" + addressNum;

		// Transact using the signing service
		AMap<AString, ACell> txArgs = Maps.of(
			"source", Strings.create("(+ 1 2)"),
			"address", Strings.create(addrStr),
			"passphrase", PASS
		);
		AMap<AString, ACell> txResult = expectResult(
			makeAuthToolCall("signingTransact", txArgs, CAROL_JWT));

		// (+ 1 2) = 3
		assertEquals(CVMLong.create(3), txResult.get(McpAPI.KEY_VALUE));
	}

	@Test
	public void testSigningTransactTransfer() throws IOException, InterruptedException {
		// Create two accounts
		AMap<AString, ACell> createArgs1 = Maps.of(
			"passphrase", PASS,
			"faucet", CVMLong.create(1000000000L)
		);
		AMap<AString, ACell> r1 = expectResult(
			makeAuthToolCall("signingCreateAccount", createArgs1, CAROL_JWT));
		long addr1 = ((CVMLong) r1.get(Strings.create("address"))).longValue();

		AMap<AString, ACell> createArgs2 = Maps.of("passphrase", PASS);
		AMap<AString, ACell> r2 = expectResult(
			makeAuthToolCall("signingCreateAccount", createArgs2, CAROL_JWT));
		long addr2 = ((CVMLong) r2.get(Strings.create("address"))).longValue();

		// Transfer from account 1 to account 2
		AMap<AString, ACell> txArgs = Maps.of(
			"source", Strings.create("(transfer #" + addr2 + " 100)"),
			"address", Strings.create("#" + addr1),
			"passphrase", PASS
		);
		AMap<AString, ACell> txResult = expectResult(
			makeAuthToolCall("signingTransact", txArgs, CAROL_JWT));

		// Transfer returns the transferred amount
		assertEquals(CVMLong.create(100), txResult.get(McpAPI.KEY_VALUE));
	}

	@Test
	public void testSigningTransactWrongPassphrase() throws IOException, InterruptedException {
		// Create account
		AMap<AString, ACell> createArgs = Maps.of(
			"passphrase", PASS,
			"faucet", CVMLong.create(1000000000L)
		);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateAccount", createArgs, CAROL_JWT));
		ACell addressNum = createResult.get(Strings.create("address"));

		// Transact with wrong passphrase
		AMap<AString, ACell> txArgs = Maps.of(
			"source", Strings.create("(+ 1 2)"),
			"address", Strings.create("#" + addressNum),
			"passphrase", Strings.create("wrong-passphrase")
		);
		AMap<AString, ACell> error = expectError(
			makeAuthToolCall("signingTransact", txArgs, CAROL_JWT));
		assertNotNull(error, "Wrong passphrase should return error");
	}

	@Test
	public void testSigningTransactNoAuth() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of(
			"source", Strings.create("(+ 1 2)"),
			"address", Strings.create("#1"),
			"passphrase", PASS
		);
		AMap<AString, ACell> error = expectError(makeToolCall("signingTransact", args));
		assertNotNull(error, "No auth should return error");
	}

	// ===== signingListAccounts =====

	@Test
	public void testSigningListAccounts() throws IOException, InterruptedException {
		// Create an account first so there's at least one key
		AMap<AString, ACell> createArgs = Maps.of("passphrase", PASS);
		AMap<AString, ACell> createResult = expectResult(
			makeAuthToolCall("signingCreateAccount", createArgs, CAROL_JWT));
		AString publicKey = RT.ensureString(createResult.get(Strings.create("publicKey")));

		// List accounts
		AMap<AString, ACell> listResult = expectResult(
			makeAuthToolCall("signingListAccounts", Maps.empty(), CAROL_JWT));

		AVector<ACell> accounts = RT.ensureVector(listResult.get(Strings.create("accounts")));
		assertNotNull(accounts, "Should return accounts array");
		assertTrue(accounts.count() >= 1, "Should have at least one entry");

		// Find our key in the list
		boolean found = false;
		for (long i = 0; i < accounts.count(); i++) {
			AMap<AString, ACell> entry = RT.ensureMap(accounts.get(i));
			AString entryKey = RT.ensureString(entry.get(Strings.create("publicKey")));
			if (publicKey.equals(entryKey)) found = true;
		}
		assertTrue(found, "Created key should appear in listAccounts");
	}

	@Test
	public void testSigningListAccountsNoAuth() throws IOException, InterruptedException {
		AMap<AString, ACell> error = expectError(makeToolCall("signingListAccounts", Maps.empty()));
		assertNotNull(error, "No auth should return error");
	}

	// ===== Helpers =====

	private AMap<AString, ACell> makeToolCall(String toolName, AMap<AString, ACell> arguments)
			throws IOException, InterruptedException {
		AMap<AString, ACell> params = Maps.of(
			"name", toolName,
			"arguments", arguments
		);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0",
			"method", "tools/call",
			"params", params,
			"id", "test-" + toolName
		);

		HttpResponse<String> response = post(MCP_PATH, JSON.toString(request));
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, () -> "Expected map response but got " + RT.getType(parsed));
		return RT.ensureMap(parsed);
	}

	private AMap<AString, ACell> makeAuthToolCall(String toolName, AMap<AString, ACell> arguments, String bearerToken)
			throws IOException, InterruptedException {
		AMap<AString, ACell> params = Maps.of(
			"name", toolName,
			"arguments", arguments
		);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0",
			"method", "tools/call",
			"params", params,
			"id", "test-" + toolName
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
