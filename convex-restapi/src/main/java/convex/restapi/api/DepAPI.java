package convex.restapi.api;

import convex.restapi.RESTServer;
import io.javalin.Javalin;

/**
 * Class for Data Ecosystem services
 * 
 * Designed to conform with the Datacraft DEP standards, see https://github.com/datacraft-dsc/DEPs
 */
public class DepAPI extends ABaseAPI {

	public DepAPI(RESTServer restServer) {
		super(restServer);
	
	}

	@Override
	public void addRoutes(Javalin app, String baseURL) {
		// TODO Auto-generated method stub
		
	}

}
