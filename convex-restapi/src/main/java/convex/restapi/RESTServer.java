package convex.restapi;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.peer.Server;
import io.javalin.Javalin;

public class RESTServer {

	private Server server;
	private Convex convex;
	private Javalin app;
	
	private RESTServer() {
		app=Javalin.create(config->{
		});
	}

	/**
	 * Create a RESTServer connected to a local Convex Peer Server instance. Defaults to using the Peer Controller account.
	 * 
	 * @param server
	 * @return
	 */
	public RESTServer create(Server server) {
		RESTServer newServer=new RESTServer();
		newServer.server=server;
		newServer.convex=ConvexLocal.create(server, server.getPeerController(), server.getKeyPair());
		return newServer;
	}
	
	/**
	 * Create a RESTServer connected to a Convex Client instance. Defaults to using the Peer Controller account.
	 * 
	 * @param server
	 * @return
	 */
	public RESTServer create(Convex convex) {
		RESTServer newServer=new RESTServer();
		newServer.convex=convex;
		return newServer;
	}
	
	public void start() {
		app.start();
		
	}
	
	public void stop() {
		app.close();
	}
	
	public Convex getConvex() {
		return convex;
	}
	
	/**
	 * Gets the local Server instance, or null if not a local connection. 
	 * @return
	 */
	public Server getServer() {
		return server;
	}
}
