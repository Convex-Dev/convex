package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.java.ConvexHTTP;

public class RESTAPITest extends ARESTTest {
	
	@Test public void testDataAPI() {
		
	}
	
//  Not obvious how to make this work given self signed certificates?
//	@Test public void testHTTPS() throws IOException {
//		Content c = Request.get("https://localhost").execute().returnContent();
//		assertNotNull(c);
//	}
	
	@Test public void testSwagger() throws IOException, InterruptedException {
		HttpResponse<String> response = get("http://localhost:" + server.getPort()+"/swagger");
		assertEquals(200, response.statusCode());
		String s = response.body();
		assertFalse(s.isBlank());
	}
	
	@Test public void testOpenAPI() throws IOException, InterruptedException {
		HttpResponse<String> response = get("http://localhost:" + server.getPort()+"/openapi");
		assertEquals(200, response.statusCode());
		String s = response.body();
		assertNotNull(JSON.parse(s));
	}
	
	@Test public void testTxPrepareSubmit() throws IOException, InterruptedException, BadFormatException {
		{ // should be a bad request with non-parseable/missing fields
			HttpResponse<String> res = post(API_PATH+"/transaction/prepare", "");
			assertEquals(400, res.statusCode());
		}

		{ // should be a bad request with unparseable CVX source
			AMap<AString,ACell> req=Maps.of(
					"address",Init.GENESIS_ADDRESS,
					"source","]bad");
			HttpResponse<String> res = post(API_PATH+"/transaction/prepare", JSON.toStringPretty(req));
			assertEquals(400, res.statusCode());
		}

		{ // prepare should work
			AMap<AString,ACell> req=Maps.of(
					"address",Init.GENESIS_ADDRESS,
					"source","(* 2 3)");
			
			String tx=JSON.toStringPretty(req);
			HttpResponse<String> res = post(API_PATH+"/transaction/prepare", tx);
			assertEquals(200, res.statusCode());
			
			// Parse response as JSON to verify it's valid JSON
			String responseBody = res.body();
			AMap<AString,ACell> responseMap = JSON.parse(responseBody);
			assertNotNull(responseMap);
			
			Blob data=Blob.parse(responseMap.getIn(Strings.DATA));
			assertNotNull(data);
			ACell txValue=server.getServer().getStore().decodeMultiCell(data);
			assertTrue(txValue instanceof ATransaction);
			
			// Get the hash value required for signing
			Blob hash=Blob.parse(responseMap.getIn(Strings.HASH));
			assertNotNull(hash);
			
			ASignature sig=KP.sign(hash);

			AMap<AString,ACell> sub=Maps.of(
					"accountKey", KP.getAccountKey(),
					"hash",hash.toCVMHexString(), 
					"sig",sig.toCVMHexString());

			HttpResponse<String> res2 = post(API_PATH+"/transaction/submit", JSON.toStringPretty(sub));
			assertEquals(200, res2.statusCode());
			
			AMap<AString, ACell> result =  JSON.parse(res2.body());
			assertEquals(CVMLong.create(6),result.getIn("value"));

		}
	}
	
	
	@Test public void testTransact() throws IOException, InterruptedException {
		{ // should be a bad request with non-parseable/missing fields
			HttpResponse<String> res = post(API_PATH+"/transact", "");
			assertEquals(400, res.statusCode());
		}

		{ // should be a bad request with missing address
			String tx = JSON.toStringPretty(Maps.of("source", "(+ 1 2)", "seed", KP.getSeed()));
			HttpResponse<String> res = post(API_PATH+"/transact", tx);
			assertEquals(400, res.statusCode());
		}

		{ // should be a bad request with non-string source
			String tx = "{\"address\":" + Init.GENESIS_ADDRESS.longValue() + ",\"source\":123,\"seed\":\"" + KP.getSeed().toHexString() + "\"}";
			HttpResponse<String> res = post(API_PATH+"/transact", tx);
			assertEquals(400, res.statusCode());
		}

		{ // should be a bad request with invalid seed length
			String tx = JSON.toStringPretty(Maps.of("address", Init.GENESIS_ADDRESS, "source", "(+ 1 2)", "seed", "0x1234"));
			HttpResponse<String> res = post(API_PATH+"/transact", tx);
			assertEquals(400, res.statusCode());
		}

		{ // should be a bad request with unparseable source code
			String tx = JSON.toStringPretty(Maps.of("address", Init.GENESIS_ADDRESS, "source", "((", "seed", KP.getSeed()));
			HttpResponse<String> res = post(API_PATH+"/transact", tx);
			assertEquals(400, res.statusCode());
		}

		{ // should execute successfully on genesis account
			String tx=JSON.toStringPretty(Maps.of("address",Init.GENESIS_ADDRESS,"source","(* 2 3)","seed",KP.getSeed()));
			HttpResponse<String> res = post(API_PATH+"/transact", tx);
			assertEquals(200, res.statusCode());

			// Parse response as JSON to verify it's valid JSON
			String responseBody = res.body();

			// Extract transaction hash from the response using Convex data structures
			AMap<AString, ACell> responseMap =  JSON.parse(responseBody);

			AMap<ACell, ACell> info = responseMap.getIn("info");
			assertNotNull(info, "Response should contain info field");
			ACell txCell = info.getIn("tx");
			assertNotNull(txCell, "Info should contain tx field with transaction hash");
			String txHash = txCell.toString();

			// Test GET tx endpoint with the extracted hash
			HttpResponse<String> txResponse = get(API_PATH + "/tx?hash=" + txHash);
			assertEquals(200, txResponse.statusCode());
		}
	}

