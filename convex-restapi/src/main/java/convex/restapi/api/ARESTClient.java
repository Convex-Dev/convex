package convex.restapi.api;

import java.net.URI;

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
	
	public URI getBaseURI() {
		return baseURI;
	}
}
