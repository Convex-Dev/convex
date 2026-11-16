package convex.restapi.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import convex.api.ContentTypes;
import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.exceptions.ParseException;
import convex.core.json.JSONReader;
import convex.core.store.AStore;
import convex.core.util.JSON;
import convex.restapi.handler.RequestBody;
import io.javalin.http.ContentTooLargeResponse;
import convex.peer.Server;
import convex.core.cvm.Address;
import convex.core.data.AString;
import convex.core.data.ACell;
import convex.core.lang.Reader;
import convex.restapi.RESTServer;
import convex.core.lang.RT;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import convex.core.data.AVector;
import convex.core.cvm.State;
import convex.core.data.Symbol;

/**
 * Base class for API based services
 */
public abstract class ABaseAPI extends AGenericAPI {
	
	/**
	 * Default busy timeout for simultaneous requests
	 */
	public static final long BUSY_TIMEOUT = 5000;

	
	/**
	 * Default pagination limit
	 */
	private static final long DEFAULT_LIMIT = 10;

	/**
	 * Maximum pagination limit. A request may ask for more, but the range is
	 * clamped to this so a public caller cannot force unbounded reads/allocation.
	 */
	static final long MAX_LIMIT = 1000;
	
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
	 * @throws ContentTooLargeResponse if the body exceeds the server's request limit
	 */
	protected Map<String, Object> getJSONBody(Context ctx) {
		try {
			Map<String, Object> req= RT.jvm(JSON.parse(readBodyText(ctx)));
			return req;
		} catch (IllegalArgumentException | ParseException e) {
			throw new BadRequestResponse("Invalid JSON body");
		}
	}

	/**
	 * Gets JSON body from a Context as a CVM Value
	 * @param ctx Request context
	 * @return JSON AMap
	 * @throws BadRequestResponse if the JSON body is invalid
	 * @throws ContentTooLargeResponse if the body exceeds the server's request limit
	 */
	protected AMap<AString, ACell> readJSONBody(Context ctx) {
		InputStream body=RequestBody.boundedInputStream(ctx,restServer.getMaxRequestBytes());
		try {
			AMap<AString, ACell> req= JSONReader.readObject(body);
			return req;
		} catch (IllegalArgumentException | ParseException | IOException e) {
			throw new BadRequestResponse("Invalid JSON body: "+e.getMessage());
		}
	}

	/**
	 * Gets CVX body from a Context as a cell
	 * @param ctx Request context
	 * @return CVM Value
	 * @throws BadRequestResponse if the body is invalid
	 * @throws ContentTooLargeResponse if the body exceeds the server's request limit
	 */
	@SuppressWarnings("unchecked")
	protected <T extends ACell> T getCVXBody(Context ctx) {
		String body=readBodyText(ctx);
		try {
			String contentType = ctx.contentType();
			T req;

			// Check for JSON content type and use JSON.parse
			if (ContentTypes.JSON.equals(contentType)) {
				req = (T) JSON.parse(body);
			} else {
				// Default to Reader.read for other content types
				req = (T) Reader.read(body);
			}
			return req;
		} catch (Exception e) {
			throw new BadRequestResponse("Invalid CVX body");
		}
	}

	/**
	 * Gets body from a Context as a cell
	 * @param ctx Request context
	 * @param store Store to decode the cell into
	 * @return CVM Value
	 * @throws BadRequestResponse if the body is invalid
	 * @throws ContentTooLargeResponse if the body exceeds the server's request limit
	 */
	protected <T extends ACell> T getRawBody(Context ctx, AStore store) {
		byte[] bs=RequestBody.readBytes(ctx,restServer.getMaxRequestBytes());
		try {
			T result=store.decodeMultiCell(Blob.wrap(bs));
			return result;
		} catch (Exception e) {
			throw new BadRequestResponse("Invalid Raw body");
		}
	}

	/** Reads the body whole as text, in its declared character encoding or UTF-8. */
	private String readBodyText(Context ctx) {
		String encoding=ctx.characterEncoding();
		Charset charset;
		try {
			charset=(encoding==null) ? StandardCharsets.UTF_8 : Charset.forName(encoding);
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse("Unsupported character encoding: "+encoding);
		}
		return new String(RequestBody.readBytes(ctx,restServer.getMaxRequestBytes()),charset);
	}