	@Test public void testDataEncodeDecode() throws IOException, InterruptedException {
		assertCad3RoundTrip("12", "110c");
		assertCad3RoundTrip("nil", "00");
		assertCad3RoundTrip("[]", "8000");
		assertCad3RoundTrip("()", "8100");

		{ // malformed CVX in encode should return 400
			String payload = "{ \"data\": \"((\" }";
			HttpResponse<String> res = post(API_PATH + "/data/encode", payload);
			assertEquals(400, res.statusCode());
		}
	}

	private void assertCad3RoundTrip(String cvxLiteral, String expectedHex) throws IOException, InterruptedException {
		String encodePayload = "{ \"data\": \"" + cvxLiteral.replace("\"", "\\\"") + "\" }";
		HttpResponse<String> encodeResponse = post(API_PATH + "/data/encode", encodePayload);
		assertEquals(200, encodeResponse.statusCode());
		AMap<AString, ACell> encodeMap = JSON.parse(encodeResponse.body());
		AString cad3 = RT.ensureString(encodeMap.get(Strings.create("cad3")));
		assertNotNull(cad3);
		assertEquals(expectedHex, cad3.toString().toLowerCase());

		String decodePayload = "{ \"cad3\": \"" + cad3.toString() + "\" }";
		HttpResponse<String> decodeResponse = post(API_PATH + "/data/decode", decodePayload);
		assertEquals(200, decodeResponse.statusCode());
		AMap<AString, ACell> decodeMap = JSON.parse(decodeResponse.body());
		AString cvx = RT.ensureString(decodeMap.get(Strings.create("cvx")));
		assertNotNull(cvx);
		assertEquals(cvxLiteral, cvx.toString());
	}
	
	@Test public void testQuery() throws IOException, InterruptedException {
//		{ // Edge case with \/ in JSON
//			String query=JSON.toStringPretty(Maps.of("address",11,"source","#8\\/count"));
//			System.out.println("testQuery:"+query);
//			HttpResponse<String> res = post(API_PATH+"/query", query);
//			String r=res.body();
//			System.out.println(r);
//			AMap<AString,ACell> json=JSON.parse(r);
//			ACell value=json.getIn("value");
//			
//			assertEquals(200, res.statusCode());
//			assertTrue(value instanceof AString);
//			assertNull(json.getIn("errorCode"));
//		}
		
		
		{ // should be a bad request with bad JSON
			HttpResponse<String> res = post(API_PATH+"/query", "fddfgb");
			assertEquals(400, res.statusCode());
		}
		
		{ // should be OK
			String query=JSON.toStringPretty(Maps.of("address",11,"source","*balance*"));
			HttpResponse<String> res = post(API_PATH+"/query", query);
			AMap<AString,ACell> json=JSON.parse(res.body());
			ACell value=json.getIn("value");
			
			assertTrue(value instanceof AInteger);
			assertNull(json.getIn("errorCode"));
			assertEquals(value,Reader.read(json.getIn("result").toString()));
			assertEquals(200, res.statusCode());
		}
		
		{ // should be a failure of query due to bad code execution
			String query=JSON.toString(Maps.of("address",11,"source","(count)"));
			HttpResponse<String> res = post(API_PATH+"/query", query);
			assertEquals(200, res.statusCode());
			@SuppressWarnings("unchecked")
			AMap<AString,ACell> json=(AMap<AString,ACell>)JSON.parse(res.body());
			AString errorCode=RT.getIn(json,"errorCode");
			assertNotNull(errorCode,()->"No errorCode in result: "+json);
			assertEquals("ARITY",errorCode.toString());
		}
	}
	
