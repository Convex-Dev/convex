package convex.restapi.api;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.lang.Reader;
import convex.java.JSON;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

public abstract class AGenericAPI {

	/**
	 * Add routes to the service
	 * @param app Javalin instance to add routes to
	 */
	public abstract void addRoutes(Javalin app);
	
	protected String calcResponseContentType(Context ctx) {
		Enumeration<String> accepts=ctx.req().getHeaders("Accept");
		String type=ContentTypes.JSON;
		// TODO: look at quality weights perhaps
		if (accepts!=null) {
			for (String a:Collections.list(accepts)) {
				if (a.contains(ContentTypes.CVX_RAW)) {
					type=ContentTypes.CVX_RAW;
					break;
				}
				if (a.contains(ContentTypes.CVX)) {
					type=ContentTypes.CVX;
				}
			}
		}
		return type;
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


}
