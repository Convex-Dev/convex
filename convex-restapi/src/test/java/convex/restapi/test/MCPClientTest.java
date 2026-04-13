package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Tests core MCP tools via the official MCP SDK client.
 *
 * Each test exercises a distinct tool path through the MCP protocol stack.
 * Signing-service tools are covered separately in SigningMcpClientTest.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class MCPClientTest extends ARESTTest {

	private static final String MCP_URL = HOST_PATH + "/mcp";

	private McpSyncClient mcp;

	// Shared funded account for transaction tests
	private String testSeed;
	private String testPublicKey;
	private int testAddr;

	@BeforeAll
	public void setupClient() {
		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(MCP_URL).build();
		mcp = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(10))
				.build();
		mcp.initialize();

		// Create a reusable funded account
		Map<String, Object> key = callToolOK("keyGen", Map.of());
		testSeed = (String) key.get("seed");
		testPublicKey = (String) key.get("publicKey");
		Map<String, Object> acct = callToolOK("createAccount",
				Map.of("accountKey", testPublicKey, "faucet", "1000000000"));
		testAddr = ((Number) acct.get("address")).intValue();
	}

	// ===== Helpers =====

	private CallToolResult callTool(String name, Map<String, Object> args) {
		return mcp.callTool(CallToolRequest.builder().name(name).arguments(args).build());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> callToolOK(String name, Map<String, Object> args) {
		CallToolResult r = callTool(name, args);
		assertNotNull(r);
		assertFalse(r.isError() != null && r.isError(), name + " should not be error");
		Map<String, Object> data = (Map<String, Object>) r.structuredContent();
		assertNotNull(data, name + " should have structured content");
		return data;
	}

	private CallToolResult callToolExpectError(String name, Map<String, Object> args) {
		CallToolResult r = callTool(name, args);
		assertNotNull(r);
		assertTrue(r.isError() != null && r.isError(), name + " should be error");
		return r;
	}

	// ===== Protocol =====

	@Test
	public void testProtocol() {
		String negotiated = mcp.getCurrentInitializationResult().protocolVersion();
		assertTrue(convex.restapi.mcp.McpServer.SUPPORTED_PROTOCOL_VERSIONS.contains(negotiated),
				"Negotiated protocol version should be supported: " + negotiated);
		mcp.ping();
	}

	@Test
	public void testListCoreTools() {
		ListToolsResult lr = mcp.listTools();
		List<Tool> tools = lr.tools();
		assertTrue(tools.size() > 0);

		// All core (non-signing) tools should be present
		String[] expected = {
			"query", "transact", "prepare", "submit", "signAndSubmit",
			"sign", "validate", "keyGen", "hash", "encode", "decode",
			"peerStatus", "createAccount", "describeAccount",
			"lookup", "resolveCNS", "getTransaction",
			"getBalance", "transfer",
			"watchState", "unwatchState"
		};
		for (String name : expected) {
			assertTrue(tools.stream().anyMatch(t -> name.equals(t.name())),
					"Should find tool: " + name);
		}

		// Every tool should have description and input schema
		for (Tool tool : tools) {
			assertNotNull(tool.description(), tool.name() + " needs description");
			assertNotNull(tool.inputSchema(), tool.name() + " needs inputSchema");
		}
	}

	// ===== Read-only query paths =====

	@Test
	public void testQuery() {
		// Basic expression
		Map<String, Object> data = callToolOK("query", Map.of("source", "(+ 1 2)"));
		assertEquals(3, ((Number) data.get("value")).intValue());

		// With address context
		Map<String, Object> bal = callToolOK("query",
				Map.of("source", "*balance*", "address", "#" + testAddr));
		assertNotNull(bal.get("value"));

		// CVM error path — tool returns isError=true with errorCode in structured content
		CallToolResult errResult = callTool("query", Map.of("source", "(fail :ASSERT)"));
		assertTrue(errResult.isError() != null && errResult.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> errData = (Map<String, Object>) errResult.structuredContent();
		assertNotNull(errData.get("errorCode"));
	}

	// ===== Utility tools =====

	@Test
	public void testKeyGen() {
		// Random
		Map<String, Object> k = callToolOK("keyGen", Map.of());
		String seed = (String) k.get("seed");
		assertTrue(seed.startsWith("0x") && seed.length() == 66, "Seed should be 0x + 64 hex");
		assertNotNull(k.get("publicKey"));

		// Deterministic — same seed gives same key
		String fixed = "0x0000000000000000000000000000000000000000000000000000000000000001";
		Map<String, Object> d1 = callToolOK("keyGen", Map.of("seed", fixed));
		Map<String, Object> d2 = callToolOK("keyGen", Map.of("seed", fixed));
		assertEquals(d1.get("publicKey"), d2.get("publicKey"));
	}

	@Test
	public void testHashAlgorithms() {
		Map<String, Object> sha3 = callToolOK("hash", Map.of("value", "hello"));
		assertNotNull(sha3.get("hash"));

		Map<String, Object> sha256 = callToolOK("hash",
				Map.of("value", "hello", "algorithm", "sha256"));
		assertNotNull(sha256.get("hash"));
		assertEquals("sha256", sha256.get("algorithm"));
	}

	@Test
	public void testEncodeDecode() {
		Map<String, Object> enc = callToolOK("encode", Map.of("cvx", "(+ 1 2)"));
		String cad3 = (String) enc.get("cad3");
		assertNotNull(cad3);

		Map<String, Object> dec = callToolOK("decode", Map.of("cad3", cad3));
		assertNotNull(dec.get("cvx"));
	}

	@Test
	public void testSignAndValidate() {
		Map<String, Object> key = callToolOK("keyGen", Map.of());
		String seed = (String) key.get("seed");
		String pk = (String) key.get("publicKey");

		// Sign
		Map<String, Object> signed = callToolOK("sign",
				Map.of("value", "deadbeef", "seed", seed));
		String sig = (String) signed.get("signature");
		assertNotNull(sig);

		// Valid signature
		Map<String, Object> valid = callToolOK("validate",
				Map.of("publicKey", pk, "signature", sig, "bytes", "deadbeef"));
		assertEquals(true, valid.get("value"));

		// Tampered bytes — should fail
		Map<String, Object> invalid = callToolOK("validate",
				Map.of("publicKey", pk, "signature", sig, "bytes", "cafebabe"));
		assertEquals(false, invalid.get("value"));
	}

	// ===== Account management =====

	@Test
	public void testCreateAccountAndDescribe() {
		Map<String, Object> key = callToolOK("keyGen", Map.of());

		// Create with faucet — check tx hash in info
		Map<String, Object> created = callToolOK("createAccount",
				Map.of("accountKey", (String) key.get("publicKey"), "faucet", "100000000"));
		int addr = ((Number) created.get("address")).intValue();

		@SuppressWarnings("unchecked")
		Map<String, Object> info = (Map<String, Object>) created.get("info");
		assertNotNull(info, "Should have info");
		assertNotNull(info.get("tx"), "Should have tx hash");

		// Describe the new account
		Map<String, Object> desc = callToolOK("describeAccount",
				Map.of("address", "#" + addr));
		assertNotNull(desc.get("accountInfo"));
	}

	@Test
	public void testCreateAccountSelfSovereign() {
		Map<String, Object> key = callToolOK("keyGen", Map.of());
		Map<String, Object> created = callToolOK("createAccount",
				Map.of("accountKey", (String) key.get("publicKey"), "controller", "nil"));
		assertNotNull(created.get("address"));
	}

	// ===== Balance and transfer =====

	@Test
	public void testGetBalance() {
		Map<String, Object> data = callToolOK("getBalance",
				Map.of("address", "#" + testAddr));
		assertNotNull(data.get("balance"));
		assertEquals(testAddr, ((Number) data.get("address")).intValue());
	}

	@Test
	public void testTransferCVM() {
		// Create destination
		Map<String, Object> key2 = callToolOK("keyGen", Map.of());
		Map<String, Object> dest = callToolOK("createAccount",
				Map.of("accountKey", (String) key2.get("publicKey")));
		int to = ((Number) dest.get("address")).intValue();

		// Transfer from shared account
		Map<String, Object> result = callToolOK("transfer", Map.of(
				"address", "#" + testAddr,
				"to", "#" + to,
				"amount", 1000,
				"seed", testSeed));

		@SuppressWarnings("unchecked")
		Map<String, Object> info = (Map<String, Object>) result.get("info");
		assertNotNull(info.get("tx"), "Transfer should return tx hash");

		// Verify destination balance
		Map<String, Object> bal = callToolOK("getBalance", Map.of("address", "#" + to));
		assertEquals(1000, ((Number) bal.get("balance")).intValue());
	}

	@Test
	public void testFungibleTokenBalance() {
		// Deploy a fungible token from shared account
		Map<String, Object> deployed = callToolOK("transact", Map.of(
				"source", "(deploy (@convex.fungible/build-token {:supply 1000000}))",
				"seed", testSeed,
				"address", "#" + testAddr));
		int tokenAddr = ((Number) deployed.get("value")).intValue();

		// Check token balance via getBalance
		Map<String, Object> bal = callToolOK("getBalance",
				Map.of("address", "#" + testAddr, "token", "#" + tokenAddr));
		assertNotNull(bal.get("balance"));
		assertEquals(tokenAddr, ((Number) bal.get("token")).intValue());
	}

	// ===== Transaction flows =====

	@Test
	public void testPrepareSignSubmit() {
		// 3-step flow: prepare → sign → submit
		Map<String, Object> prepared = callToolOK("prepare",
				Map.of("source", "(+ 10 20)", "address", "#" + testAddr));
		String hash = (String) prepared.get("hash");
		assertNotNull(hash);

		Map<String, Object> signed = callToolOK("sign",
				Map.of("value", hash, "seed", testSeed));
		String sig = (String) signed.get("signature");

		Map<String, Object> submitted = callToolOK("submit", Map.of(
				"hash", hash, "accountKey", testPublicKey, "sig", sig));
		assertEquals(30, ((Number) submitted.get("value")).intValue());
	}

	@Test
	public void testSignAndSubmit() {
		// 2-step flow: prepare → signAndSubmit
		Map<String, Object> prepared = callToolOK("prepare",
				Map.of("source", "(str :a :b)", "address", "#" + testAddr));
		String hash = (String) prepared.get("hash");

		Map<String, Object> result = callToolOK("signAndSubmit",
				Map.of("hash", hash, "seed", testSeed));
		assertEquals(":a:b", result.get("value").toString());
	}

	@Test
	public void testTransactAndGetTransaction() {
		// Transact and then look up the tx by hash
		Map<String, Object> txResult = callToolOK("transact", Map.of(
				"source", "(* 6 7)",
				"seed", testSeed,
				"address", "#" + testAddr));
		assertEquals(42, ((Number) txResult.get("value")).intValue());

		@SuppressWarnings("unchecked")
		Map<String, Object> info = (Map<String, Object>) txResult.get("info");
		String txHash = (String) info.get("tx");
		assertNotNull(txHash);

		// Look up the transaction
		Map<String, Object> looked = callToolOK("getTransaction", Map.of("hash", txHash));
		assertEquals(true, looked.get("found"));
		assertNotNull(looked.get("tx"));
		assertNotNull(looked.get("result"));
	}

	// ===== Name resolution =====

	@Test
	public void testLookupAndResolveCNS() {
		// Lookup existing symbol
		Map<String, Object> found = callToolOK("lookup",
				Map.of("address", "@convex.core", "symbol", "count"));
		assertEquals(true, found.get("exists"));

		// Lookup non-existent
		Map<String, Object> notFound = callToolOK("lookup",
				Map.of("address", "@convex.core", "symbol", "nonexistent-symbol-xyz"));
		assertEquals(false, notFound.get("exists"));

		// Resolve CNS name
		Map<String, Object> cns = callToolOK("resolveCNS", Map.of("name", "convex.core"));
		assertEquals(true, cns.get("exists"));

		// Non-existent CNS name
		Map<String, Object> noCns = callToolOK("resolveCNS",
				Map.of("name", "nonexistent.name.xyz"));
		assertEquals(false, noCns.get("exists"));
	}

	@Test
	public void testPeerStatus() {
		Map<String, Object> data = callToolOK("peerStatus", Map.of());
		assertNotNull(data.get("status"));
	}

	// ===== Error paths =====

	@Test
	public void testMissingRequiredParams() {
		// Per MCP 2025-11-25: tool input validation errors are returned as tool
		// results with isError=true (not JSON-RPC errors), enabling LLM self-correction.
		callToolExpectError("query", Map.of());
		callToolExpectError("getBalance", Map.of());
	}

	@Test
	public void testTransferInsufficientFunds() {
		// Create a nearly empty account
		Map<String, Object> key = callToolOK("keyGen", Map.of());
		Map<String, Object> acct = callToolOK("createAccount",
				Map.of("accountKey", (String) key.get("publicKey"), "faucet", "100"));
		int broke = ((Number) acct.get("address")).intValue();

		// Attempt to transfer more than balance
		CallToolResult r = callTool("transfer", Map.of(
				"address", "#" + broke,
				"to", "#12",
				"amount", 999999999,
				"seed", (String) key.get("seed")));
		assertNotNull(r);
		// CVM returns error in structured content
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) r.structuredContent();
		if (data != null) {
			assertNotNull(data.get("errorCode"), "Should have CVM error for insufficient funds");
		}
	}
}
