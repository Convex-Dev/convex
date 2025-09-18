package convex.restapi.api;

import java.util.Collections;
import java.util.Enumeration;
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
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

/**
 * Base class for generic Convex REST API
 * Contains useful common functionality for all API implementations.
 */
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
				if (a.contains(ContentTypes.TEXT)) {
					type=ContentTypes.TEXT;
				}
				if (a.contains(ContentTypes.HTML)) {
					type=ContentTypes.HTML;
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
	@SuppressWarnings("unchecked")
	protected <T extends ACell> T getCVXBody(Context ctx) {
		try {
			String contentType = ctx.contentType();
			T req;
			
			// Check for JSON content type and use JSON.parse
			if (ContentTypes.JSON.equals(contentType)) {
				req = (T) JSON.parse(ctx.body());
			} else {
				// Default to Reader.read for other content types
				req = (T) Reader.read(ctx.body());
			}
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
	 * Set content to a CVM Result according to requested content type. Updates status according to result error status
	 * @param ctx Javalin context
	 * @param resultContent 
	 */
	public void setResult(Context ctx, Result resultContent) {
		if (resultContent.getSource()==null) {
			resultContent=resultContent.withSource(SourceCodes.SERVER);
		}
		
		int status=statusForResult(resultContent);
		ctx.status(status);
		
		setContent(ctx,(ACell)resultContent);
	}
	
	/**
	 * Set content to a CVM Value according to requested content type. Updates status according to result error status
	 * @param ctx Javalin context
	 * @param content Return content
	 */
	public void setContent(Context ctx, ACell content) {
		
		String type = calcResponseContentType(ctx);
		
		if (type.equals(ContentTypes.JSON)) {
			ctx.contentType(ContentTypes.JSON);
			ctx.result(JSON.printPretty(content).getInputStream());
		} else if (type.equals(ContentTypes.CVX)) {
			ctx.contentType(ContentTypes.CVX);
			AString rs=RT.print(content);
			if (rs==null) {
				setResult(ctx,Result.error(ErrorCodes.LIMIT, Strings.PRINT_EXCEEDED).withSource(SourceCodes.PEER));
				ctx.status(403); // Forbidden because of result size
				return;
			}
			ctx.result(rs.toString());
		} else if (type.equals(ContentTypes.CVX_RAW)) {
			ctx.contentType(ContentTypes.CVX_RAW);
			Blob b=Format.encodeMultiCell(content, true);
			ctx.result(b.getBytes());
		} else if (type.equals(ContentTypes.HTML)) {
			ctx.contentType(ContentTypes.HTML);
			AString htmlContent = formatAsHTML(content);
			ctx.result(htmlContent.toString());
		} else if (type.equals(ContentTypes.TEXT)) {
			ctx.contentType(ContentTypes.TEXT);
			ctx.result("Unsupported content type: "+type);
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
	
	/**
	 * Format CVM data as minimal HTML
	 * @param content CVM data to format
	 * @return HTML string representation
	 */
	private AString formatAsHTML(ACell content) {
		BlobBuilder bb = new BlobBuilder();
		
		// Minimal HTML structure
		bb.append("<!DOCTYPE html><html><head><title>Convex API Result</title></head><body><pre>");
		
		// Format the content
		try {
			AString jsonString = JSON.printPretty(content);
			bb.append(escapeHtml(jsonString));
		} catch (Exception e) {
			bb.append("Error formatting result: ");
			bb.append(escapeHtml(Strings.create(e.getMessage())));
		}
		
		bb.append("</pre></body></html>");
		
		return bb.getCVMString();
	}
	
	/**
	 * Escape HTML special characters using CVM string functions
	 * @param text Text to escape
	 * @return HTML-escaped text
	 */
	private AString escapeHtml(AString text) {
		if (text == null) return Strings.EMPTY;
		
		BlobBuilder bb = new BlobBuilder();
		long len = text.count();
		
		for (long i = 0; i < len; i++) {
			int c = text.charAt((int)i);
			switch (c) {
				case '&': bb.append("&amp;"); break;
				case '<': bb.append("&lt;"); break;
				case '>': bb.append("&gt;"); break;
				case '"': bb.append("&quot;"); break;
				case '\'': bb.append("&#39;"); break;
				default: bb.append((char)c); break;
			}
		}
		
		return bb.getCVMString();
	}
}
