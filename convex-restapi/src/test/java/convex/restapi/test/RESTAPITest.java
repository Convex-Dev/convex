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

import convex.core.crypto.ASignature;
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
			ACell txValue=Format.decodeMultiCell(data);
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
	
	@Test public void testQuery() throws IOException, InterruptedException {
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
}
