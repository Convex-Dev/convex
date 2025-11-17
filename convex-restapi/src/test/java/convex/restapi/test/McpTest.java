package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.init.Init;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.api.McpAPI;

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
		assertEquals(Strings.create("init-1"), responseMap.get(McpAPI.FIELD_ID));

		ACell resultCell = responseMap.get(McpAPI.FIELD_RESULT);
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
		String request = "{\n"
			+ "  \"jsonrpc\": \"2.0\",\n"
			+ "  \"method\": \"does/not/exist\",\n"
			+ "  \"params\": {},\n"
			+ "  \"id\": \"bad-1\"\n"
			+ "}";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response but got " + RT.getType(parsed));

		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		assertEquals(Strings.create("bad-1"), responseMap.get(McpAPI.FIELD_ID));

		ACell errorCell = responseMap.get(McpAPI.FIELD_ERROR);
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
		AMap<AString, ACell> responseMap = makeToolCall("query", "{ \"source\": \"*balance*\" }");
		AMap<AString, ACell> structured = expectResult(responseMap);
		assertNotNull(RT.getIn(structured, "value"));
	}

	/**
	 * Prepare tool should mirror the REST transaction preparation response and
	 * return encoded data plus metadata for signing.
	 */
	@Test
	public void testPrepareTool() throws IOException, InterruptedException {
		String args = "{ \"source\": \"(* 2 3)\", \"address\": \"#11\" }";
		AMap<AString, ACell> responseMap = makeToolCall("prepare", args);
		AMap<AString, ACell> structured = expectResult(responseMap);
		assertNotNull(RT.ensureString(RT.getIn(structured, "hash")));
		assertNotNull(RT.ensureString(RT.getIn(structured, "data")));
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
			Strings.create("source"), source,
			Strings.create("address"), addressString
		);

		AMap<AString, ACell> responseMap = makeToolCall("prepare", JSON.toString(mcpArgs));
		AMap<AString, ACell> structured = expectResult(responseMap);
		AString mcpHash = RT.ensureString(RT.getIn(structured, "hash"));
		assertNotNull(mcpHash);

		AMap<AString, ACell> requestMap = Maps.of(
			Strings.create("address"), addressString,
			Strings.create("source"), source
		);
		HttpResponse<String> restResponse = post(API_PATH + "/transaction/prepare", JSON.toString(requestMap));
		assertEquals(200, restResponse.statusCode());
		ACell restParsed = JSON.parse(restResponse.body());
		AMap<AString, ACell> restMap = RT.ensureMap(restParsed);
		assertNotNull(restMap);
		AString restHash = RT.ensureString(RT.getIn(restMap, "hash"));
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
		String encodeArgs = "{ \"cvx\": \"" + cvxLiteral.replace("\"", "\\\"") + "\" }";
		AMap<AString, ACell> encodeResponse = makeToolCall("encode", encodeArgs);
		AMap<AString, ACell> encodeResult = expectResult(encodeResponse);
		AString cad3 = RT.ensureString(RT.getIn(encodeResult, "cad3"));
		assertNotNull(cad3);
		assertEquals(expectedHex, cad3.toString().toLowerCase());
		AString hash = RT.ensureString(RT.getIn(encodeResult, "hash"));
		assertNotNull(hash);

		String decodeArgs = "{ \"cad3\": \"" + cad3.toString() + "\" }";
		AMap<AString, ACell> decodeResponse = makeToolCall("decode", decodeArgs);
		AMap<AString, ACell> decodeResult = expectResult(decodeResponse);
		AString cvx = RT.ensureString(RT.getIn(decodeResult, "cvx"));
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
			Strings.create("source"), source,
			Strings.create("address"), addressString
		);

		AMap<AString, ACell> prepareResponse = makeToolCall("prepare", JSON.toString(prepareArgs));
		AMap<AString, ACell> prepared = expectResult(prepareResponse);
		AString hashCell = RT.ensureString(RT.getIn(prepared, "hash"));
		assertNotNull(hashCell);
		Blob hashBlob = Blob.parse(hashCell.toString());

		AString seedHex = Strings.create(KP.getSeed().toHexString());
		AMap<AString, ACell> signArgs = Maps.of(
			Strings.create("value"), hashCell,
			Strings.create("seed"), seedHex
		);
		AMap<AString, ACell> signResponse = makeToolCall("sign", JSON.toString(signArgs));
		AMap<AString, ACell> signed = expectResult(signResponse);
		AString signatureHex = RT.ensureString(RT.getIn(signed, "signature"));
		AString accountKeyHex = RT.ensureString(RT.getIn(signed, "accountKey"));
		assertNotNull(signatureHex);
		assertNotNull(accountKeyHex);
		assertEquals(Strings.create(KP.getAccountKey().toHexString()), accountKeyHex);
		assertEquals(Strings.create(KP.sign(hashBlob).toHexString()), signatureHex);

		AMap<AString, ACell> submitArgs = Maps.of(
			Strings.create("hash"), hashCell,
			Strings.create("signature"), signatureHex,
			Strings.create("accountKey"), accountKeyHex
		);
		AMap<AString, ACell> submitResponse = makeToolCall("submit", JSON.toString(submitArgs));
		AMap<AString, ACell> submitResult = expectResult(submitResponse);
		assertEquals(CVMLong.create(6), RT.getIn(submitResult, "value"));
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
			Strings.create("source"), source,
			Strings.create("address"), addressString,
			Strings.create("seed"), seedHex
		);

		AMap<AString, ACell> transactResponse = makeToolCall("transact", JSON.toString(transactArgs));
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
		AMap<AString, ACell> responseMap = makeToolCall("keyGen", "{}");
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		AString seed = RT.ensureString(RT.getIn(structured, "seed"));
		AString publicKey = RT.ensureString(RT.getIn(structured, "publicKey"));
		
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
		AMap<AString, ACell> keyGenArgs = Maps.of(Strings.create("seed"), SEED_TEST);
		AMap<AString, ACell> responseMap = makeToolCall("keyGen", JSON.toString(keyGenArgs));
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		AString seed = RT.ensureString(RT.getIn(structured, "seed"));
		AString publicKey = RT.ensureString(RT.getIn(structured, "publicKey"));
		
		assertNotNull(seed, "KeyGen should return the seed");
		assertNotNull(publicKey, "KeyGen should return a publicKey");
		AString expectedSeed = Strings.create("0x" + SEED_TEST.toString());
		assertEquals(expectedSeed, seed, "Seed should match the provided seed with 0x prefix");
		
		// Verify the public key is deterministic for the same seed
		AMap<AString, ACell> responseMap2 = makeToolCall("keyGen", JSON.toString(keyGenArgs));
		AMap<AString, ACell> structured2 = expectResult(responseMap2);
		AString publicKey2 = RT.ensureString(RT.getIn(structured2, "publicKey"));
		assertEquals(publicKey, publicKey2, "Same seed should produce same public key");
	}

	/**
	 * KeyGen tool should accept seed input with 0x prefix.
	 */
	@Test
	public void testKeyGenToolWithSeedWithPrefix() throws IOException, InterruptedException {
		AString seedHexWithPrefix = Strings.create("0x" + SEED_TEST.toString());
		AMap<AString, ACell> keyGenArgs = Maps.of(Strings.create("seed"), seedHexWithPrefix);
		
		AMap<AString, ACell> responseMap = makeToolCall("keyGen", JSON.toString(keyGenArgs));
		AMap<AString, ACell> structured = expectResult(responseMap);
		
		AString seed = RT.ensureString(RT.getIn(structured, "seed"));
		AString publicKey = RT.ensureString(RT.getIn(structured, "publicKey"));
		
		assertNotNull(seed, "KeyGen should return the seed");
		assertNotNull(publicKey, "KeyGen should return a publicKey");
		AString expectedSeed = Strings.create("0x" + SEED_TEST.toString());
		assertEquals(expectedSeed, seed, "Seed should match the provided seed with 0x prefix");
		
		// Verify the public key is the same whether input has 0x prefix or not
		AMap<AString, ACell> keyGenArgsNoPrefix = Maps.of(Strings.create("seed"), SEED_TEST);
		AMap<AString, ACell> responseMap2 = makeToolCall("keyGen", JSON.toString(keyGenArgsNoPrefix));
		AMap<AString, ACell> structured2 = expectResult(responseMap2);
		AString publicKey2 = RT.ensureString(RT.getIn(structured2, "publicKey"));
		assertEquals(publicKey, publicKey2, "Same seed with or without 0x prefix should produce same public key");
	}

	/**
	 * Helper method to generate a key pair and return the public key.
	 */
	private AString generateKeyPair(AString seed) throws IOException, InterruptedException {
		AMap<AString, ACell> keyGenArgs = Maps.of(Strings.create("seed"), seed);
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", JSON.toString(keyGenArgs));
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		return RT.ensureString(RT.getIn(keyGenResult, "publicKey"));
	}

	/**
	 * Helper method to sign data with a seed.
	 */
	private AString signData(AString valueHex, AString seedHex) throws IOException, InterruptedException {
		AMap<AString, ACell> signArgs = Maps.of(
			Strings.create("value"), valueHex,
			Strings.create("seed"), seedHex
		);
		AMap<AString, ACell> signResponse = makeToolCall("sign", JSON.toString(signArgs));
		AMap<AString, ACell> signResult = expectResult(signResponse);
		return RT.ensureString(RT.getIn(signResult, "signature"));
	}

	/**
	 * Validate tool should return true for a valid Ed25519 signature.
	 */
	@Test
	public void testValidateToolSuccess() throws IOException, InterruptedException {
		AString publicKeyHex = generateKeyPair(SEED_TEST);
		AString signatureHex = signData(VALUE_HELLO, SEED_TEST);
		
		AMap<AString, ACell> validateArgs = Maps.of(
			Strings.create("publicKey"), publicKeyHex,
			Strings.create("signature"), signatureHex,
			Strings.create("bytes"), VALUE_HELLO
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", JSON.toString(validateArgs));
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
			Strings.create("publicKey"), publicKeyHex,
			Strings.create("signature"), wrongSignatureHex,
			Strings.create("bytes"), VALUE_HELLO
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", JSON.toString(validateArgs));
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
			Strings.create("publicKey"), publicKeyHex,
			Strings.create("signature"), signatureHex,
			Strings.create("bytes"), VALUE_WORLD
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", JSON.toString(validateArgs));
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
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", "{}");
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		AString seedHex = RT.ensureString(RT.getIn(keyGenResult, "seed"));
		AString publicKeyHex = RT.ensureString(RT.getIn(keyGenResult, "publicKey"));
		
		assertNotNull(seedHex, "keyGen should return a seed");
		assertNotNull(publicKeyHex, "keyGen should return a publicKey");
		assertTrue(seedHex.toString().startsWith("0x"), "Seed should start with 0x prefix");
		assertTrue(publicKeyHex.toString().startsWith("0x"), "PublicKey should start with 0x prefix");
		
		// Step 2: Sign some data using the generated seed
		AString valueHex = Strings.create("48656c6c6f20576f726c64");
		AMap<AString, ACell> signArgs = Maps.of(
			Strings.create("value"), valueHex,
			Strings.create("seed"), seedHex
		);
		AMap<AString, ACell> signResponse = makeToolCall("sign", JSON.toString(signArgs));
		AMap<AString, ACell> signResult = expectResult(signResponse);
		AString signatureHex = RT.ensureString(RT.getIn(signResult, "signature"));
		AString accountKeyFromSign = RT.ensureString(RT.getIn(signResult, "accountKey"));
		
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
		AMap<AString, ACell> validateResponse = makeToolCall("validate", JSON.toString(validateArgs));
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
			Strings.create("publicKey"), publicKey2Hex,
			Strings.create("signature"), signatureHex,
			Strings.create("bytes"), VALUE_HELLO
		);
		AMap<AString, ACell> validateResponse = makeToolCall("validate", JSON.toString(validateArgs));
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
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", "{}");
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		AString publicKeyHex = RT.ensureString(RT.getIn(keyGenResult, "publicKey"));
		
		assertNotNull(publicKeyHex, "keyGen should return a publicKey");
		
		AMap<AString, ACell> createAccountArgs = Maps.of(Strings.create("accountKey"), publicKeyHex);
		AMap<AString, ACell> createAccountResponse = makeToolCall("createAccount", JSON.toString(createAccountArgs));
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
		AMap<AString, ACell> keyGenResponse = makeToolCall("keyGen", "{}");
		AMap<AString, ACell> keyGenResult = expectResult(keyGenResponse);
		AString publicKeyHex = RT.ensureString(RT.getIn(keyGenResult, "publicKey"));
		
		assertNotNull(publicKeyHex, "keyGen should return a publicKey");
		
		AMap<AString, ACell> createAccountArgs = Maps.of(
			Strings.create("accountKey"), publicKeyHex,
			Strings.create("faucet"), "1000"
		);
		AMap<AString, ACell> createAccountResponse = makeToolCall("createAccount", JSON.toString(createAccountArgs));
		AMap<AString, ACell> createAccountResult = expectResult(createAccountResponse);
		ACell addressCell = RT.getIn(createAccountResult, "address");
		
		assertNotNull(addressCell, "createAccount should return an address");
		CVMLong address = CVMLong.parse(addressCell);
		assertNotNull(address, "Address should be a valid number");
		assertTrue(address.longValue() > 0, "Address should be a positive number");
	}

	/**
	 * Attempting to call an unregistered tool should surface a protocol error
	 * (same as unknown method) rather than a tool-level error payload.
	 */
	@Test
	public void testToolCallUnknownTool() throws IOException, InterruptedException {
		AMap<AString, ACell> responseMap = makeToolCall("unknown-tool", "{}");
		assertEquals(Strings.create("test-unknown-tool"), responseMap.get(McpAPI.FIELD_ID));
		ACell errorCell = responseMap.get(McpAPI.FIELD_ERROR);
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
			Strings.create("value"), VALUE_HELLO,
			Strings.create("seed"), SEED_TEST
		);
		AMap<AString, ACell> responseMap = makeToolCall("sign", JSON.toString(arguments));
		AMap<AString, ACell> structured = expectResult(responseMap);

		AString signature = RT.ensureString(RT.getIn(structured, "signature"));
		AString accountKey = RT.ensureString(RT.getIn(structured, "accountKey"));
		AString signedValue = RT.ensureString(RT.getIn(structured, "value"));

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
		AMap<AString, ACell> responseMap = makeToolCall("sign", "{ \"value\": \"68656c6c6f\" }");
		AMap<AString, ACell> structured = expectError(responseMap);
		assertNull(RT.getIn(structured, "value"));
	}

	/**
	 * Missing payload should similarly surface as a tool-level error, ensuring
	 * clients know they need to provide the hex data to sign.
	 */
	@Test
	public void testSignMissingValue() throws IOException, InterruptedException {
		AMap<AString, ACell> signArgs = Maps.of(Strings.create("seed"), SEED_TEST);
		AMap<AString, ACell> responseMap = makeToolCall("sign", JSON.toString(signArgs));
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

		assertNull(responseMap.get(McpAPI.FIELD_ID));
		AMap<AString, ACell> error = RT.ensureMap(responseMap.get(McpAPI.FIELD_ERROR));
		assertNotNull(error);
		assertEquals(CVMLong.create(-32600), error.get(McpAPI.FIELD_CODE));
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
		assertNull(errorResponse.get(McpAPI.FIELD_ID));
		AMap<AString, ACell> error = RT.ensureMap(errorResponse.get(McpAPI.FIELD_ERROR));
		assertNotNull(error);
		assertEquals(CVMLong.create(-32600), error.get(McpAPI.FIELD_CODE));
	}

	/**
	 * Utility to issue an MCP tools/call request and get the parsed response as a
	 * Convex map.
	 */
	private AMap<AString, ACell> makeToolCall(String toolName, String argumentsJson) throws IOException, InterruptedException {
		String args;
		if (argumentsJson == null || argumentsJson.isBlank()) {
			args = "{}";
		} else {
			args = argumentsJson;
		}
		String id = "test-" + toolName;
		String request = "{\n"
			+ "  \"jsonrpc\": \"2.0\",\n"
			+ "  \"method\": \"tools/call\",\n"
			+ "  \"params\": {\n"
			+ "    \"name\": \"" + toolName + "\",\n"
			+ "    \"arguments\": " + args + "\n"
			+ "  },\n"
			+ "  \"id\": \"" + id + "\"\n"
			+ "}";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, ()->"Expected map response but got " + RT.getType(parsed));
		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		return responseMap;
	}

	/**
	 * Common assertion path for successful tool calls. Ensures the result wrapper
	 * is present, marks {@code isError == false}, checks that a text payload was
	 * produced for backward compatibility, and returns the structured content map
	 * for further inspection.
	 */
	private AMap<AString, ACell> expectResult(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result, "RPC result missing");
		assertEquals(CVMBool.FALSE, result.get(McpAPI.FIELD_IS_ERROR));

		AVector<ACell> content = RT.ensureVector(result.get(McpAPI.FIELD_CONTENT));
		assertNotNull(content);
		assertTrue(content.count() > 0);
		AMap<AString, ACell> textEntry = RT.ensureMap(content.get(0));
		assertNotNull(textEntry.get(McpAPI.FIELD_TEXT));
		AMap<AString, ACell> structured =RT.ensureMap(result.get(McpAPI.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}

	/**
	 * Common assertion path for tool failures. Ensures the JSON-RPC call succeeded
	 * but the structured content indicates an error payload that tests can read.
	 */
	private AMap<AString, ACell> expectError(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpAPI.FIELD_IS_ERROR));
		AMap<AString, ACell> structured = RT.ensureMap(result.get(McpAPI.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}
}