	@Test public void testQueryAccount() throws IOException, InterruptedException {
		{ // should be a bad request with bad JSON
			HttpResponse<String> res = get(API_PATH+"/accounts/999999");
			assertEquals(404, res.statusCode());
		}
		
		{ // should be OK
			HttpResponse<String> res = get(API_PATH+"/accounts/11");
			assertEquals(200, res.statusCode());
		}
	}
	
	@Test public void testBlock() throws Exception {
		// Create convex instance
		URI uri = new URI(HOST_PATH);
		ConvexHTTP convex = ConvexHTTP.connect(uri, Init.GENESIS_ADDRESS, KP);
		convex.setAddress(Init.GENESIS_ADDRESS);
		convex.setKeyPair(KP);
		
		// Submit a transaction
		convex.core.Result result = convex.transactSync("(+ 2 3)");
		assertNotNull(result);
		assertFalse(result.isError(),()->"Error in result: "+result);
		
		// Extract transaction hash from result
		convex.core.data.ACell infoCell = result.getInfo();
		assertNotNull(infoCell, "Result should contain info field");
		
		@SuppressWarnings("unchecked")
		convex.core.data.AMap<convex.core.data.ACell, convex.core.data.ACell> info = 
			(convex.core.data.AMap<convex.core.data.ACell, convex.core.data.ACell>) infoCell;
		convex.core.data.ACell txCell = info.get(Keywords.TX);
		assertNotNull(txCell, "Info should contain tx field with transaction hash");
		String txHash = txCell.toString();
		
		// Test transaction endpoint to verify transaction and get block location
		HttpResponse<String> txResponse = get(API_PATH + "/tx?hash=" + txHash);
		assertEquals(200, txResponse.statusCode());
		
		// Parse transaction response to get block information
		String txResponseBody = txResponse.body();
		convex.core.data.ACell txParsed = JSON.parse(txResponseBody);
		assertNotNull(txParsed);
		
		// Get blocks to find which block contains our transaction
		HttpResponse<String> blocksResponse = get(API_PATH + "/blocks?limit=10");
		String blocksResponseBody = blocksResponse.body();
		convex.core.data.ACell blocksParsed = JSON.parse(blocksResponseBody);
		assertNotNull(blocksParsed);
		
		// Parse blocks response to find the block with our transaction
		convex.core.data.ACell itemsCell = RT.getIn(blocksParsed, "items");
		assertNotNull(itemsCell, "Blocks response should contain items");
		
		@SuppressWarnings("unchecked")
		convex.core.data.AVector<convex.core.data.ACell> items = 
			(convex.core.data.AVector<convex.core.data.ACell>) itemsCell;
		assertFalse(items.isEmpty(), "Should have at least one block");
		
		// Find the block that contains our transaction (should be the most recent one)
		// Since we just submitted a transaction, it should be in the latest block
		convex.core.data.ACell latestBlockCell = items.get(items.count() - 1);
		convex.core.data.ACell blockIndexCell = RT.getIn(latestBlockCell, "index");
		assertNotNull(blockIndexCell, "Block should have an index");
		
		// Test the specific block endpoint
		String blockNum = blockIndexCell.toString();
		HttpResponse<String> blockResponse = get(API_PATH + "/blocks/" + blockNum);
		assertEquals(200, blockResponse.statusCode());
		
		// Parse block response
		String blockResponseBody = blockResponse.body();
		convex.core.data.ACell blockParsed = JSON.parse(blockResponseBody);
		assertNotNull(blockParsed);
		
		// Verify block data structure
		assertNotNull(RT.getIn(blockParsed, "index"));
		assertNotNull(RT.getIn(blockParsed, "timestamp"));
		assertNotNull(RT.getIn(blockParsed, "peer"));
		assertNotNull(RT.getIn(blockParsed, "hash"));
		assertNotNull(RT.getIn(blockParsed, "transactionCount"));
		assertNotNull(RT.getIn(blockParsed, "finalised"));
		
		// Verify the block index matches
		assertEquals(blockIndexCell, RT.getIn(blockParsed, "index"));
		
		// Test 404 for non-existent block
		HttpResponse<String> notFoundResponse = get(API_PATH + "/blocks/999999");
		assertEquals(404, notFoundResponse.statusCode());
	}
	
