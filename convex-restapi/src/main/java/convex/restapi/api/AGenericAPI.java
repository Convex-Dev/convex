package convex.restapi.api;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import convex.api.ContentTypes;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.core.exceptions.*;
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
			Map<String, Object> req= RT.jvm(JSON.parse(ctx.body()));
			return req;
		} catch (IllegalArgumentException | ParseException e) {
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


	public void prepareResult(Context ctx, Result r) {
		if (r.getSource()==null) {
			r=r.withSource(SourceCodes.SERVER);
		}
		
		int status=statusForResult(r);
		ctx.status(status);
		
		String type = calcResponseContentType(ctx);
		
		if (type.equals(ContentTypes.JSON)) {
			ctx.contentType(ContentTypes.JSON);
			HashMap<String, Object> resultJSON = r.toJSON();
			ctx.result(JSON.toStringPretty(resultJSON));
		} else if (type.equals(ContentTypes.CVX)) {
			ctx.contentType(ContentTypes.CVX);
			AString rs=RT.print(r);
			if (rs==null) {
				rs=RT.print(Result.error(ErrorCodes.LIMIT, Strings.PRINT_EXCEEDED).withSource(SourceCodes.PEER));
				ctx.status(403); // Forbidden because of result size
			}
			ctx.result(rs.toString());
		} else if (type.equals(ContentTypes.CVX_RAW)) {
			ctx.contentType(ContentTypes.CVX_RAW);
			Blob b=Format.encodeMultiCell(r, true);
			ctx.result(b.getBytes());
		} else {
			ctx.contentType(ContentTypes.TEXT);
			ctx.status(415); // unsupported media type for "Accept" header
			ctx.result("Unsupported content type: "+type);
		}
	}
	
	public int statusForResult(Result r) {
		if (!r.isError()) {
			return 200;
		}
		Keyword source=r.getSource();
		ACell error=r.getErrorCode();
		if (SourceCodes.CVM.equals(source)) {
			return 200;
		} else if (SourceCodes.CODE.equals(source)) {
			return 200;
		} else if (SourceCodes.PEER.equals(source)) {
			if (ErrorCodes.SIGNATURE.equals(error)) return 403; // Forbidden
			if (ErrorCodes.FUNDS.equals(error)) return 402; // payment required
		}
		if (ErrorCodes.FORMAT.equals(error)) return 400; // bad request
		if (ErrorCodes.TIMEOUT.equals(error)) return 408; // timeout
		int status = 422;
		return status;
	}
}
