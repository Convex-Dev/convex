package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.cvm.Keywords;
import convex.core.lang.RT;
import convex.core.init.Init;
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
			Object parsedResponse = JSON.parse(responseBody);
			assertNotNull(parsedResponse);
			
			// Extract transaction hash from the response using Convex data structures
			@SuppressWarnings("unchecked")
			convex.core.data.AMap<convex.core.data.ACell, convex.core.data.ACell> responseMap = (convex.core.data.AMap<convex.core.data.ACell, convex.core.data.ACell>) parsedResponse;
			convex.core.data.ACell infoCell = responseMap.get(convex.core.data.Strings.create("info"));
			assertNotNull(infoCell, "Response should contain info field");
			
			@SuppressWarnings("unchecked")
			convex.core.data.AMap<convex.core.data.ACell, convex.core.data.ACell> info = (convex.core.data.AMap<convex.core.data.ACell, convex.core.data.ACell>) infoCell;
			convex.core.data.ACell txCell = info.get(convex.core.data.Strings.create("tx"));
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
			assertEquals(200, res.statusCode());
		}
		
		{ // should be a failure of query due to bad code execution
			String query=JSON.toStringPretty(Maps.of("address",11,"source","(count)"));
			HttpResponse<String> res = post(API_PATH+"/query", query);
			assertEquals(200, res.statusCode());
			@SuppressWarnings("unchecked")
			AMap<AString,ACell> json=(AMap<AString,ACell>)JSON.parse(res.body());
			AString errorCode=RT.getIn(json,"error");
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
}
