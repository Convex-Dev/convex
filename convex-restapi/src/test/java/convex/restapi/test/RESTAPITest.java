package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;

import org.apache.hc.client5.http.fluent.*;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.java.JSON;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.restapi.RESTServer;

public class RESTAPITest {
	static RESTServer server;
	static int port;
	static String HOST_PATH;
	static String API_PATH;
	
	@BeforeAll
	public static void init() throws InterruptedException, ConfigException, LaunchException {
		Server s=API.launchPeer();
		RESTServer rs=RESTServer.create(s);
		rs.start(0);
		port=rs.getPort();
		server=rs;
		HOST_PATH="http://localhost:" + server.getPort();
		API_PATH=HOST_PATH+"/api/v1";
	}
	
	@AfterAll 
	public static void cleanShutdown() {
		if (server!=null) {
			server.close();
		}
	}
	
	@Test public void testDataAPI() {
		
	}
	
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