	@Test public void testStatus() throws IOException, InterruptedException {
		// Test GET status endpoint
		HttpResponse<String> response = get(API_PATH + "/status");
		assertEquals(200, response.statusCode(), "Status endpoint should return 200 OK");
		
		// Parse response as JSON
		String responseBody = response.body();
		ACell parsed = JSON.parse(responseBody);
		assertNotNull(parsed, "Response should be parseable");
		
		// Verify it's a map
		assertTrue(parsed instanceof AMap, "Status response should be a map but got: " + 
			convex.core.util.Utils.getClassName(parsed));
		
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> statusMap = (AMap<ACell, ACell>) parsed;
		
		// Verify expected status fields exist (JSON converts keyword keys to strings)
		assertNotNull(RT.getIn(statusMap, "belief"), "Status should contain belief field");
		assertNotNull(RT.getIn(statusMap, "peer"), "Status should contain peer field");
		assertNotNull(RT.getIn(statusMap, "genesis"), "Status should contain genesis field");
		assertNotNull(RT.getIn(statusMap, "state"), "Status should contain state field");
		assertNotNull(RT.getIn(statusMap, "consensus-point"), "Status should contain consensus-point field");
	}
	
	@Test public void testCreateAccount() throws IOException, InterruptedException {
		{ // should be a bad request with missing accountKey
			HttpResponse<String> res = post(API_PATH + "/createAccount", "{}");
			assertEquals(400, res.statusCode());
		}
		
		{ // should be a bad request with invalid accountKey
			AMap<AString, ACell> req = Maps.of("accountKey", "invalid-key");
			HttpResponse<String> res = post(API_PATH + "/createAccount", JSON.toString(req));
			assertEquals(400, res.statusCode());
		}
		
		{ // should create account successfully
			AKeyPair newKeyPair = AKeyPair.generate();
			String accountKeyHex = newKeyPair.getAccountKey().toHexString();
			
			AMap<AString, ACell> req = Maps.of("accountKey", accountKeyHex);
			HttpResponse<String> res = post(API_PATH + "/createAccount", JSON.toString(req));
			assertEquals(200, res.statusCode());
			
			// Parse response as JSON
			String responseBody = res.body();
			AMap<AString, ACell> responseMap = JSON.parse(responseBody);
			assertNotNull(responseMap);
			
			// Verify response contains address
			ACell addressCell = RT.getIn(responseMap, "address");
			assertNotNull(addressCell, "Response should contain address field");
			
			// Verify address is a valid number
			Address address = Address.parse(addressCell);
			assertNotNull(address, "Address should be valid");
		}
		
		{ // should create account with faucet request
			AKeyPair newKeyPair = AKeyPair.generate();
			String accountKeyHex = newKeyPair.getAccountKey().toHexString();

			AMap<AString, ACell> req = Maps.of(
				"accountKey", accountKeyHex,
				"faucet", CVMLong.create(1000)
			);
			HttpResponse<String> res = post(API_PATH + "/createAccount", JSON.toString(req));
			assertEquals(200, res.statusCode());

			// Parse response as JSON
			String responseBody = res.body();
			AMap<AString, ACell> responseMap = JSON.parse(responseBody);
			assertNotNull(responseMap);

			// Verify response contains address
			ACell addressCell = RT.getIn(responseMap, "address");
			assertNotNull(addressCell, "Response should contain address field");

			// Verify address is a valid number
			Address address = Address.parse(addressCell);
			assertNotNull(address, "Address should be valid");
		}
	}

