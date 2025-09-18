package convex.java;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;


import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.util.JSON;

/**
 * Base class for REST client instances 
 */
public class ARESTClient {

	
	private final URI host;
	private final URI baseURI;
	private final HttpClient httpClient;
	
	public ARESTClient(URI host,String basePath) {
		this.host = host;
		this.baseURI = host.resolve(basePath);
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(30))
				.build();
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
	protected CompletableFuture<Result> doRequest(HttpRequest request) {
		try {
			CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
			return future.thenApply(response->{
				String rbody = null;
				try {
					rbody = response.body();
					return Result.create(null, JSON.parseJSON5(rbody));
				} catch (Exception e) {
					if (rbody==null) rbody="<Body not readable as String>";
					Result res = Result.error(ErrorCodes.FORMAT,"Can't parse JSON body: " +rbody);
					return res;
				}
			});
		} catch (Exception e) {
			return CompletableFuture.completedFuture(Result.fromException(e));
		}
	}

}
