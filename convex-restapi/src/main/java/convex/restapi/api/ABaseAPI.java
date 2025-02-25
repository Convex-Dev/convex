package convex.restapi.api;

import convex.peer.Server;
import convex.restapi.RESTServer;

/**
 * BAse class for API based services
 */
public abstract class ABaseAPI extends AGenericAPI {
	
	protected final RESTServer restServer;
	protected final Server server;

	public ABaseAPI(RESTServer restServer) {
		this.restServer=restServer;
		this.server=restServer.getServer();
	}
	


	


}