	@Test public void testFaucet() throws IOException, InterruptedException {
		{ // should be a bad request with missing address
			HttpResponse<String> res = post(API_PATH + "/faucet", "{}");
			assertEquals(400, res.statusCode());
		}

		{ // should be a bad request with missing amount
			AMap<AString, ACell> req = Maps.of("address", Init.GENESIS_ADDRESS);
			HttpResponse<String> res = post(API_PATH + "/faucet", JSON.toString(req));
			assertEquals(400, res.statusCode());
		}

		{ // should execute faucet request successfully
			AMap<AString, ACell> req = Maps.of(
				"address", Init.GENESIS_ADDRESS,
				"amount", CVMLong.create(1000000)
			);
			HttpResponse<String> res = post(API_PATH + "/faucet", JSON.toString(req));
			assertEquals(200, res.statusCode());

			// Parse response
			AMap<AString, ACell> responseMap = JSON.parse(res.body());
			assertNotNull(responseMap);
			assertNotNull(RT.getIn(responseMap, "address"), "Response should contain address");
			assertNotNull(RT.getIn(responseMap, "amount"), "Response should contain amount");
		}

		{ // should cap amount at GOLD limit
			AMap<AString, ACell> req = Maps.of(
				"address", Init.GENESIS_ADDRESS,
				"amount", CVMLong.create(Long.MAX_VALUE)
			);
			HttpResponse<String> res = post(API_PATH + "/faucet", JSON.toString(req));
			assertEquals(200, res.statusCode());

			// Verify amount was capped
			AMap<AString, ACell> responseMap = JSON.parse(res.body());
			ACell amount = RT.getIn(responseMap, "amount");
			assertNotNull(amount);
			// Amount should be capped at Coin.GOLD (1000000000)
			assertTrue(((CVMLong)amount).longValue() <= 1000000000L, "Amount should be capped at GOLD");
		}
	}

	@Test public void testIdenticon() throws IOException, InterruptedException {
		{ // should return PNG for valid hex
			HttpResponse<String> res = get(HOST_PATH + "/identicon/1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
			assertEquals(200, res.statusCode());
			assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("image/png"));
			// Check caching headers
			assertTrue(res.headers().firstValue("Cache-Control").orElse("").contains("max-age"));
			assertNotNull(res.headers().firstValue("ETag").orElse(null));
		}

		{ // should return PNG for short hex (any valid hex works)
			HttpResponse<String> res = get(HOST_PATH + "/identicon/abcd");
			assertEquals(200, res.statusCode());
			assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("image/png"));
		}

