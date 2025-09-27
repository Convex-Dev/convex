package convex.restapi.api;

import convex.peer.Server;
import convex.restapi.RESTServer;
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
	


	
	/**
	 * Utility method to construct the external base URL
	 * @param ctx Javalin context
	 * @param basePath Path for base URL e.g. "mcp"
	 * @return Base URL for external use (possible localhost if external URL not available from Context)
	 */
	public static String getExternalBaseUrl(Context ctx, String basePath) {
	    // Try to get information from forwarded headers
	    String proto = ctx.header("X-Forwarded-Proto");
	    String host = ctx.header("X-Forwarded-Host");
	    String port = ctx.header("X-Forwarded-Port");
	    String prefix = ctx.header("X-Forwarded-Prefix");
	
	    // Fallback to local request info if headers are missing
	    if (proto == null) {
	        proto = ctx.scheme(); // e.g., "http" or "https"
	    }
	    if (host == null) {
	        host = ctx.host(); // e.g., "localhost:8080" or "my-server.org"
	    }
	
	    // Build the base URL
	    StringBuilder baseUrl = new StringBuilder();
	
	    // Append protocol
	    baseUrl.append(proto).append("://");
	
	    // Append host
	    baseUrl.append(host);
	
	    // Append port if non-standard and not already included in host
	    if (port != null && !host.contains(":")) {
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

}
