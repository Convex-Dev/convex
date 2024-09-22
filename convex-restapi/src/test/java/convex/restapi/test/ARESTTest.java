package convex.restapi.test;

import convex.core.crypto.AKeyPair;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTServer;

public abstract class ARESTTest {
	protected static RESTServer server;
	protected static int port;
	protected static String HOST_PATH;
	protected static String API_PATH;
	protected static AKeyPair KP;
	
	static {
		try {
			Server s = API.launchPeer();
			RESTServer rs = RESTServer.create(s);
			rs.start(0);
			port = rs.getPort();
			server = rs;
			HOST_PATH="http://localhost:" + rs.getPort();
			API_PATH=HOST_PATH+"/api/v1";
			KP=s.getKeyPair();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}
}
