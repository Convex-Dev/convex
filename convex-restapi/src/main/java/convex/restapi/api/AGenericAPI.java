package convex.restapi.api;

import java.util.Collections;
import java.util.Enumeration;

import convex.api.ContentTypes;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.util.BlobBuilder;
import convex.core.text.StringUtils;
import convex.core.lang.RT;
import convex.core.util.JSON;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

/**
 * Base class for generic Convex REST API
 * Contains useful common functionality for all API implementations.
 */
public abstract class AGenericAPI {

	/**
	 * Add routes to the service
	 * @param routes Routes configuration to add routes to
	 */
	public abstract void addRoutes(RoutesConfig routes);
	
	public static String calcResponseContentType(Context ctx) {
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
	private static final long MAX_CONTENT_SIZE = 1_000_000; // 1MB limit

	public void setContent(Context ctx, ACell content) {
		// CVM query errors remain successful HTTP query responses, but admission
		// failure is a transport-level service availability response.
		if ((content instanceof Result r)&&ErrorCodes.LOAD.equals(r.getErrorCode())) {
			ctx.status(503);
		}

		long size = Cells.storageSize(content);
		if (size > MAX_CONTENT_SIZE) {
			setResult(ctx, Result.error(ErrorCodes.LIMIT, "Response too large: " + size + " bytes").withSource(SourceCodes.PEER));
			return;
		}

		String type = calcResponseContentType(ctx);
		
		if (type.equals(ContentTypes.JSON)) {
			ctx.contentType(ContentTypes.JSON);
			if (content instanceof Result r) {
				// Special format for CVM results at top level
				Object jsonResult=r.toJSON();
				ctx.result(JSON.printPretty(jsonResult).getInputStream());
			} else {
			
				ctx.result(JSON.printPretty(content).getInputStream());
			}
		} else if (type.equals(ContentTypes.CVX)) {
			ctx.contentType(ContentTypes.CVX);
			AString rs=RT.print(content);
			if (rs==null) {
				setResult(ctx,Result.error(ErrorCodes.LIMIT, Strings.PRINT_EXCEEDED).withSource(SourceCodes.PEER));
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
			AString rs=RT.print(content);
			if (rs==null) {
				setResult(ctx,Result.error(ErrorCodes.LIMIT, Strings.PRINT_EXCEEDED).withSource(SourceCodes.PEER));
				return;
			}
			ctx.result(rs.toString());
		} else {
			ctx.contentType(ContentTypes.TEXT);
			ctx.status(415); // unsupported media type for "Accept" header
			ctx.result("Unsupported content type: "+type);
		}
	}
	
	public int statusForResult(Result r) {
		if (!r.isError()) {
			if (r==Result.SENT_MESSAGE) return 202; // Accepted, no result expected
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
			if (ErrorCodes.LIMIT.equals(error)) return 413; // Content too large
		}
		if (ErrorCodes.FORMAT.equals(error)) return 400; // bad request
		if (ErrorCodes.MISSING.equals(error)) return 404; // not found
		if (ErrorCodes.TIMEOUT.equals(error)) return 408; // timeout
		if (ErrorCodes.LOAD.equals(error)) return 503; // service unavailable under bounded admission
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
		bb.append("<!DOCTYPE html>\n<html><head><title>Convex API Result</title><meta charset=\"UTF-8\"></head><body><pre>\n");
		
		// Format the content
		try {
			AString jsonString = JSON.printPretty(content);
			bb.append(StringUtils.escapeHtml(jsonString));
		} catch (Exception e) {
			bb.append("Error formatting result: ");
			bb.append(StringUtils.escapeHtml(Strings.create(e.getMessage())));
		}
		
		bb.append("\n</pre></body></html>");
		
		return bb.getCVMString();
	}
	
}
