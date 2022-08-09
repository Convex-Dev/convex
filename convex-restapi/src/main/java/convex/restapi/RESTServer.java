package convex.restapi;

import convex.api.Convex;
import convex.peer.Server;

public class RESTServer {

	private Server server;
	private Convex convex;

	/**
	 * Create a RESTServer connected to a local Convex Peer Server instance
	 * @param server
	 * @return
	 */
	public RESTServer create(Server server) {
		RESTServer newServer=new RESTServer();
		newServer.server=server;
		return newServer;
	}
	
	public Convex getConvex() {
		return convex;
	}
}
