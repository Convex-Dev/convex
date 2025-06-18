package convex.java;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.util.JSONUtils;

/**
 * Base class for REST client instances 
 */
public class ARESTClient {

	
	private final URI host;
	private URI baseURI;
	
	public ARESTClient(URI host,String basePath) {
		this.host=host;
		this.baseURI=host.resolve(basePath);
	}

	public URI getHost() {
		return host;
	}
	
	/**
	 * Gets the base URI for the target server, e.g. "https://foo.com/api/v1/"
	 * @return URI
	 */
	public URI getBaseURI() {
		return baseURI;
	}
	
	/**
	 * Makes a HTTP request as a CompletableFuture
	 * @param request Request object
	 * @return Future to be filled with JSON response.
	 */
	protected CompletableFuture<Result> doRequest(SimpleHttpRequest request) {
		try {
			CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(request);
			return future.thenApply(response->{
				String rbody=null;
				try {
					rbody=response.getBody().getBodyText();
					return Result.create(null,JSONUtils.parseJSON5(rbody));
				} catch (Exception e) {
					if (rbody==null) rbody="<Body not readable as String>";
					Result res= Result.error(ErrorCodes.FORMAT,"Can't parse JSON body: " +rbody);
					return res;
				}
			});
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e));
		}
	}

}
