package convex.restapi.api;

import convex.peer.Server;
import convex.restapi.RESTServer;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

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
	        if (!("https".equalsIgnoreCase(proto) && "443".equals(port)) &&
	            !("http".equalsIgnoreCase(proto) && "80".equals(port))) {
	            baseUrl.append(":").append(port);
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
	
	private static final long DEFAULT_LIMIT = 10;

	/**
	 * Get a pagination range from query params as an [start,end] array]
	 * @param ctx Javalin content
	 * @param max Maximum element index (exclusive), typically the number of elements
	 * @return
	 */
	protected long[] getPaginationRange(Context ctx, long max) {
		long[] range=new long[2];
		try {
			String offsetParam=ctx.queryParam("offset");
			long offset=(offsetParam==null)?0:Integer.parseInt(offsetParam);
			if (offset<0) throw new BadRequestResponse("Negative offset parameter: "+offset);
			if (offset>max) throw new BadRequestResponse("Offset out of range: "+offset);
			
			String limitParam=ctx.queryParam("limit");
			long limit=(offsetParam==null)?DEFAULT_LIMIT:Integer.parseInt(limitParam);
			if (limit<0) throw new BadRequestResponse("Negative limit parameter: "+limit);

			range[0]=offset;
			range[1]=Math.min(max, offset+limit);
		} catch (BadRequestResponse e) {
			throw e;
		} catch (NumberFormatException e) {
			throw new BadRequestResponse("Unable to parse offset/limit");
		}
		return range;
	}


}