	public static String getHostname(Context ctx) {
		String host =ctx.header("X-Forwarded-Host");
	    if (host == null) {
	        host = ctx.host(); // e.g., "localhost:8080" or "my-server.org"
	    }
	    int colon=host.indexOf(':');
	    if (colon>=0) {
	    	host=host.substring(0,colon);
	    }
	    return host;
	}

	
	/**
	 * Utility method to construct the external base URL
	 * @param ctx Javalin context
	 * @param basePath Path for base URL e.g. "mcp"
	 * @return Base URL for external use (possible localhost if external URL not available from Context)
	 */
	public static String getExternalBaseUrl(Context ctx, String basePath) {
	    // Identify protocol being used
	    String proto = ctx.header("X-Forwarded-Proto");
	    if (proto == null) {
	        proto = ctx.scheme(); // e.g., "http" or "https"
	    }
	    
	    String port = ctx.header("X-Forwarded-Port");
	    if (port==null) {
	    	port=Integer.toString(ctx.port());
	    }
	    
	    String host = getHostname(ctx);
	    
	    
	    String prefix = ctx.header("X-Forwarded-Prefix");
	
	    
	    // get the Port
	
	    // Build the base URL
	    StringBuilder baseUrl = new StringBuilder();
	
	    // Append protocol
	    baseUrl.append(proto).append("://");
	
	    // Append host
	    baseUrl.append(host);
	    
	    // Append port if non-standard and not already included in host
	    if (!host.contains(":")) {
	        if (("https".equalsIgnoreCase(proto) && "443".equals(port)) ||
	            ("http".equalsIgnoreCase(proto) && "80".equals(port))) {
	            // Standard hostname is fine since we are using correct protocol for peer
	        } else {
	        	baseUrl.append(':').append(port);
	        }
	    }
	
	    // Append base path (e.g., "/api/v1")
	    if (basePath != null && !basePath.isEmpty()) {
	        // Ensure basePath starts with a slash and doesn't end with one
	        String cleanedBasePath = basePath.startsWith("/") ? basePath : "/" + basePath;
	        cleanedBasePath = cleanedBasePath.endsWith("/") ? 
	                          cleanedBasePath.substring(0, cleanedBasePath.length() - 1) : 
	                          cleanedBasePath;
	        baseUrl.append(cleanedBasePath);
	    }
	
	    // Append prefix if provided by proxy
	    if (prefix != null && !prefix.isEmpty()) {
	        // Ensure prefix starts with a slash and doesn't end with one
	        prefix = prefix.startsWith("/") ? prefix : "/" + prefix;
	        prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
	        // Only append prefix if it's not already part of the basePath
	        if (!baseUrl.toString().endsWith(prefix)) {
	            baseUrl.append(prefix);
	        }
	    }
	
	    return baseUrl.toString();
	}
	


	/**
	 * Get a pagination range from query params as an [start,end, fullLimit] array
	 * @param ctx Javalin content
	 * @param maxIndex Maximum element index (exclusive), typically the number of elements
	 * @return
	 */
	protected long[] getPaginationRange(Context ctx, long maxIndex) {
		try {
			return paginationRange(ctx.queryParam("offset"), ctx.queryParam("limit"), maxIndex);
		} catch (BadRequestResponse e) {
			throw e;
		} catch (NumberFormatException e) {
			throw new BadRequestResponse("Unable to parse offset/limit");
		}
	}

	/**
	 * Pure pagination logic, split out from {@link Context} extraction so it can be
	 * unit-tested. Returns {@code [start, end, limit]}.
	 *
	 * <p>The limit defaults to {@link #DEFAULT_LIMIT} when absent and is clamped to
	 * {@link #MAX_LIMIT}, so a request cannot drive an unbounded read regardless of
	 * the value supplied (#660).</p>
	 *
	 * @param offsetParam Raw {@code offset} query parameter, or null
	 * @param limitParam Raw {@code limit} query parameter, or null
	 * @param maxIndex Maximum element index (exclusive)
	 * @return {@code [start, end, limit]}
	 */
	static long[] paginationRange(String offsetParam, String limitParam, long maxIndex) {
		long offset=(offsetParam==null)?0:Long.parseLong(offsetParam.trim());
		if (offset<0) throw new BadRequestResponse("Negative offset parameter: "+offset);
		if (offset>maxIndex) throw new BadRequestResponse("Offset out of range: "+offset);

		long limit=(limitParam==null)?DEFAULT_LIMIT:Long.parseLong(limitParam.trim());
		if (limit<0) throw new BadRequestResponse("Negative limit parameter: "+limit);
		if (limit>MAX_LIMIT) limit=MAX_LIMIT;

		return new long[] {offset, Math.min(maxIndex, offset+limit), limit};
	}

	/**
	 * Attempt to resolve an address from an arbitrary object, including possible CNS lookups.
	 * @param o Object to resolve an address from.
	 * @return Address instance, or null if not a valid address.
	 */
	protected Address resolveAddress(ACell o) {
		if (o==null) return null;

		if (o instanceof Address a) {
			return a;
		}

		if (o instanceof Symbol sym) {
			State state=server.getState();
			ACell cnsValue=state.lookupCNS(sym);
			return resolveAddress(cnsValue);
		}

		if (o instanceof AVector v) {
			if (v.count() !=2) return null; // must be a scoped address
			return resolveAddress(v.get(0)); // resolve the base address
		}

		try {
			// If it's a String, try to parse it as an address
			AString s = RT.ensureString(o);
			if (s != null) {
				// remove the @ prefix if it exists
				if (s.startsWith("@")) s=s.slice(1);
				return resolveAddress(Reader.read(s));
			}

			// If it's a Keyword, try to parse it as an address
			return null;
		} catch (Exception e) {
			throw new IllegalArgumentException("Unable to resolve address from object: "+o+" cause: "+e.getMessage(), e);
		}
	}
}
