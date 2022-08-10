package convex.restapi;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Address;
import convex.java.Convex;
import convex.peer.API;
import convex.peer.Server;

public class RemoteClientTest {

	static RESTServer server;
	
	@BeforeAll
	public static void init() {
		Server s=API.launchPeer();
		RESTServer rs=RESTServer.create(s);
		rs.start();
		server=rs;
	}
	
	@Test 
	public void testRemoteClient() {
		Convex c=Convex.connect("http://localhost:8080");
		AKeyPair kp=AKeyPair.generate();
		Address addr=c.createAccount(kp);
		assertNotNull(addr);
	}
	
	
	
	@AfterAll 
	public static void cleanShutdown() {
		server.stop();
	}
}
