package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;

import org.apache.hc.client5.http.fluent.Content;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.client5.http.fluent.Response;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpResponse;
import org.junit.jupiter.api.Test;

import convex.core.init.Init;
import convex.java.JSON;

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
	
	@Test public void testTransact() throws IOException {
		{ // should be a bad request with non-parseable/missing fields
			HttpResponse res=Request.post(API_PATH+"/transact").execute().returnResponse();
			assertEquals(400,res.getCode());
		}
		
		{ // should execute successfully on genesis account
			String tx=JSON.toPrettyString(JSON.map("address",Init.GENESIS_ADDRESS,"source","(* 2 3)","seed",KP.getSeed()));
			HttpResponse res=Request.post(API_PATH+"/transact").bodyString(tx, ContentType.APPLICATION_JSON).execute().returnResponse();
			assertEquals(200,res.getCode());
		}
	}
	
	@Test public void testQuery() throws IOException {
		{ // should be a bad request with bad JSON
			Request req=Request.post(API_PATH+"/query").bodyString("fddfgb", ContentType.APPLICATION_JSON);
			HttpResponse res=req.execute().returnResponse();
			assertEquals(400,res.getCode());
		}
		
		{ // should be OK
			String query=JSON.toPrettyString(JSON.map("address",11,"source","*balance*"));
			Request req=Request.post(API_PATH+"/query").bodyString(query, ContentType.APPLICATION_JSON);
			HttpResponse res=req.execute().returnResponse();
			assertEquals(200,res.getCode());
		}
		
		{ // should be a failure of query
			String query=JSON.toPrettyString(JSON.map("address",11,"source","(count)"));
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
}
