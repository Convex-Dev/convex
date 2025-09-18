package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import org.apache.hc.client5.http.fluent.Content;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.fluent.Response;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpResponse;
import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.cvm.Keywords;
import convex.core.lang.RT;
import convex.core.init.Init;
import convex.core.util.JSON;
import convex.java.ConvexHTTP;
import java.net.URI;

public class RESTAPITest extends ARESTTest {
	
	
	@Test public void testDataAPI() {
		
	}
	
//  Not obvious how to make this work given self signed certificates?
//	@Test public void testHTTPS() throws IOException {
//		Content c = Request.get("https://localhost").execute().returnContent();
//		assertNotNull(c);
//	}
	
	@Test public void testSwagger() throws IOException {
		Content c = Request.get("http://localhost:" + server.getPort()+"/swagger").execute().returnContent();
		String s = c.asString();
		assertFalse(s.isBlank());
	}
	
	@Test public void testOpenAPI() throws IOException {
		Content c = Request.get("http://localhost:" + server.getPort()+"/openapi").execute().returnContent();
		String s = c.asString();
		assertNotNull(JSON.parse(s));
	}
	
	@Test public void testTransact() throws IOException {
		{ // should be a bad request with non-parseable/missing fields
			HttpResponse res=Request.post(API_PATH+"/transact").execute().returnResponse();
			assertEquals(400,res.getCode());
		}
		
		{ // should execute successfully on genesis account
			String tx=JSON.toStringPretty(Maps.of("address",Init.GENESIS_ADDRESS,"source","(* 2 3)","seed",KP.getSeed()));
			Request req=Request.post(API_PATH+"/transact").bodyString(tx, ContentType.APPLICATION_JSON);
			ClassicHttpResponse res=(ClassicHttpResponse) req.execute().returnResponse();
			assertEquals(200,res.getCode());
			
			// Parse response as JSON to verify it's valid JSON
			String responseBody = new String(res.getEntity().getContent().readAllBytes());
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
			HttpResponse txResponse = Request.get(API_PATH + "/tx?hash=" + txHash).execute().returnResponse();
			assertEquals(200, txResponse.getCode());
		}
	}
	
	@Test public void testQuery() throws IOException {
		{ // should be a bad request with bad JSON
			Request req=Request.post(API_PATH+"/query").bodyString("fddfgb", ContentType.APPLICATION_JSON);
			HttpResponse res=req.execute().returnResponse();
			assertEquals(400,res.getCode());
		}
		
		{ // should be OK
			String query=JSON.toStringPretty(Maps.of("address",11,"source","*balance*"));
			Request req=Request.post(API_PATH+"/query").bodyString(query, ContentType.APPLICATION_JSON);
			ClassicHttpResponse res=(ClassicHttpResponse) req.execute().returnResponse();
			assertEquals(200,res.getCode());
		}
		
		{ // should be a failure of query
			String query=JSON.toStringPretty(Maps.of("address",11,"source","(count)"));
			Request req=Request.post(API_PATH+"/query").bodyString(query, ContentType.APPLICATION_JSON);
			Response res=req.execute();
			HttpResponse httpr=res.returnResponse();
			assertEquals(422,httpr.getCode());
			// assertEquals("ARITY",((Map<String,Object>)JSON.parse(c.asString())).get("errorCode"));
		}
			
	}
	
	@Test public void testQueryAccount() throws IOException {
		{ // should be a bad request with bad JSON
			Request req=Request.get(API_PATH+"/accounts/999999");
			HttpResponse res=req.execute().returnResponse();
			assertEquals(404,res.getCode());
		}
		
		{ // should be OK
			Request req=Request.get(API_PATH+"/accounts/11");
			HttpResponse res=req.execute().returnResponse();
			assertEquals(200,res.getCode());
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
		assertFalse(result.isError());
		
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
		HttpResponse txResponse = Request.get(API_PATH + "/tx?hash=" + txHash).execute().returnResponse();
		assertEquals(200, txResponse.getCode());
		
		// Parse transaction response to get block information
		Content txContent = Request.get(API_PATH + "/tx?hash=" + txHash).execute().returnContent();
		String txResponseBody = txContent.asString();
		convex.core.data.ACell txParsed = JSON.parse(txResponseBody);
		assertNotNull(txParsed);
		
		// Get blocks to find which block contains our transaction
		Content blocksContent = Request.get(API_PATH + "/blocks?limit=10").execute().returnContent();
		String blocksResponseBody = blocksContent.asString();
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
		HttpResponse blockResponse = Request.get(API_PATH + "/block/" + blockNum).execute().returnResponse();
		assertEquals(200, blockResponse.getCode());
		
		// Parse block response
		Content blockContent = Request.get(API_PATH + "/block/" + blockNum).execute().returnContent();
		String blockResponseBody = blockContent.asString();
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
		HttpResponse notFoundResponse = Request.get(API_PATH + "/block/999999").execute().returnResponse();
		assertEquals(404, notFoundResponse.getCode());
	}
}
