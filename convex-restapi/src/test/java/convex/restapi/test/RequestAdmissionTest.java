package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.api.ContentTypes;
import convex.restapi.RESTConfig;
import convex.restapi.RESTServer;

/**
 * Integration test for the global request-admission cap, in particular that a request
 * holding an admission permit releases it even when a before-handler rejects the request.
 * The cap is set to 1 so a leaked permit would immediately turn later requests into 503s.
 * The cap belongs to the REST server, so this one serves the shared test peer.
 */
public class RequestAdmissionTest extends ARESTTest {

	private static RESTServer admissionServer;
	private static String hostPath;

	private static final byte[] MCP_PING="{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"
			.getBytes(StandardCharsets.UTF_8);

	@BeforeAll
	public static void launch() {
		RESTConfig config=RESTConfig.parse("{\"rest\":{\"maxConcurrentRequests\":1}}");
		admissionServer=RESTServer.create(server.getServer(),config);
		admissionServer.start(0);
		hostPath="http://localhost:"+admissionServer.getPort();
	}

	@AfterAll
	public static void close() {
		if (admissionServer!=null) admissionServer.close();
	}

	@Test
	public void testRejectedRequestReleasesPermit() throws IOException, InterruptedException {
		// The cap is 1. Each oversized request acquires the sole admission permit and is then
		// rejected by the body-size before-handler. If that permit were not released on the
		// rejection, the next request would be admission-blocked (503) rather than get its
		// own 413 — so repeated 413s prove the permit is always released.
		byte[] oversized=validMcpBody((int) RESTServer.MAX_REQUEST_BODY_BYTES+1);
		for (int i=0;i<5;i++) {
			assertEquals(413, postBytes("/mcp", ContentTypes.JSON, oversized).statusCode(),
					"oversized request "+i+" must be rejected as 413, not admission-blocked (503)");
		}
		// A normal request still gets through, confirming the lane was never left saturated.
		assertEquals(200, postBytes("/mcp", ContentTypes.JSON, MCP_PING).statusCode());
	}

	private static byte[] validMcpBody(int size) {
		byte[] body=new byte[size];
		Arrays.fill(body,(byte) ' ');
		System.arraycopy(MCP_PING,0,body,0,MCP_PING.length);
		return body;
	}

	private static HttpResponse<String> postBytes(String path, String contentType, byte[] body)
			throws IOException, InterruptedException {
		HttpRequest request=HttpRequest.newBuilder()
				.uri(URI.create(hostPath+path))
				.header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
