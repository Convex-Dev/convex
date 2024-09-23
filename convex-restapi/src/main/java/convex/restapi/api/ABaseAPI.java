package convex.restapi.api;

import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.lang.Reader;
import convex.java.JSON;
import convex.peer.Server;
import convex.restapi.RESTServer;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * BAse class for API based services
 */
public abstract class ABaseAPI {
	
	protected final RESTServer restServer;
	protected final Server server;

	public ABaseAPI(RESTServer restServer) {
		this.restServer=restServer;
		this.server=restServer.getServer();
	}
	
	/**
	 * Gets JSON body from a Context as a Java Object
	 * @param ctx Request context
	 * @return JSON Object
	 * @throws BadRequestResponse if the JSON body is invalid
	 */
	protected Map<String, Object> getJSONBody(Context ctx) {
		try {
			Map<String, Object> req= JSON.toMap(ctx.body());
			return req;
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse(jsonError("Invalid JSON body"));
		}
	}
	
	/**
	 * Gets CVX body from a Context as a cell
	 * @param ctx Request context
	 * @return CVM Value
	 * @throws BadRequestResponse if the body is invalid
	 */
	protected <T extends ACell> T getCVXBody(Context ctx) {
		try {
			@SuppressWarnings("unchecked")
			T req= (T) Reader.read(ctx.body());
			return req;
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Invalid CVX body"));
		}
	}
	
	/**
	 * Gets body from a Context as a cell
	 * @param ctx Request context
	 * @return CVM Value
	 * @throws BadRequestResponse if the body is invalid
	 */
	protected <T extends ACell> T getRawBody(Context ctx) {
		try {
			byte[] bs=ctx.bodyAsBytes();
			T result=Format.decodeMultiCell(Blob.wrap(bs));
			return result;
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Invalid Raw body"));
		}
	}
	
	/**
	 * Gets a generic JSON response for an error message
	 * @param string
	 * @return
	 */
	protected static String jsonError(String string) {
		return "{\"error\":\"" + string + "\"}";
	}

	/**
	 * Add routes to the service
	 * @param app Javalin instance to add routes to
	 * @param baseURL Base URL for routes e.g. "/service-name/api"
	 */
	protected abstract void addRoutes(Javalin app);

}
