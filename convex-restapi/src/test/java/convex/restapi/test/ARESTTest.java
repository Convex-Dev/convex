package convex.restapi.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import convex.core.crypto.AKeyPair;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTServer;

public abstract class ARESTTest {
	protected static RESTServer server;
	protected static int port;
	protected static String HOST_PATH;
	protected static String API_PATH;
	protected static AKeyPair KP;
	protected static final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	
	static {
		try {
			Server s = API.launchPeer();
			RESTServer rs = RESTServer.create(s);
			rs.start(0);
			port = rs.getPort();
			server = rs;
			HOST_PATH="http://localhost:" + rs.getPort();
			API_PATH=HOST_PATH+"/api/v1";
			KP=s.getKeyPair();
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	/**
	 * Helper method to make HTTP GET requests
	 */
	protected static HttpResponse<String> get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.GET()
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
	
	/**
	 * Helper method to make HTTP POST requests with JSON body
	 */
	protected static HttpResponse<String> post(String url, String jsonBody) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
