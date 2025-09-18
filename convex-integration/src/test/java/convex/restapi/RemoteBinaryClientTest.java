package convex.restapi;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;

/**
 * Tests for remote binary client functionality
 * 
 * Note: ConvexJSON tests have been moved to convex.java.ConvexJSONTest
 */
public class RemoteBinaryClientTest {

	static RESTServer server;
	static int port;
	static String host;
	static AKeyPair skp;
	
	@BeforeAll
	public static void init() throws InterruptedException, ConfigException, LaunchException {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, AKeyPair.generate());
		config.put(Keyword.create("faucet"), true);
		Server s=API.launchPeer(config);
		RESTServer rs=RESTServer.create(s);
		rs.start(0);
		port=rs.getPort();
		server=rs;
		skp=s.getKeyPair();
		host="http://localhost:"+port;
	}
	
	@AfterAll 
	public static void cleanShutdown() {
		server.close();
	}
	
	@Test 
	public void testServerStartup() {
		// Basic test to verify the server started correctly
		assertNotNull(server);
		assertNotNull(host);
		assertTrue(port > 0);
	}
	
	// TODO: Add actual binary client tests here
	// These would test the binary protocol functionality specifically
	// rather than the JSON API functionality
}
