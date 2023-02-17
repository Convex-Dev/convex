package convex.restapi;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.peer.API;
import convex.peer.Server;

public class RESTAPITest {
	static RESTServer server;
	static int port;
	
	@BeforeAll
	public static void init() {
		Server s=API.launchPeer();
		RESTServer rs=RESTServer.create(s);
		rs.start(0);
		port=rs.getPort();
		server=rs;
	}
	
	@AfterAll 
	public static void cleanShutdown() {
		server.stop();
	}
	
	@Test public void testDataAPI() {
		
	}
}