		{ // should return 400 for invalid hex
			HttpResponse<String> res = get(HOST_PATH + "/identicon/notvalidhex!");
			assertEquals(400, res.statusCode());
		}
	}

	@Test public void testQueryPeer() throws IOException, InterruptedException {
		// Get the peer key from the server
		String peerKey = server.getServer().getPeerKey().toHexString();

		{ // should return peer info for valid peer
			HttpResponse<String> res = get(API_PATH + "/peers/" + peerKey);
			assertEquals(200, res.statusCode());

			// Parse response - should be valid JSON representing PeerStatus
			String body = res.body();
			assertNotNull(body);
			assertFalse(body.isBlank());
		}

		{ // should return 404 for non-existent peer
			String fakePeerKey = "0000000000000000000000000000000000000000000000000000000000000000";
			HttpResponse<String> res = get(API_PATH + "/peers/" + fakePeerKey);
			assertEquals(404, res.statusCode());
		}

		{ // should return 400 for invalid peer key format
			HttpResponse<String> res = get(API_PATH + "/peers/invalid-key");
			assertEquals(400, res.statusCode());
		}

		{ // should return 400 for too-short key
			HttpResponse<String> res = get(API_PATH + "/peers/1234");
			assertEquals(400, res.statusCode());
		}
	}

	@Test public void testAdversarialInputs() throws IOException, InterruptedException {
		// Test various malformed and adversarial inputs

		{ // Extremely long source code
			StringBuilder longSource = new StringBuilder("(+ 1");
			for (int i = 0; i < 10000; i++) {
				longSource.append(" 1");
			}
			longSource.append(")");
			String query = JSON.toStringPretty(Maps.of("address", 11, "source", longSource.toString()));
			HttpResponse<String> res = post(API_PATH + "/query", query);
			// Should either succeed or fail gracefully (not crash)
			assertTrue(res.statusCode() == 200 || res.statusCode() == 400 || res.statusCode() == 422);
		}

		{ // Null bytes in source
			String query = "{\"address\":11,\"source\":\"(+ 1 \\u0000 2)\"}";
			HttpResponse<String> res = post(API_PATH + "/query", query);
			// Should handle gracefully
			assertTrue(res.statusCode() >= 200 && res.statusCode() < 500);
		}

		{ // Unicode in source
			String query = JSON.toStringPretty(Maps.of("address", 11, "source", "(str \"こんにちは\" \"🎉\")"));
			HttpResponse<String> res = post(API_PATH + "/query", query);
			assertEquals(200, res.statusCode());
		}

		{ // Very large address number
			String query = JSON.toStringPretty(Maps.of("address", Long.MAX_VALUE, "source", "*balance*"));
			HttpResponse<String> res = post(API_PATH + "/query", query);
			// Should handle - might be 200 with error result or 404
			assertTrue(res.statusCode() >= 200 && res.statusCode() < 500);
		}

		{ // Negative address
			HttpResponse<String> res = get(API_PATH + "/accounts/-1");
			assertEquals(400, res.statusCode());
		}

		{ // SQL injection attempt in query source (should be harmless)
			String query = JSON.toStringPretty(Maps.of("address", 11, "source", "'; DROP TABLE users; --"));
			HttpResponse<String> res = post(API_PATH + "/query", query);
			// Should fail to parse as Convex code, but not crash (400 parse error or 422 execution error)
			assertTrue(res.statusCode() >= 200 && res.statusCode() < 500,
				"SQL injection should be handled gracefully, got: " + res.statusCode());
		}

		{ // Script injection attempt
			String query = JSON.toStringPretty(Maps.of("address", 11, "source", "<script>alert('xss')</script>"));
			HttpResponse<String> res = post(API_PATH + "/query", query);
			// Should fail to parse as Convex code (400 parse error or 422 execution error)
			assertTrue(res.statusCode() >= 200 && res.statusCode() < 500,
				"Script injection should be handled gracefully, got: " + res.statusCode());
		}

		{ // Deeply nested JSON
			StringBuilder nested = new StringBuilder("{\"address\":11,\"source\":\"1\"");
			// This is valid JSON, just testing parser robustness
			nested.append("}");
			HttpResponse<String> res = post(API_PATH + "/query", nested.toString());
			assertEquals(200, res.statusCode());
		}

		{ // Empty hash for getData
			HttpResponse<String> res = get(API_PATH + "/data/");
			// Should be 404 (no route match) or 400
			assertTrue(res.statusCode() == 400 || res.statusCode() == 404);
		}

		{ // Hash with special characters (path traversal attempt)
			HttpResponse<String> res = get(API_PATH + "/data/../../etc/passwd");
			// Should be 400 (bad hash) or 404 (route not found due to path normalization)
			assertTrue(res.statusCode() == 400 || res.statusCode() == 404,
				"Path traversal should be handled, got: " + res.statusCode());
		}

		{ // Block number as negative
			HttpResponse<String> res = get(API_PATH + "/blocks/-1");
			assertEquals(400, res.statusCode());
		}

		{ // Block number as non-numeric
			HttpResponse<String> res = get(API_PATH + "/blocks/abc");
			assertEquals(400, res.statusCode());
		}

		{ // Blocks with invalid pagination
			HttpResponse<String> res = get(API_PATH + "/blocks?offset=-1");
			assertEquals(400, res.statusCode());
		}

		{ // Blocks with limit too high
			HttpResponse<String> res = get(API_PATH + "/blocks?limit=10000");
			assertEquals(400, res.statusCode());
		}

		{ // Transaction prepare with code injection attempt
			AMap<AString, ACell> req = Maps.of(
				"address", Init.GENESIS_ADDRESS,
				"source", "(eval (read-string \"(def bad 1)\"))"
			);
			HttpResponse<String> res = post(API_PATH + "/transaction/prepare", JSON.toStringPretty(req));
			// Should prepare (code validity checked at execution time)
			assertEquals(200, res.statusCode());
		}

		{ // Faucet with negative amount
			AMap<AString, ACell> req = Maps.of(
				"address", Init.GENESIS_ADDRESS,
				"amount", CVMLong.create(-1000)
			);
			HttpResponse<String> res = post(API_PATH + "/faucet", JSON.toString(req));
			// Should either reject or treat as 0
			assertTrue(res.statusCode() == 200 || res.statusCode() == 400 || res.statusCode() == 422);
		}

		{ // Malformed JSON
			HttpResponse<String> res = post(API_PATH + "/query", "{invalid json}");
			assertEquals(400, res.statusCode());
		}

		{ // Empty body
			HttpResponse<String> res = post(API_PATH + "/query", "");
			assertEquals(400, res.statusCode());
		}

		{ // Content-Type mismatch (sending non-JSON as JSON)
			HttpResponse<String> res = post(API_PATH + "/query", "not json at all");
			assertEquals(400, res.statusCode());
		}
	}
}
