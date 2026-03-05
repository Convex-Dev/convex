package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.restapi.mcp.McpProtocol;
import convex.restapi.mcp.McpTool;

/**
 * Integration tests for the MCP HTTP endpoint.
 *
 * <p>The tests here exercise the JSON-RPC interface exposed at {@code /mcp}.
 * Each scenario issues real HTTP requests against the embedded REST server and
 * inspects the JSON structure that comes back. The focus is on validating the
 * high-level contract for the minimal set of MCP methods and tools that we
 * support.</p>
 */
public class McpTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";
	private static final AString SEED_TEST = Strings.create("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
	private static final AString VALUE_HELLO = Strings.create("68656c6c6f");
	private static final AString VALUE_WORLD = Strings.create("776f726c64");

	/** Tracks the last tool name called by makeToolCall, used for schema validation in expectResult */
	private String lastToolName;

	/**
	 * Happy-path sanity check that the MCP server exposes the required tool list.
	 * The initialize call is special because it bootstraps protocol features.
	 */
	@Test
	public void testInitialize() throws IOException, InterruptedException {
		String request = "{\n"
			+ "  \"jsonrpc\": \"2.0\",\n"
			+ "  \"method\": \"initialize\",\n"
			+ "  \"params\": {},\n"
			+ "  \"id\": \"init-1\"\n"
			+ "}";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response but got " + RT.getType(parsed));

		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		assertEquals(Strings.create("init-1"), responseMap.get(McpProtocol.FIELD_ID));

		ACell resultCell = responseMap.get(McpProtocol.FIELD_RESULT);
		assertNotNull(resultCell, "initialize should return result");
		assertTrue(resultCell instanceof AMap);

		AMap<AString, ACell> result = RT.ensureMap(resultCell);
		ACell protocol = RT.getIn(result,"protocolVersion");
		assertNotNull(protocol, "initialize should include protocol version");
		
		// Should be a faucet configured for testing
		assertNotNull(server.getFaucet());
	}

	/**
	 * Unknown JSON-RPC methods must return the standard -32601 error response.
	 */
	@Test
	public void testUnknownMethod() throws IOException, InterruptedException {
		String request = "{"
			+ "  \"jsonrpc\": \"2.0\","
			+ "  \"method\": \"does/not/exist\","
			+ "  \"params\": {},"
			+ "  \"id\": \"bad-1\""
			+ "}";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response but got " + RT.getType(parsed));

		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		assertEquals(Strings.create("bad-1"), responseMap.get(McpProtocol.FIELD_ID));

		ACell errorCell = responseMap.get(McpProtocol.FIELD_ERROR);
		assertNotNull(errorCell, "Unknown method should return error object");
		assertTrue(errorCell instanceof AMap);

		AMap<AString, ACell> error = RT.ensureMap(errorCell);
		ACell codeCell = RT.getIn(error, "code");
		assertEquals(CVMLong.create(-32601), codeCell);
	}

	/**
	 * Basic smoke test that the {@code query} tool executes a form and returns a
	 * structured result payload.
	 */
	@Test
	public void testToolCallQuery() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("source", "*balance*");
		AMap<AString, ACell> responseMap = makeToolCall("query", args);
		AMap<AString, ACell> structured = expectResult(responseMap);
		assertNotNull(RT.getIn(structured, "value"));
	}

	/**
	 * Prepare tool should mirror the REST transaction preparation response and
	 * return encoded data plus metadata for signing.
	 */
	@Test
	public void testPrepareTool() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of(
			"source", "(* 2 3)",
			"address", "#11"
		);
		AMap<AString, ACell> responseMap = makeToolCall("prepare", args);
		AMap<AString, ACell> structured = expectResult(responseMap);
		assertNotNull(RT.getIn(structured, "hash"));
		assertNotNull(RT.getIn(structured, "data"));
		assertNotNull(RT.getIn(structured, "sequence"));
	}

	/**
	 * Prepare tool hash should match the output of the REST transaction/prepare API.
	 */
	@Test
	public void testPrepareToolMatchesChainAPI() throws IOException, InterruptedException {
		AString source = Strings.create("(* 2 3)");
		AString addressString = Strings.create(Init.GENESIS_ADDRESS.toString());
		AMap<AString, ACell> mcpArgs = Maps.of(
			"source", source,
			"address", addressString
		);

		AMap<AString, ACell> responseMap = makeToolCall("prepare", mcpArgs);
		AMap<AString, ACell> structured = expectResult(responseMap);
		AString mcpHash = RT.getIn(structured, "hash");
		assertNotNull(mcpHash);

		AMap<AString, ACell> requestMap = Maps.of(
			"address", addressString,
			"source", source
		);
		HttpResponse<String> restResponse = post(API_PATH + "/transaction/prepare", JSON.toString(requestMap));
		assertEquals(200, restResponse.statusCode());
		ACell restParsed = JSON.parse(restResponse.body());
		AMap<AString, ACell> restMap = RT.ensureMap(restParsed);
		assertNotNull(restMap);
		AString restHash = RT.getIn(restMap, "hash");
		assertNotNull(restHash);

		assertEquals(restHash, mcpHash);
	}

	@Test
	public void testEncodeDecodeTools() throws IOException, InterruptedException {
		assertEncodeDecodeRoundTrip("12", "110c");
		assertEncodeDecodeRoundTrip("nil", "00");
		assertEncodeDecodeRoundTrip("[]", "8000");
	}

	private void assertEncodeDecodeRoundTrip(String cvxLiteral, String expectedHex) throws IOException, InterruptedException {
		AMap<AString, ACell> encodeArgs = Maps.of("cvx", cvxLiteral);
		AMap<AString, ACell> encodeResponse = makeToolCall("encode", encodeArgs);
		AMap<AString, ACell> encodeResult = expectResult(encodeResponse);
		AString cad3 = RT.getIn(encodeResult, "cad3");
		assertNotNull(cad3);
		assertEquals(expectedHex, cad3.toString().toLowerCase());
		AString hash = RT.getIn(encodeResult, "hash");
		assertNotNull(hash);

		AMap<AString, ACell> decodeArgs = Maps.of("cad3", cad3);
		AMap<AString, ACell> decodeResponse = makeToolCall("decode", decodeArgs);
		AMap<AString, ACell> decodeResult = expectResult(decodeResponse);
		AString cvx = RT.getIn(decodeResult, "cvx");
		assertNotNull(cvx);
		assertEquals(cvxLiteral, cvx.toString());
	}

	/**
	 * Full e2e flow: prepare -> sign -> submit should execute the transaction successfully.
	 */
	@Test
	public void testPrepareSignSubmit() throws IOException, InterruptedException {
		AString source = Strings.create("(* 2 3)");
		AString addressString = Strings.create(Init.GENESIS_ADDRESS.toString());
		AMap<AString, ACell> prepareArgs = Maps.of(
			"source", source,
			"address", addressString
		);

		AMap<AString, ACell> prepareResponse = makeToolCall("prepare", prepareArgs);
		AMap<AString, ACell> prepared = expectResult(prepareResponse);
		AString hashCell = RT.getIn(prepared, "hash");
		assertNotNull(hashCell);
		Blob hashBlob = Blob.parse(hashCell.toString());

		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> signArgs = Maps.of(
			"value", hashCell,
			"seed", seedHex
		);
		AMap<AString, ACell> signResponse = makeToolCall("sign", signArgs);
		AMap<AString, ACell> signed = expectResult(signResponse);
		AString signatureHex = RT.getIn(signed, "signature");
		AString accountKeyHex = RT.getIn(signed, "accountKey");
		assertNotNull(signatureHex);
		assertNotNull(accountKeyHex);
		assertEquals(KP.getAccountKey(), AccountKey.parse(accountKeyHex));
		assertEquals(KP.sign(hashBlob), Blob.parse(signatureHex));

		AMap<AString, ACell> submitArgs = Maps.of(
			"hash", hashCell,
			"signature", signatureHex,
			"accountKey", accountKeyHex
		);
		AMap<AString, ACell> submitResponse = makeToolCall("submit", submitArgs);
		AMap<AString, ACell> submitResult = expectResult(submitResponse);
		assertEquals(CVMLong.create(6), RT.getIn(submitResult, "value"));
	}

	/**
	 * Two-step flow: prepare -> signAndSubmit should execute the transaction successfully.
	 * This is the recommended pattern for agents.
	 */
	@Test
	public void testPrepareSignAndSubmit() throws IOException, InterruptedException {
		AString source = Strings.create("(+ 10 20)");
		AString addressString = Strings.create(Init.GENESIS_ADDRESS.toString());
		AMap<AString, ACell> prepareArgs = Maps.of(
			"source", source,
			"address", addressString
		);

		AMap<AString, ACell> prepareResponse = makeToolCall("prepare", prepareArgs);
		AMap<AString, ACell> prepared = expectResult(prepareResponse);
		AString hashCell = RT.getIn(prepared, "hash");
		assertNotNull(hashCell, "Prepare should return a hash");

		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> signAndSubmitArgs = Maps.of(
			"hash", hashCell,
			"seed", seedHex
		);
		AMap<AString, ACell> signAndSubmitResponse = makeToolCall("signAndSubmit", signAndSubmitArgs);
		AMap<AString, ACell> result = expectResult(signAndSubmitResponse);
		assertEquals(CVMLong.create(30), RT.getIn(result, "value"), "Result should be 30 for (+ 10 20)");
	}

	/**
	 * signAndSubmit should fail gracefully with invalid hash.
	 */
	@Test
	public void testSignAndSubmitInvalidHash() throws IOException, InterruptedException {
		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> args = Maps.of(
			"hash", "0xDEADBEEF",
			"seed", seedHex
		);
		AMap<AString, ACell> response = makeToolCall("signAndSubmit", args);
		AMap<AString, ACell> structured = expectError(response);
		assertNotNull(structured, "Invalid hash should return a structured error");
	}

	/**
	 * signAndSubmit should fail gracefully with invalid seed.
	 */
	@Test
	public void testSignAndSubmitInvalidSeed() throws IOException, InterruptedException {
		// First prepare a valid transaction
		AString source = Strings.create("(* 2 3)");
		AString addressString = Strings.create(Init.GENESIS_ADDRESS.toString());
		AMap<AString, ACell> prepareArgs = Maps.of(
			"source", source,
			"address", addressString
		);
		AMap<AString, ACell> prepareResponse = makeToolCall("prepare", prepareArgs);
		AMap<AString, ACell> prepared = expectResult(prepareResponse);
		AString hashCell = RT.getIn(prepared, "hash");

		// Try with invalid seed (too short)
		AMap<AString, ACell> args = Maps.of(
			"hash", hashCell,
			"seed", "0x1234"
		);
		AMap<AString, ACell> response = makeToolCall("signAndSubmit", args);
		AMap<AString, ACell> structured = expectError(response);
		assertNotNull(structured, "Invalid seed should return a structured error");
	}

	/**
	 * signAndSubmit should fail with missing hash parameter.
	 */
	@Test
	public void testSignAndSubmitMissingHash() throws IOException, InterruptedException {
		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> args = Maps.of("seed", seedHex);
		AMap<AString, ACell> response = makeToolCall("signAndSubmit", args);

		// Missing params → tool error (isError=true), not protocol error (per MCP 2025-11-25)
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, "Missing hash should return a tool error result");
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	/**
	 * signAndSubmit should fail with missing seed parameter.
	 */
	@Test
	public void testSignAndSubmitMissingSeed() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("hash", "0x1234567890abcdef");
		AMap<AString, ACell> response = makeToolCall("signAndSubmit", args);

		// Missing params → tool error (isError=true), not protocol error (per MCP 2025-11-25)
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, "Missing seed should return a tool error result");
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	/**
	 * GetTransaction tool should retrieve a transaction after it's been submitted.
	 * This is an e2e test that runs a transaction then verifies it can be looked up by hash.
	 */
	@Test
	public void testGetTransaction() throws IOException, InterruptedException {
		// First, run a transaction using signAndSubmit
		AString source = Strings.create("(def test-var-for-get-tx 42)");
		AString addressString = Strings.create(Init.GENESIS_ADDRESS.toString());
		AMap<AString, ACell> prepareArgs = Maps.of(
			"source", source,
			"address", addressString
		);

		AMap<AString, ACell> prepareResponse = makeToolCall("prepare", prepareArgs);
		AMap<AString, ACell> prepared = expectResult(prepareResponse);
		AString hashCell = RT.getIn(prepared, "hash");
		assertNotNull(hashCell, "Prepare should return a hash");

		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> signAndSubmitArgs = Maps.of(
			"hash", hashCell,
			"seed", seedHex
		);
		AMap<AString, ACell> signAndSubmitResponse = makeToolCall("signAndSubmit", signAndSubmitArgs);
		AMap<AString, ACell> txResult = expectResult(signAndSubmitResponse);
		assertEquals(CVMLong.create(42), RT.getIn(txResult, "value"), "Transaction result should be 42");

		// Get the transaction hash from info.tx (info has string keys with hex string values)
		ACell infoCell = RT.getIn(txResult, "info");
		assertNotNull(infoCell, "Transaction result should have info, got result: " + txResult);
		AString txHashString = RT.ensureString(RT.getIn(infoCell, "tx"));
		assertNotNull(txHashString, "Transaction info should contain tx hash");

		// Now use getTransaction to look up the transaction by hash
		AMap<AString, ACell> getTransactionArgs = Maps.of("hash", txHashString);
		AMap<AString, ACell> getTransactionResponse = makeToolCall("getTransaction", getTransactionArgs);
		AMap<AString, ACell> getTxResult = expectResult(getTransactionResponse);

		// Verify it was found
		assertEquals(CVMBool.TRUE, RT.getIn(getTxResult, "found"), "Transaction should be found");
		assertNotNull(RT.getIn(getTxResult, "tx"), "Transaction data should be returned");
		assertNotNull(RT.getIn(getTxResult, "position"), "Transaction position should be returned");
		assertNotNull(RT.getIn(getTxResult, "result"), "Transaction result should be returned");
	}

	/**
	 * GetTransaction tool should return found=false for a non-existent hash.
	 */
	@Test
	public void testGetTransactionNotFound() throws IOException, InterruptedException {
		// Use a valid but non-existent hash
		AMap<AString, ACell> args = Maps.of("hash", "0x0000000000000000000000000000000000000000000000000000000000000000");
		AMap<AString, ACell> response = makeToolCall("getTransaction", args);
		AMap<AString, ACell> result = expectResult(response);

		assertEquals(CVMBool.FALSE, RT.getIn(result, "found"), "Non-existent transaction should have found=false");
	}

	/**
	 * GetTransaction tool should return error for invalid hash format.
	 */
	@Test
	public void testGetTransactionInvalidHash() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("hash", "not-a-valid-hash");
		AMap<AString, ACell> response = makeToolCall("getTransaction", args);
		AMap<AString, ACell> error = expectError(response);
		assertNotNull(error, "Invalid hash should return an error");
	}

	/**
	 * GetTransaction tool should return protocol error for missing hash parameter.
	 */
	@Test
	public void testGetTransactionMissingHash() throws IOException, InterruptedException {
		AMap<AString, ACell> response = makeToolCall("getTransaction", Maps.empty());

		// Missing params → tool error (isError=true), not protocol error (per MCP 2025-11-25)
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, "Missing hash should return a tool error result");
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	/**
	 * Transact tool should execute a transaction directly with source, seed, and address,
	 * returning the result value.
	 */
	@Test
	public void testTransactTool() throws IOException, InterruptedException {
		AString source = Strings.create("(* 2 3)");
		AString addressString = Strings.create(Init.GENESIS_ADDRESS.toString());
		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> transactArgs = Maps.of(
			"source", source,
			"address", addressString,
			"seed", seedHex
		);

		AMap<AString, ACell> transactResponse = makeToolCall("transact", transactArgs);
		AMap<AString, ACell> transactResult = expectResult(transactResponse);
		ACell value = RT.getIn(transactResult, "value");
		assertNotNull(value, "Transact should return a value");
		assertEquals(CVMLong.create(6), value, "Transact result should be 6 for (* 2 3)");
	}

	/**
	 * KeyGen tool should generate a key pair with a secure random seed when no seed is provided.
	 */
	@Test
	public void testKeyGenToolRandom() throws IOException, InterruptedException {
		AMap<AString, ACell> responseMap = makeToolCall("keyGen", Maps.empty());
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		AString seed = RT.getIn(structured, "seed");
		AString publicKey = RT.getIn(structured, "publicKey");
		
		assertNotNull(seed, "KeyGen should return a seed");
		assertNotNull(publicKey, "KeyGen should return a publicKey");
		assertTrue(seed.toString().startsWith("0x"), "Seed should start with 0x prefix");
		assertEquals(66, seed.toString().length(), "Seed should be 66 characters (0x + 64 hex chars for 32 bytes)");
		assertTrue(publicKey.toString().startsWith("0x"), "PublicKey should start with 0x prefix");
	}

	/**
	 * KeyGen tool should generate a key pair from a provided seed (without 0x prefix).
	 */
	@Test
	public void testKeyGenToolWithSeed() throws IOException, InterruptedException {
		AMap<AString, ACell> keyGenArgs = Maps.of("seed", SEED_TEST);
		AMap<AString, ACell> responseMap = makeToolCall("keyGen", keyGenArgs);
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		AString seed = RT.getIn(structured, "seed");
		AString publicKey = RT.getIn(structured, "publicKey");
		
		assertNotNull(seed, "KeyGen should return the seed");
		assertNotNull(publicKey, "KeyGen should return a publicKey");
		AString expectedSeed = Strings.create("0x" + SEED_TEST.toString());
		assertEquals(expectedSeed, seed, "Seed should match the provided seed with 0x prefix");
		
		// Verify the public key is deterministic for the same seed
		AMap<AString, ACell> responseMap2 = makeToolCall("keyGen", keyGenArgs);
		AMap<AString, ACell> structured2 = expectResult(responseMap2);
		AString publicKey2 = RT.getIn(structured2, "publicKey");
		assertEquals(publicKey, publicKey2, "Same seed should produce same public key");
	}

	/**
	 * KeyGen tool should accept seed input with 0x prefix.
	 */
	@Test
	public void testKeyGenToolWithSeedWithPrefix() throws IOException, InterruptedException {
		AString seedHexWithPrefix = Strings.create("0x" + SEED_TEST.toString());
		AMap<AString, ACell> keyGenArgs = Maps.of("seed", seedHexWithPrefix);
		
		AMap<AString, ACell> responseMap = makeToolCall("keyGen", keyGenArgs);
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		AString seed = RT.getIn(structured, "seed");
		AString publicKey = RT.getIn(structured, "publicKey");
		
		assertNotNull(seed, "KeyGen should return the seed");
		assertNotNull(publicKey, "KeyGen should return a publicKey");
		AString expectedSeed = Strings.create("0x" + SEED_TEST.toString());
		assertEquals(expectedSeed, seed, "Seed should match the provided seed with 0x prefix");
		
		// Verify the public key is the same whether input has 0x prefix or not
		AMap<AString, ACell> keyGenArgsNoPrefix = Maps.of("seed", SEED_TEST);
		AMap<AString, ACell> responseMap2 = makeToolCall("keyGen", keyGenArgsNoPrefix);
		AMap<AString, ACell> structured2 = expectResult(responseMap2);
		AString publicKey2 = RT.getIn(structured2, "publicKey");
		assertEquals(publicKey, publicKey2, "Same seed with or without 0x prefix should produce same public key");
	}

	/**
	 * Helper method to generate a key pair and return the public key.
	 */
	private AString generateKeyPair(AString seed) throws IOException, InterruptedException {
		AMap<AString, ACell> keyGenArgs = Maps.of("seed", seed);
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", keyGenArgs);
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		return RT.getIn(keyGenResult, "publicKey");
	}

	/**
	 * Helper method to sign data with a seed.
	 */
	private AString signData(AString valueHex, AString seedHex) throws IOException, InterruptedException {
		AMap<AString, ACell> signArgs = Maps.of(
			"value", valueHex,
			"seed", seedHex
		);
		AMap<AString, ACell> signResponse = makeToolCall("sign", signArgs);
		AMap<AString, ACell> signResult = expectResult(signResponse);
		return RT.getIn(signResult, "signature");
	}

	/**
	 * Validate tool should return true for a valid Ed25519 signature.
	 */
	@Test
	public void testValidateToolSuccess() throws IOException, InterruptedException {
		AString publicKeyHex = generateKeyPair(SEED_TEST);
		AString signatureHex = signData(VALUE_HELLO, SEED_TEST);
		
		AMap<AString, ACell> validateArgs = Maps.of(
			"publicKey", publicKeyHex,
			"signature", signatureHex,
			"bytes", VALUE_HELLO
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", validateArgs);
		AMap<AString, ACell> validateResult = expectResult(validateResponse);
		ACell value = RT.getIn(validateResult, "value");
		
		assertNotNull(value, "Validate should return a value");
		assertEquals(CVMBool.TRUE, value, "Valid signature should return true");
	}

	/**
	 * Validate tool should return false for an invalid signature (wrong signature).
	 */
	@Test
	public void testValidateToolFailureWrongSignature() throws IOException, InterruptedException {
		AString publicKeyHex = generateKeyPair(SEED_TEST);
		AString wrongSignatureHex = Strings.create("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
		
		AMap<AString, ACell> validateArgs = Maps.of(
			"publicKey", publicKeyHex,
			"signature", wrongSignatureHex,
			"bytes", VALUE_HELLO
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", validateArgs);
		AMap<AString, ACell> validateResult = expectResult(validateResponse);
		ACell value = RT.getIn(validateResult, "value");
		
		assertNotNull(value, "Validate should return a value");
		assertEquals(CVMBool.FALSE, value, "Invalid signature should return false");
	}

	/**
	 * Validate tool should return false for an invalid signature (wrong message bytes).
	 */
	@Test
	public void testValidateToolFailureWrongBytes() throws IOException, InterruptedException {
		AString publicKeyHex = generateKeyPair(SEED_TEST);
		AString signatureHex = signData(VALUE_HELLO, SEED_TEST);
		
		AMap<AString, ACell> validateArgs = Maps.of(
			"publicKey", publicKeyHex,
			"signature", signatureHex,
			"bytes", VALUE_WORLD
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", validateArgs);
		AMap<AString, ACell> validateResult = expectResult(validateResponse);
		ACell value = RT.getIn(validateResult, "value");
		
		assertNotNull(value, "Validate should return a value");
		assertEquals(CVMBool.FALSE, value, "Signature for wrong message should return false");
	}

	/**
	 * End-to-end test: Generate a key pair, sign data, and validate the signature.
	 * This test demonstrates the complete workflow using keyGen, sign, and validate tools.
	 */
	@Test
	public void testSignatureE2E() throws IOException, InterruptedException {
		// Step 1: Generate a random key pair using keyGen
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", Maps.empty());
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		AString seedHex = RT.getIn(keyGenResult, "seed");
		AString publicKeyHex = RT.getIn(keyGenResult, "publicKey");
		
		assertNotNull(seedHex, "keyGen should return a seed");
		assertNotNull(publicKeyHex, "keyGen should return a publicKey");
		assertTrue(seedHex.toString().startsWith("0x"), "Seed should start with 0x prefix");
		assertTrue(publicKeyHex.toString().startsWith("0x"), "PublicKey should start with 0x prefix");
		
		// Step 2: Sign some data using the generated seed
		AString valueHex = Strings.create("48656c6c6f20576f726c64");
		AMap<AString, ACell> signArgs = Maps.of(
			"value", valueHex,
			"seed", seedHex
		);
		AMap<AString, ACell> signResponse = makeToolCall("sign", signArgs);
		AMap<AString, ACell> signResult = expectResult(signResponse);
		AString signatureHex = RT.getIn(signResult, "signature");
		AString accountKeyFromSign = RT.getIn(signResult, "accountKey");
		
		assertNotNull(signatureHex, "sign should return a signature");
		assertNotNull(accountKeyFromSign, "sign should return an accountKey");
		assertTrue(signatureHex.toString().length() > 0, "Signature should be non-empty");
		
		// Verify the account key from sign matches the public key from keyGen
		// Normalize by removing 0x prefix if present for comparison
		String normalizedPublicKey = publicKeyHex.toString().startsWith("0x") ? publicKeyHex.toString().substring(2) : publicKeyHex.toString();
		String normalizedAccountKey = accountKeyFromSign.toString().startsWith("0x") ? accountKeyFromSign.toString().substring(2) : accountKeyFromSign.toString();
		assertEquals(normalizedPublicKey.toLowerCase(), normalizedAccountKey.toLowerCase(), "Public key from keyGen should match accountKey from sign");
		
		// Step 3: Validate the signature using the validate tool
		AMap<AString, ACell> validateArgs = Maps.of(
			Strings.create("publicKey"), publicKeyHex,
			Strings.create("signature"), signatureHex,
			Strings.create("bytes"), valueHex
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", validateArgs);
		AMap<AString, ACell> validateResult = expectResult(validateResponse);
		ACell isValid = RT.getIn(validateResult, "value");
		
		assertNotNull(isValid, "validate should return a value");
		assertEquals(CVMBool.TRUE, isValid, "The signature should be valid after the complete E2E workflow");
	}

	/**
	 * Validate tool should return false for an invalid signature (wrong public key).
	 */
	@Test
	public void testValidateToolFailureWrongPublicKey() throws IOException, InterruptedException {
		AString seed1Hex = SEED_TEST;
		AString seed2Hex = Strings.create("2102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f21");
		
		AString publicKey2Hex = generateKeyPair(seed2Hex);
		AString signatureHex = signData(VALUE_HELLO, seed1Hex);
		
		AMap<AString, ACell> validateArgs = Maps.of(
			"publicKey", publicKey2Hex,
			"signature", signatureHex,
			"bytes", VALUE_HELLO
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", validateArgs);
		AMap<AString, ACell> validateResult = expectResult(validateResponse);
		ACell value = RT.getIn(validateResult, "value");
		
		assertNotNull(value, "Validate should return a value");
		assertEquals(CVMBool.FALSE, value, "Signature with wrong public key should return false");
	}

	/**
	 * CreateAccount tool should create a new account with the provided public key.
	 */
	@Test
	public void testCreateAccountTool() throws IOException, InterruptedException {
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", Maps.empty());
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		AString publicKeyHex = RT.getIn(keyGenResult, "publicKey");
		
		assertNotNull(publicKeyHex, "keyGen should return a publicKey");
		
		AMap<AString, ACell> createAccountArgs = Maps.of("accountKey", publicKeyHex);
		AMap<AString, ACell> createAccountResponse = makeToolCall("createAccount", createAccountArgs);
		AMap<AString, ACell> createAccountResult = expectResult(createAccountResponse);
		ACell addressCell = RT.getIn(createAccountResult, "address");
		
		assertNotNull(addressCell, "createAccount should return an address");
		CVMLong address = CVMLong.parse(addressCell);
		assertNotNull(address, "Address should be a valid number");
		assertTrue(address.longValue() > 0, "Address should be a positive number");
	}

	/**
	 * CreateAccount tool should create an account and request faucet coins.
	 */
	@Test
	public void testCreateAccountToolWithFaucet() throws IOException, InterruptedException {
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", Maps.empty());
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		AString publicKeyHex = RT.getIn(keyGenResult, "publicKey");
		
		assertNotNull(publicKeyHex, "keyGen should return a publicKey");
		
		AMap<AString, ACell> createAccountArgs = Maps.of(
			"accountKey", publicKeyHex,
			"faucet", CVMLong.create(1000)
		);
		AMap<AString, ACell> createAccountResponse = makeToolCall("createAccount", createAccountArgs);
		AMap<AString, ACell> createAccountResult = expectResult(createAccountResponse);
		ACell addressCell = RT.getIn(createAccountResult, "address");
		
		assertNotNull(addressCell, "createAccount should return an address");
		CVMLong address = CVMLong.parse(addressCell);
		assertNotNull(address, "Address should be a valid number");
		assertTrue(address.longValue() > 0, "Address should be a positive number");
	}

	/**
	 * CreateAccount and DescribeAccount integration test.
	 * Creates a new account and verifies that describeAccount returns an empty metadata map.
	 */
	@Test
	public void testCreateAccountAndDescribeAccount() throws IOException, InterruptedException {
		// Step 1: Generate a key pair
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", Maps.empty());
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		AString publicKeyHex = RT.getIn(keyGenResult, "publicKey");
		
		assertNotNull(publicKeyHex, "keyGen should return a publicKey");
		
		// Step 2: Create an account
		AMap<AString, ACell> createAccountArgs = Maps.of("accountKey", publicKeyHex);
		AMap<AString, ACell> createAccountResponse = makeToolCall("createAccount", createAccountArgs);
		AMap<AString, ACell> createAccountResult = expectResult(createAccountResponse);
		ACell addressCell = RT.getIn(createAccountResult, "address");
		
		assertNotNull(addressCell, "createAccount should return an address");
		Address addressLong = Address.parse(addressCell);
		assertNotNull(addressLong, "Address should be valid");
		
		// Step 3: Describe the account
		AString addressString = Strings.create("#" + addressLong.longValue());
		AMap<AString, ACell> describeAccountArgs = Maps.of("address", addressString);
		AMap<AString, ACell> describeAccountResponse = makeToolCall("describeAccount", describeAccountArgs);
		AMap<AString, ACell> describeAccountResult = expectResult(describeAccountResponse);
		
		// Step 4: Verify metadata is an empty map
		AString metadataCell = RT.getIn(describeAccountResult, "metadata");
		assertNotNull(metadataCell, "describeAccount should return CVX format metadata map");
		AMap<Symbol,AHashMap<ACell,ACell>> meta=Reader.read(metadataCell);
		assertTrue(meta instanceof AMap, "Metadata should be a map");
		assertEquals(0, meta.count(), "New account should have empty metadata");
	}

	/**
	 * Attempting to call an unregistered tool should surface a protocol error
	 * (same as unknown method) rather than a tool-level error payload.
	 */
	@Test
	public void testToolCallUnknownTool() throws IOException, InterruptedException {
		AMap<AString, ACell> responseMap = makeToolCall("unknown-tool", Maps.empty());
		assertEquals(Strings.create("test-unknown-tool"), responseMap.get(McpProtocol.FIELD_ID));
		ACell errorCell = responseMap.get(McpProtocol.FIELD_ERROR);
		assertNotNull(errorCell, "Unknown tool should return a JSON-RPC error");
		assertTrue(errorCell instanceof AMap);

		AMap<AString, ACell> error = RT.ensureMap(errorCell);
		ACell codeCell = RT.getIn(error, "code");
		assertEquals(CVMLong.create(-32601), codeCell);
	}

	/**
	 * The sign tool should accept hex-encoded data, sign it with the caller-provided
	 * Ed25519 seed, and return signature and public key information.
	 */
	@Test
	public void testSignWithSeed() throws IOException, InterruptedException {
		Blob seedBlob = Blob.parse(SEED_TEST.toString());
		AKeyPair keyPair = AKeyPair.create(seedBlob);
		Blob payload = Blob.parse(VALUE_HELLO.toString());
		ASignature expectedSignature = keyPair.sign(payload);

		AMap<AString, ACell> arguments = Maps.of(
			"value", VALUE_HELLO,
			"seed", SEED_TEST
		);
		AMap<AString, ACell> responseMap = makeToolCall("sign", arguments);
		AMap<AString, ACell> structured = expectResult(responseMap);

		AString signature = RT.getIn(structured, "signature");
		AString accountKey = RT.getIn(structured, "accountKey");
		AString signedValue = RT.getIn(structured, "value");

		assertEquals(Strings.create(expectedSignature.toHexString()), signature);
		assertEquals(Strings.create(keyPair.getAccountKey().toHexString()), accountKey);
		assertEquals(VALUE_HELLO, signedValue);
	}

	/**
	 * Missing seed argument should produce a tool-level error with a helpful
	 * message, keeping the JSON-RPC envelope successful.
	 */
	@Test
	public void testSignMissingSeed() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("value", "68656c6c6f");
		AMap<AString, ACell> responseMap = makeToolCall("sign", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNull(RT.getIn(structured, "value"));
	}

	/**
	 * Missing payload should similarly surface as a tool-level error, ensuring
	 * clients know they need to provide the hex data to sign.
	 */
	@Test
	public void testSignMissingValue() throws IOException, InterruptedException {
		AMap<AString, ACell> signArgs = Maps.of("seed", SEED_TEST);
		AMap<AString, ACell> responseMap = makeToolCall("sign", signArgs);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNull(RT.getIn(structured, "signature"));
	}

	/**
	 * Empty batch should return an Invalid Request error response.
	 */
	@Test
	public void testEmptyBatchInvalidRequest() throws IOException, InterruptedException {
		HttpResponse<String> response = post(MCP_PATH, "[]");
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response but got " + RT.getType(parsed));
		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);

		assertNull(responseMap.get(McpProtocol.FIELD_ID));
		AMap<AString, ACell> error = RT.ensureMap(responseMap.get(McpProtocol.FIELD_ERROR));
		assertNotNull(error);
		assertEquals(CVMLong.create(-32600), error.get(McpProtocol.FIELD_CODE));
	}

	/**
	 * Non-empty but invalid batch should return a vector of Invalid Request errors.
	 */
	@Test
	public void testInvalidBatchElement() throws IOException, InterruptedException {
		HttpResponse<String> response = post(MCP_PATH, "[1]");
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AVector, "Expected vector response but got " + RT.getType(parsed));
		AVector<ACell> results = RT.ensureVector(parsed);
		assertEquals(1, results.count());

		AMap<AString, ACell> errorResponse = RT.ensureMap(results.get(0));
		assertNull(errorResponse.get(McpProtocol.FIELD_ID));
		AMap<AString, ACell> error = RT.ensureMap(errorResponse.get(McpProtocol.FIELD_ERROR));
		assertNotNull(error);
		assertEquals(CVMLong.create(-32600), error.get(McpProtocol.FIELD_CODE));
	}

	/**
	 * Utility to issue an MCP tools/call request and get the parsed response as a
	 * Convex map.
	 */
	private AMap<AString, ACell> makeToolCall(String toolName, AMap<AString, ACell> arguments) throws IOException, InterruptedException {
		if (arguments == null) {
			arguments = Maps.empty();
		}
		this.lastToolName = toolName;
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
		assertTrue(parsed instanceof AMap, ()->"Expected map response but got " + RT.getType(parsed));
		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		return responseMap;
	}

	/**
	 * Validates that the structured content of a tool response matches the
	 * declared outputSchema in the tool's JSON definition. Checks that each
	 * property's actual type matches the schema type (string, object, boolean,
	 * integer, number, array).
	 */
	private void validateOutputSchema(String toolName, AMap<AString, ACell> structured) {
		String resourcePath = "convex/restapi/mcp/tools/" + toolName + ".json";
		AMap<AString, ACell> metadata = McpTool.loadMetadata(resourcePath);

		AMap<AString, ACell> outputSchema = RT.ensureMap(metadata.get(Strings.create("outputSchema")));
		if (outputSchema == null) return; // no schema to validate

		AMap<AString, ACell> properties = RT.ensureMap(outputSchema.get(Strings.create("properties")));
		if (properties == null) return; // no properties declared

		long n = properties.count();
		for (long i = 0; i < n; i++) {
			var entry = properties.entryAt(i);
			String fieldName = entry.getKey().toString();
			AMap<AString, ACell> fieldSchema = RT.ensureMap(entry.getValue());
			if (fieldSchema == null) continue;

			AString typeCell = RT.ensureString(fieldSchema.get(Strings.create("type")));
			if (typeCell == null) continue; // no type constraint

			String expectedType = typeCell.toString();
			ACell actualValue = structured.get(Strings.create(fieldName));

			// Field may be absent (not required) - only validate if present
			if (actualValue == null) continue;

			switch (expectedType) {
				case "string":
					assertTrue(actualValue instanceof AString,
						() -> "Tool '" + toolName + "' field '" + fieldName + "': expected string but got " + RT.getType(actualValue) + " = " + actualValue);
					break;
				case "object":
					assertTrue(actualValue instanceof AMap,
						() -> "Tool '" + toolName + "' field '" + fieldName + "': expected object but got " + RT.getType(actualValue) + " = " + actualValue);
					break;
				case "boolean":
					assertTrue(actualValue instanceof CVMBool,
						() -> "Tool '" + toolName + "' field '" + fieldName + "': expected boolean but got " + RT.getType(actualValue) + " = " + actualValue);
					break;
				case "integer":
				case "number":
					assertTrue(actualValue instanceof ANumeric,
						() -> "Tool '" + toolName + "' field '" + fieldName + "': expected " + expectedType + " but got " + RT.getType(actualValue) + " = " + actualValue);
					break;
				case "array":
					assertTrue(actualValue instanceof AVector,
						() -> "Tool '" + toolName + "' field '" + fieldName + "': expected array but got " + RT.getType(actualValue) + " = " + actualValue);
					break;
				default:
					// Unknown type in schema, skip validation
					break;
			}
		}
	}

	/**
	 * Common assertion path for successful tool calls. Ensures the result wrapper
	 * is present, marks {@code isError == false}, checks that a text payload was
	 * produced for backward compatibility, and returns the structured content map
	 * for further inspection. Also validates the output against the declared schema.
	 */
	private AMap<AString, ACell> expectResult(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpProtocol.FIELD_ERROR));
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, ()->"RPC result missing in:" + responseMap);
		assertEquals(CVMBool.FALSE, result.get(McpProtocol.FIELD_IS_ERROR), ()->"Unexpcted failure in:" + responseMap);

		AVector<ACell> content = RT.ensureVector(result.get(McpProtocol.FIELD_CONTENT));
		assertNotNull(content);
		assertTrue(content.count() > 0);
		AMap<AString, ACell> textEntry = RT.ensureMap(content.get(0));
		assertNotNull(textEntry.get(McpProtocol.FIELD_TEXT));
		AMap<AString, ACell> structured =RT.ensureMap(result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);

		// Validate structured content against the tool's declared outputSchema
		if (lastToolName != null) {
			validateOutputSchema(lastToolName, structured);
		}

		return structured;
	}

	/**
	 * Common assertion path for tool failures. Ensures the JSON-RPC call succeeded
	 * but the structured content indicates an error payload that tests can read.
	 */
	private AMap<AString, ACell> expectError(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpProtocol.FIELD_ERROR));
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
		AMap<AString, ACell> structured = RT.ensureMap(result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}

	/**
	 * Lookup tool should find "count" symbol in address #8 (core account) and return non-null metadata.
	 */
	@Test
	public void testLookupCountInCore() throws IOException, InterruptedException {
		AMap<AString, ACell> lookupArgs = Maps.of(
			"address", "convex.core",
			"symbol", "count"
		);
		AMap<AString, ACell> responseMap = makeToolCall("lookup", lookupArgs);
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		ACell exists = RT.getIn(structured, "exists");
		assertEquals(CVMBool.TRUE, exists, "count symbol should exist in #8");
		
		ACell value = RT.getIn(structured, "value");
		assertNotNull(value, "count symbol should have a value");
		
		AString metaString = RT.getIn(structured, "meta");
		AMap<Symbol,AHashMap<ACell,ACell>> meta=Reader.read(metaString);
		assertTrue(meta instanceof AHashMap, "Metadata should be a map");
	}

	/**
	 * Lookup tool should return exists=false for a symbol that doesn't exist.
	 */
	@Test
	public void testLookupNonExistentSymbol() throws IOException, InterruptedException {
		AMap<AString, ACell> lookupArgs = Maps.of(
			"address", "#0",
			"symbol", "foo"
		);
		AMap<AString, ACell> responseMap = makeToolCall("lookup", lookupArgs);
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		ACell exists = RT.getIn(structured, "exists");
		assertEquals(CVMBool.FALSE, exists, "foo symbol should not exist in #0");
		
		assertFalse(structured.containsKey(Strings.create("value")), ()->"Non-existent symbol should not have a value");

	}

	/**
	 * Lookup tool should return an error for an invalid address like -100.
	 */
	@Test
	public void testLookupInvalidAddress() throws IOException, InterruptedException {
		AMap<AString, ACell> lookupArgs = Maps.of(
			"address", "#-100",
			"symbol", "count"
		);
		AMap<AString, ACell> responseMap = makeToolCall("lookup", lookupArgs);
		AMap<AString, ACell> structured = expectError(responseMap);
		
		ACell message = RT.getIn(structured, "message");
		assertNotNull(message, "Error should have a message");
		String messageStr = message.toString().toLowerCase();
		assertTrue(messageStr.contains("address") || messageStr.contains("invalid") || messageStr.contains("not found"), 
			"Error message should mention address, invalid, or not found, but got: " + messageStr);
	}

	/**
	 * Lookup tool should return an error for an invalid symbol like "[]".
	 */
	@Test
	public void testLookupInvalidSymbol() throws IOException, InterruptedException {
		AMap<AString, ACell> lookupArgs = Maps.of(
			"address", "#8",
			"symbol", "[]"
		);
		AMap<AString, ACell> responseMap = makeToolCall("lookup", lookupArgs);
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		ACell exists = RT.getIn(structured, "exists");
		assertSame(CVMBool.FALSE,exists);
	}

	/**
	 * ResolveCNS tool should return the same record for "@convex.trust" and "convex.trust".
	 */
	@Test
	public void testResolveCNSWithAndWithoutAt() throws IOException, InterruptedException {
		// Test with @ prefix
		AMap<AString, ACell> argsWithAt = Maps.of("name", "@convex.trust");
		AMap<AString, ACell> responseWithAt = makeToolCall("resolveCNS", argsWithAt);
		AMap<AString, ACell> structuredWithAt = expectResult(responseWithAt);
		ACell existsWithAt = RT.getIn(structuredWithAt, "exists");
		assertEquals(CVMBool.TRUE, existsWithAt, "@convex.trust should exist");
		
		// Test without @ prefix
		AMap<AString, ACell> argsWithoutAt = Maps.of("name", "convex.trust");
		AMap<AString, ACell> responseWithoutAt = makeToolCall("resolveCNS", argsWithoutAt);
		AMap<AString, ACell> structuredWithoutAt = expectResult(responseWithoutAt);
		
		// Both should be equal
		assertEquals(structuredWithAt, structuredWithoutAt);

	}

	/**
	 * ResolveCNS tool should return controller #2 for convex.trust.
	 */
	@Test
	public void testResolveCNSTrustController() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("name", "convex.trust");
		AMap<AString, ACell> responseMap = makeToolCall("resolveCNS", args);
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		ACell exists = RT.getIn(structured, "exists");
		assertEquals(CVMBool.TRUE, exists, "convex.trust should exist");
		
		ACell controller = RT.getIn(structured, "controller");
		assertNotNull(controller, "Controller should not be null");
		
		Address controllerAddr = Address.parse(controller);
		assertNotNull(controllerAddr, "Controller should be an Address");
	}

	/**
	 * ResolveCNS tool should return exists=false for a non-existent CNS name.
	 */
	@Test
	public void testResolveCNSNonExistent() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("name", "fictitious.cns.name");
		AMap<AString, ACell> responseMap = makeToolCall("resolveCNS", args);
		AMap<AString, ACell> structured = expectResult(responseMap);

		ACell exists = RT.getIn(structured, "exists");
		assertEquals(CVMBool.FALSE, exists, "fictitious.cns.name should not exist");
	}

	/**
	 * Hash tool should compute SHA256 hash by default.
	 */
	@Test
	public void testHashToolSha256Default() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("value", "hello");
		AMap<AString, ACell> responseMap = makeToolCall("hash", args);
		AMap<AString, ACell> structured = expectResult(responseMap);

		AString hash = RT.getIn(structured, "hash");
		assertNotNull(hash, "Hash tool should return a hash value");
		assertEquals(64, hash.toString().length(), "SHA256 hash should be 32 bytes (64 hex chars)");

		AString algorithm = RT.getIn(structured, "algorithm");
		assertEquals(Strings.create("sha256"), algorithm, "Default algorithm should be sha256");
	}

	/**
	 * Hash tool should compute SHA3 when algorithm is specified.
	 */
	@Test
	public void testHashToolSha3() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of(
			"value", "hello",
			"algorithm", "sha3"
		);
		AMap<AString, ACell> responseMap = makeToolCall("hash", args);
		AMap<AString, ACell> structured = expectResult(responseMap);

		AString hash = RT.getIn(structured, "hash");
		assertNotNull(hash, "Hash tool should return a hash value");
		assertEquals(64, hash.toString().length(), "SHA3 hash should be 32 bytes (64 hex chars)");

		AString algorithm = RT.getIn(structured, "algorithm");
		assertEquals(Strings.create("sha3"), algorithm, "Algorithm should be sha3");
	}

	/**
	 * Hash tool should produce different results for sha3 vs sha256.
	 */
	@Test
	public void testHashToolDifferentAlgorithms() throws IOException, InterruptedException {
		AMap<AString, ACell> sha256Args = Maps.of("value", "test data");
		AMap<AString, ACell> sha256Response = makeToolCall("hash", sha256Args);
		AMap<AString, ACell> sha256Structured = expectResult(sha256Response);
		AString sha256Hash = RT.getIn(sha256Structured, "hash");

		AMap<AString, ACell> sha3Args = Maps.of(
			"value", "test data",
			"algorithm", "sha3"
		);
		AMap<AString, ACell> sha3Response = makeToolCall("hash", sha3Args);
		AMap<AString, ACell> sha3Structured = expectResult(sha3Response);
		AString sha3Hash = RT.getIn(sha3Structured, "hash");

		assertNotNull(sha256Hash, "SHA256 hash should not be null");
		assertNotNull(sha3Hash, "SHA3 hash should not be null");
		assertFalse(sha3Hash.equals(sha256Hash), "SHA3 and SHA256 should produce different hashes");
	}

	/**
	 * Hash tool should return an error when value is missing.
	 */
	@Test
	public void testHashToolMissingValue() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("algorithm", "sha3");
		AMap<AString, ACell> responseMap = makeToolCall("hash", args);

		// Missing params → tool error (isError=true), not protocol error (per MCP 2025-11-25)
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, "Missing value should return a tool error result");
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	/**
	 * PeerStatus tool should return peer information including state and network details.
	 */
	@Test
	public void testPeerStatusTool() throws IOException, InterruptedException {
		AMap<AString, ACell> responseMap = makeToolCall("peerStatus", Maps.empty());
		AMap<AString, ACell> structured = expectResult(responseMap);

		ACell status = RT.getIn(structured, "status");
		assertNotNull(status, "PeerStatus should return a status map");
		assertTrue(status instanceof AMap, "Status should be a map");
	}

	/**
	 * Submit tool should accept 'sig' parameter (primary) for signature.
	 */
	@Test
	public void testSubmitToolWithSigParameter() throws IOException, InterruptedException {
		AString source = Strings.create("(* 3 4)");
		AString addressString = Strings.create(Init.GENESIS_ADDRESS.toString());
		AMap<AString, ACell> prepareArgs = Maps.of(
			"source", source,
			"address", addressString
		);

		AMap<AString, ACell> prepareResponse = makeToolCall("prepare", prepareArgs);
		AMap<AString, ACell> prepared = expectResult(prepareResponse);
		AString hashCell = RT.getIn(prepared, "hash");
		assertNotNull(hashCell);

		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> signArgs = Maps.of(
			"value", hashCell,
			"seed", seedHex
		);
		AMap<AString, ACell> signResponse = makeToolCall("sign", signArgs);
		AMap<AString, ACell> signed = expectResult(signResponse);
		AString signatureHex = RT.getIn(signed, "signature");
		AString accountKeyHex = RT.getIn(signed, "accountKey");

		// Use 'sig' parameter instead of 'signature'
		AMap<AString, ACell> submitArgs = Maps.of(
			"hash", hashCell,
			"sig", signatureHex,
			"accountKey", accountKeyHex
		);
		AMap<AString, ACell> submitResponse = makeToolCall("submit", submitArgs);
		AMap<AString, ACell> submitResult = expectResult(submitResponse);
		assertEquals(CVMLong.create(12), RT.getIn(submitResult, "value"));
	}

	// =============================================================================
	// Adversarial / Bad Input Tests
	// =============================================================================

	/**
	 * Query tool should handle malformed Convex Lisp code gracefully.
	 */
	@Test
	public void testQueryMalformedCode() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("source", "(def x");  // Missing closing paren
		AMap<AString, ACell> responseMap = makeToolCall("query", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNotNull(structured, "Malformed code should return a structured error");
	}

	/**
	 * Query tool should handle SQL injection attempts gracefully.
	 */
	@Test
	public void testQuerySQLInjection() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("source", "'; DROP TABLE users; --");
		AMap<AString, ACell> responseMap = makeToolCall("query", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNotNull(structured, "SQL injection attempt should return a structured error");
	}

	/**
	 * Query tool should handle extremely long input.
	 */
	@Test
	public void testQueryExtremelyLongInput() throws IOException, InterruptedException {
		StringBuilder sb = new StringBuilder("(+ 1");
		for (int i = 0; i < 1000; i++) {
			sb.append(" 1");
		}
		sb.append(")");
		AMap<AString, ACell> args = Maps.of("source", sb.toString());
		AMap<AString, ACell> responseMap = makeToolCall("query", args);
		// Should either succeed or fail gracefully
		ACell result = responseMap.get(McpProtocol.FIELD_RESULT);
		ACell error = responseMap.get(McpProtocol.FIELD_ERROR);
		assertTrue(result != null || error != null, "Long input should return result or error");
	}

	/**
	 * Query tool should handle null bytes in input.
	 */
	@Test
	public void testQueryNullBytes() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("source", "test\u0000value");
		AMap<AString, ACell> responseMap = makeToolCall("query", args);
		// Should handle gracefully - either succeed or return structured error
		ACell result = responseMap.get(McpProtocol.FIELD_RESULT);
		ACell error = responseMap.get(McpProtocol.FIELD_ERROR);
		assertTrue(result != null || error != null, "Null bytes should be handled gracefully");
	}

	/**
	 * CreateAccount tool should handle invalid hex for account key.
	 */
	@Test
	public void testCreateAccountInvalidHex() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("accountKey", "not-valid-hex!");
		AMap<AString, ACell> responseMap = makeToolCall("createAccount", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNotNull(structured, "Invalid hex should return a structured error");
	}

	/**
	 * Encode tool should handle deeply nested structures.
	 */
	@Test
	public void testEncodeDeeplyNested() throws IOException, InterruptedException {
		StringBuilder nested = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			nested.append("[");
		}
		nested.append("1");
		for (int i = 0; i < 50; i++) {
			nested.append("]");
		}
		AMap<AString, ACell> args = Maps.of("cvx", nested.toString());
		AMap<AString, ACell> responseMap = makeToolCall("encode", args);
		// Should either succeed or fail gracefully
		ACell result = responseMap.get(McpProtocol.FIELD_RESULT);
		ACell error = responseMap.get(McpProtocol.FIELD_ERROR);
		assertTrue(result != null || error != null, "Deeply nested input should be handled");
	}

	/**
	 * Decode tool should handle garbage/invalid CAD3 data.
	 */
	@Test
	public void testDecodeInvalidCAD3() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of("cad3", "0xDEADBEEF");  // Not valid CAD3
		AMap<AString, ACell> responseMap = makeToolCall("decode", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNotNull(structured, "Invalid CAD3 should return a structured error");
	}

	/**
	 * Sign tool should handle empty seed.
	 */
	@Test
	public void testSignEmptySeed() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of(
			"value", VALUE_HELLO,
			"seed", ""
		);
		AMap<AString, ACell> responseMap = makeToolCall("sign", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNotNull(structured, "Empty seed should return a structured error");
	}

	/**
	 * Validate tool should handle empty signature.
	 */
	@Test
	public void testValidateEmptySignature() throws IOException, InterruptedException {
		AString publicKeyHex = generateKeyPair(SEED_TEST);
		AMap<AString, ACell> args = Maps.of(
			"publicKey", publicKeyHex,
			"signature", "",
			"bytes", VALUE_HELLO
		);
		AMap<AString, ACell> responseMap = makeToolCall("validate", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNotNull(structured, "Empty signature should return a structured error");
	}

	/**
	 * Prepare tool should handle invalid address format.
	 */
	@Test
	public void testPrepareInvalidAddress() throws IOException, InterruptedException {
		AMap<AString, ACell> args = Maps.of(
			"source", "(* 2 3)",
			"address", "not-an-address"
		);
		AMap<AString, ACell> responseMap = makeToolCall("prepare", args);
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNotNull(structured, "Invalid address should return a structured error");
	}

	/**
	 * Invalid JSON-RPC request should return Parse Error.
	 */
	@Test
	public void testInvalidJson() throws IOException, InterruptedException {
		HttpResponse<String> response = post(MCP_PATH, "{invalid json}");
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response");
		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);

		AMap<AString, ACell> error = RT.ensureMap(responseMap.get(McpProtocol.FIELD_ERROR));
		assertNotNull(error, "Invalid JSON should return an error");
		assertEquals(CVMLong.create(-32700), error.get(McpProtocol.FIELD_CODE), "Should be Parse Error (-32700)");
	}

	/**
	 * Request without jsonrpc version field should still be processed (lenient).
	 * MCP doesn't strictly validate jsonrpc version for interoperability.
	 */
	@Test
	public void testMissingJsonrpcVersion() throws IOException, InterruptedException {
		String request = "{\"method\": \"initialize\", \"params\": {}, \"id\": \"test\"}";
		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);

		// Should still succeed with result (lenient parsing)
		ACell result = responseMap.get(McpProtocol.FIELD_RESULT);
		assertNotNull(result, "Initialize should succeed even without jsonrpc version field");
	}

	/**
	 * Request with wrong jsonrpc version should still be processed (lenient).
	 * MCP doesn't strictly validate jsonrpc version for interoperability.
	 */
	@Test
	public void testWrongJsonrpcVersion() throws IOException, InterruptedException {
		String request = "{\"jsonrpc\": \"1.0\", \"method\": \"initialize\", \"params\": {}, \"id\": \"test\"}";
		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);

		// Should still succeed with result (lenient parsing)
		ACell result = responseMap.get(McpProtocol.FIELD_RESULT);
		assertNotNull(result, "Initialize should succeed even with wrong jsonrpc version");
	}

	// ===== SSE and Session tests =====

	/**
	 * Helper to send a POST to /mcp with custom Accept header.
	 */
	private HttpResponse<String> postWithAccept(String url, String jsonBody, String accept) throws IOException, InterruptedException {
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", accept)
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * POST with Accept: text/event-stream (SSE-only) should return SSE response.
	 */
	@Test
	public void testSseResponseOnPost() throws IOException, InterruptedException {
		String request = "{\"jsonrpc\": \"2.0\", \"method\": \"initialize\", \"params\": {}, \"id\": \"sse-1\"}";
		HttpResponse<String> response = postWithAccept(MCP_PATH, request, "text/event-stream");
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
				"Should return SSE content type");

		String body = response.body();
		assertTrue(body.contains("event: message"), "SSE response should contain event: message");
		assertTrue(body.contains("data: "), "SSE response should contain data: prefix");
		assertTrue(body.contains("\"protocolVersion\""), "SSE data should contain initialize result");
	}

	/**
	 * POST with Accept: application/json should return JSON (not SSE).
	 */
	@Test
	public void testJsonResponseOnPost() throws IOException, InterruptedException {
		String request = "{\"jsonrpc\": \"2.0\", \"method\": \"initialize\", \"params\": {}, \"id\": \"json-1\"}";
		HttpResponse<String> response = postWithAccept(MCP_PATH, request, "application/json");
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),
				"Should return JSON content type");

		// Should be valid JSON (not SSE)
		ACell parsed = JSON.parse(response.body());
		assertNotNull(parsed, "Response body should be valid JSON");
		AMap<AString, ACell> map = RT.ensureMap(parsed);
		assertNotNull(map.get(McpProtocol.FIELD_RESULT), "Should have result");
	}

	/**
	 * POST with Accept: application/json, text/event-stream should prefer JSON.
	 */
	@Test
	public void testPreferJsonWhenBothAccepted() throws IOException, InterruptedException {
		String request = "{\"jsonrpc\": \"2.0\", \"method\": \"initialize\", \"params\": {}, \"id\": \"both-1\"}";
		HttpResponse<String> response = postWithAccept(MCP_PATH, request, "application/json, text/event-stream");
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"),
				"Should prefer JSON when both are accepted");
	}

	/**
	 * Initialize should return Mcp-Session-Id header.
	 */
	@Test
	public void testInitializeReturnsSessionId() throws IOException, InterruptedException {
		String request = "{\"jsonrpc\": \"2.0\", \"method\": \"initialize\", \"params\": {}, \"id\": \"sess-1\"}";
		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
		assertNotNull(sessionId, "Initialize should return Mcp-Session-Id header");
		assertFalse(sessionId.isEmpty(), "Session ID should not be empty");
	}

	/**
	 * DELETE /mcp with valid session should terminate the connection.
	 */
	@Test
	public void testDeleteSession() throws Exception {
		// Open a GET /mcp stream — this creates the McpConnection
		try (SseSession session = openSseSession()) {
			// Delete the session
			java.net.http.HttpRequest deleteRequest = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create(MCP_PATH))
					.header("Mcp-Session-Id", session.id())
					.DELETE()
					.build();
			HttpResponse<String> deleteResponse = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, deleteResponse.statusCode());

			// Deleting again should return 404 (already removed)
			HttpResponse<String> secondDelete = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
			assertEquals(404, secondDelete.statusCode());
		}
	}

	/**
	 * DELETE /mcp without session ID should return 400.
	 */
	@Test
	public void testDeleteWithoutSessionId() throws IOException, InterruptedException {
		java.net.http.HttpRequest deleteRequest = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(MCP_PATH))
				.DELETE()
				.build();
		HttpResponse<String> response = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
		assertEquals(400, response.statusCode());
	}

	/**
	 * GET /mcp without Accept: text/event-stream should return 405.
	 */
	@Test
	public void testGetWithoutSseAccept() throws IOException, InterruptedException {
		HttpResponse<String> response = get(MCP_PATH);
		assertEquals(405, response.statusCode());
	}

	/**
	 * GET /mcp with SSE Accept but no session should generate a session ID
	 * and open a connection.
	 */
	@Test
	public void testGetSseOpensConnection() throws Exception {
		try (SseSession session = openSseSession()) {
			assertNotNull(session.id(), "GET /mcp should return Mcp-Session-Id");
			assertFalse(session.id().isEmpty(), "Session ID should not be empty");
		}
	}

	/**
	 * Notification should return 202 with application/json content type.
	 */
	@Test
	public void testNotificationReturns202() throws IOException, InterruptedException {
		// Notification has no "id" field
		String request = "{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\", \"params\": {}}";
		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(202, response.statusCode());
	}

	// ===== watchState / unwatchState tests =====

	/**
	 * Record holding an SSE-backed session: the session ID and the background thread
	 * that keeps the SSE connection alive.
	 */
	private record SseSession(String id, Thread thread) implements AutoCloseable {
		@Override public void close() { thread.interrupt(); }
	}

	/**
	 * Open an SSE connection (GET /mcp) in a background thread and return the
	 * session ID from the response header. The session lives until {@link SseSession#close()}.
	 */
	private SseSession openSseSession() throws Exception {
		var sessionIdHolder = new java.util.concurrent.CompletableFuture<String>();
		Thread sseThread = Thread.ofVirtual().start(() -> {
			try {
				var req = java.net.http.HttpRequest.newBuilder()
						.uri(java.net.URI.create(MCP_PATH))
						.header("Accept", "text/event-stream")
						.GET()
						.build();
				httpClient.send(req, responseInfo -> {
					String sid = responseInfo.headers().firstValue("Mcp-Session-Id").orElse(null);
					sessionIdHolder.complete(sid);
					// Return a discarding subscriber that keeps the connection open
					return HttpResponse.BodySubscribers.ofInputStream();
				});
			} catch (Exception e) {
				sessionIdHolder.completeExceptionally(e);
			}
		});
		String sessionId = sessionIdHolder.get(5, java.util.concurrent.TimeUnit.SECONDS);
		assertNotNull(sessionId, "SSE response should include Mcp-Session-Id header");
		return new SseSession(sessionId, sseThread);
	}

	/**
	 * Open an SSE session and return the session ID for use in tool calls.
	 * The background SSE thread keeps the connection alive.
	 */
	private String initSession() throws Exception {
		return openSseSession().id();
	}

	/**
	 * Helper to make a tool call with a session header.
	 */
	private AMap<AString, ACell> makeToolCallWithSession(String toolName, AMap<AString, ACell> arguments, String sessionId)
			throws IOException, InterruptedException {
		AMap<AString, ACell> params = Maps.of(
			"name", toolName,
			"arguments", (arguments != null) ? arguments : Maps.empty()
		);
		AMap<AString, ACell> request = Maps.of(
			"jsonrpc", "2.0",
			"method", "tools/call",
			"params", params,
			"id", "test-" + toolName
		);
		java.net.http.HttpRequest httpReq = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(MCP_PATH))
				.header("Content-Type", "application/json")
				.header("Mcp-Session-Id", sessionId)
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(JSON.toString(request)))
				.build();
		HttpResponse<String> response = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		ACell parsed = JSON.parse(response.body());
		return RT.ensureMap(parsed);
	}

	// ===== queryState tool =====

	@Test
	public void testQueryStateBasic() throws Exception {
		// Query a known path — accounts vector indexed by integer, not address
		AMap<AString, ACell> args = Maps.of("path", "[:accounts 0 :balance]");
		AMap<AString, ACell> responseMap = makeToolCall("queryState", args);

		AMap<AString, ACell> structured = expectResult(responseMap);
		assertEquals(CVMBool.TRUE, structured.get(Strings.create("exists")));
		assertNotNull(structured.get(Strings.create("value")));
	}

	@Test
	public void testQueryStateNonExistentPath() throws Exception {
		AMap<AString, ACell> args = Maps.of("path", "[:accounts 999999999 :balance]");
		AMap<AString, ACell> responseMap = makeToolCall("queryState", args);

		AMap<AString, ACell> structured = expectResult(responseMap);
		assertEquals(CVMBool.FALSE, structured.get(Strings.create("exists")));
	}

	@Test
	public void testQueryStateInvalidPath() throws Exception {
		// Empty vector
		AMap<AString, ACell> args = Maps.of("path", "[]");
		AMap<AString, ACell> responseMap = makeToolCall("queryState", args);
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));

		// Not a vector
		args = Maps.of("path", "42");
		responseMap = makeToolCall("queryState", args);
		result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));

		// Missing path
		responseMap = makeToolCall("queryState", null);
		result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	@Test
	public void testQueryStateAdversarial() throws Exception {
		// Navigate into a non-collection (balance is a number, can't key into it)
		AMap<AString, ACell> args = Maps.of("path", "[:accounts 0 :balance :foo]");
		AMap<AString, ACell> responseMap = makeToolCall("queryState", args);
		AMap<AString, ACell> structured = expectResult(responseMap);
		assertEquals(CVMBool.FALSE, structured.get(Strings.create("exists")));

		// Very deep nonsense path
		args = Maps.of("path", "[:accounts 0 :balance :a :b :c :d :e :f]");
		responseMap = makeToolCall("queryState", args);
		structured = expectResult(responseMap);
		assertEquals(CVMBool.FALSE, structured.get(Strings.create("exists")));

		// Path through non-existent intermediate key
		args = Maps.of("path", "[:nonexistent :foo :bar]");
		responseMap = makeToolCall("queryState", args);
		structured = expectResult(responseMap);
		assertEquals(CVMBool.FALSE, structured.get(Strings.create("exists")));

		// Single key that doesn't exist in state
		args = Maps.of("path", "[:nonexistent]");
		responseMap = makeToolCall("queryState", args);
		structured = expectResult(responseMap);
		assertEquals(CVMBool.FALSE, structured.get(Strings.create("exists")));

		// Malformed CVM expression
		args = Maps.of("path", "[this is not valid {{{");
		responseMap = makeToolCall("queryState", args);
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));

		// String instead of vector
		args = Maps.of("path", "\"hello\"");
		responseMap = makeToolCall("queryState", args);
		result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	@Test
	public void testQueryStateNoSessionRequired() throws Exception {
		// queryState should work without a session (unlike watchState)
		AMap<AString, ACell> args = Maps.of("path", "[:accounts 0 :balance]");
		AMap<AString, ACell> responseMap = makeToolCall("queryState", args);

		AMap<AString, ACell> structured = expectResult(responseMap);
		assertEquals(CVMBool.TRUE, structured.get(Strings.create("exists")));
	}

	// ===== watchState / unwatchState tools =====

	@Test
	public void testWatchStateReturnsWatchId() throws Exception {
		String sessionId = initSession();
		assertNotNull(sessionId);

		AMap<AString, ACell> args = Maps.of("path", "[:accounts #0 :balance]");
		AMap<AString, ACell> responseMap = makeToolCallWithSession("watchState", args, sessionId);

		AMap<AString, ACell> structured = expectResult(responseMap);
		assertNotNull(structured.get(Strings.create("watchId")), "Should return watchId");
	}

	@Test
	public void testWatchStateWithoutSession() throws Exception {
		// Call without session header — should get tool error
		AMap<AString, ACell> args = Maps.of("path", "[:accounts #0 :balance]");
		AMap<AString, ACell> responseMap = makeToolCall("watchState", args);

		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	@Test
	public void testWatchStateInvalidPath() throws Exception {
		String sessionId = initSession();

		// Empty vector
		AMap<AString, ACell> args = Maps.of("path", "[]");
		AMap<AString, ACell> responseMap = makeToolCallWithSession("watchState", args, sessionId);
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));

		// Not a vector
		args = Maps.of("path", ":not-a-vector");
		responseMap = makeToolCallWithSession("watchState", args, sessionId);
		result = RT.ensureMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	@Test
	public void testUnwatchStateRemoves() throws Exception {
		String sessionId = initSession();

		// Create a watch
		AMap<AString, ACell> watchArgs = Maps.of("path", "[:accounts #0 :balance]");
		AMap<AString, ACell> watchResponse = makeToolCallWithSession("watchState", watchArgs, sessionId);
		AMap<AString, ACell> watchResult = expectResult(watchResponse);
		String watchId = watchResult.get(Strings.create("watchId")).toString();
		assertNotNull(watchId);

		// Unwatch it
		AMap<AString, ACell> unwatchArgs = Maps.of("watchId", watchId);
		AMap<AString, ACell> unwatchResponse = makeToolCallWithSession("unwatchState", unwatchArgs, sessionId);
		AMap<AString, ACell> unwatchResult = expectResult(unwatchResponse);
		assertEquals(CVMLong.ONE, unwatchResult.get(Strings.create("removed")));

		// Unwatch again — should be 0
		unwatchResponse = makeToolCallWithSession("unwatchState", unwatchArgs, sessionId);
		unwatchResult = expectResult(unwatchResponse);
		assertEquals(CVMLong.ZERO, unwatchResult.get(Strings.create("removed")));
	}

	@Test
	public void testUnwatchStateUnknownId() throws Exception {
		String sessionId = initSession();

		AMap<AString, ACell> args = Maps.of("watchId", "w-nonexistent");
		AMap<AString, ACell> responseMap = makeToolCallWithSession("unwatchState", args, sessionId);
		AMap<AString, ACell> result = expectResult(responseMap);
		assertEquals(CVMLong.ZERO, result.get(Strings.create("removed")));
	}

	@Test
	public void testUnwatchStateByPathPrefix() throws Exception {
		try (SseSession session = openSseSession()) {
			String sessionId = session.id();

			// Create watches under the same account
			expectResult(makeToolCallWithSession("watchState", Maps.of("path", "[:accounts #0 :balance]"), sessionId));
			expectResult(makeToolCallWithSession("watchState", Maps.of("path", "[:accounts #0 :environment]"), sessionId));
			// And one under a different account
			expectResult(makeToolCallWithSession("watchState", Maps.of("path", "[:accounts #1 :balance]"), sessionId));

			// Remove all watches for account #0 by path prefix vector
			AMap<AString, ACell> unwatchResponse = makeToolCallWithSession("unwatchState",
					Maps.of("path", "[:accounts #0]"), sessionId);
			AMap<AString, ACell> unwatchResult = expectResult(unwatchResponse);
			assertEquals(CVMLong.create(2), unwatchResult.get(Strings.create("removed")));

			// Account #1 watch should still be there — remove by its prefix
			unwatchResponse = makeToolCallWithSession("unwatchState",
					Maps.of("path", "[:accounts #1]"), sessionId);
			unwatchResult = expectResult(unwatchResponse);
			assertEquals(CVMLong.ONE, unwatchResult.get(Strings.create("removed")));
		}
	}

	@Test
	public void testUnwatchStateRequiresParam() throws Exception {
		String sessionId = initSession();

		// Neither watchId nor path — should be protocol error
		AMap<AString, ACell> responseMap = makeToolCallWithSession("unwatchState", Maps.empty(), sessionId);
		AMap<AString, ACell> error = RT.ensureMap(responseMap.get(McpProtocol.FIELD_ERROR));
		assertNotNull(error, "Should return protocol error when neither param provided");
	}

	@Test
	public void testDeleteSessionCleansWatches() throws Exception {
		// Open SSE session and create a watch
		SseSession session = openSseSession();
		AMap<AString, ACell> args = Maps.of("path", "[:accounts #0 :balance]");
		AMap<AString, ACell> watchResponse = makeToolCallWithSession("watchState", args, session.id());
		AMap<AString, ACell> watchResult = expectResult(watchResponse);
		String watchId = watchResult.get(Strings.create("watchId")).toString();

		// Delete session — should destroy the connection and all its watches
		java.net.http.HttpRequest deleteRequest = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(MCP_PATH))
				.header("Mcp-Session-Id", session.id())
				.DELETE()
				.build();
		HttpResponse<String> deleteResponse = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, deleteResponse.statusCode());
		session.close();

		// Open a new session and try to unwatch — should return 0 (already cleaned up)
		String newSessionId = initSession();
		AMap<AString, ACell> unwatchArgs = Maps.of("watchId", watchId);
		AMap<AString, ACell> unwatchResponse = makeToolCallWithSession("unwatchState", unwatchArgs, newSessionId);
		AMap<AString, ACell> unwatchResult = expectResult(unwatchResponse);
		assertEquals(CVMLong.ZERO, unwatchResult.get(Strings.create("removed")));
	}
}
