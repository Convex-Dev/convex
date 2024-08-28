package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;

import org.apache.hc.client5.http.fluent.Content;
import org.apache.hc.client5.http.fluent.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.restapi.RESTServer;

public class RESTAPITest {
	static RESTServer server;
	static int port;
	
	@BeforeAll
	public static void init() throws InterruptedException, ConfigException, LaunchException {
		Server s=API.launchPeer();
		RESTServer rs=RESTServer.create(s);
		rs.start(0);
		port=rs.getPort();
		server=rs;
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
}
