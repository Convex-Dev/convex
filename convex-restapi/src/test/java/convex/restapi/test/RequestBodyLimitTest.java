package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import convex.api.ContentTypes;
import convex.restapi.RESTServer;

/** Integration tests for the public request-body ceiling. */
public class RequestBodyLimitTest extends ARESTTest {

	private static final byte[] MCP_PING = "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"
			.getBytes(StandardCharsets.UTF_8);

	@Test
	public void testFixedLengthBoundary() throws IOException, InterruptedException {
		byte[] exact = validMcpBody((int) RESTServer.MAX_REQUEST_BODY_BYTES);
		assertEquals(200, postBytes("/mcp", ContentTypes.JSON, exact, false).statusCode());

		// Deliberately without Expect: 100-continue, so this exercises the drain path. The
		// client streams a body the server has already decided to reject; without draining,
		// closing on unread data resets the connection and destroys the 413 in transit.
		byte[] oversized = validMcpBody((int) RESTServer.MAX_REQUEST_BODY_BYTES+1);
		assertEquals(413, postBytes("/mcp", ContentTypes.JSON, oversized, false).statusCode());
	}

	@Test
	public void testFixedLengthBoundaryWithExpectContinue() throws IOException, InterruptedException {
		// A client offering Expect: 100-continue is rejected on Content-Length alone and never
		// sends the body, so there is nothing to drain and no reset to race.
		byte[] oversized = validMcpBody((int) RESTServer.MAX_REQUEST_BODY_BYTES+1);
		assertEquals(413, postBytes("/mcp", ContentTypes.JSON, oversized, false, true).statusCode());
	}

	@Test
	public void testChunkedBodiesAreBoundedAcrossStreamingParsers() throws IOException, InterruptedException {
		byte[] oversized = new byte[(int) RESTServer.MAX_REQUEST_BODY_BYTES+1];
		Arrays.fill(oversized, (byte) ' ');

		assertEquals(413, postBytes("/api/v1/query", ContentTypes.JSON, oversized, true).statusCode());
		assertEquals(413, postBytes("/api/v1/data/encode", ContentTypes.CVX, oversized, true).statusCode());
		assertEquals(413, postBytes("/api/v1/data/decode", ContentTypes.BYTES, oversized, true).statusCode());
		assertEquals(413, postBytes("/mcp", ContentTypes.JSON, oversized, true).statusCode());
	}

	private static byte[] validMcpBody(int size) {
		if (size<MCP_PING.length) throw new IllegalArgumentException("MCP body size too small");
		byte[] body = new byte[size];
		Arrays.fill(body, (byte) ' ');
		System.arraycopy(MCP_PING, 0, body, 0, MCP_PING.length);
		return body;
	}

	private static HttpResponse<String> postBytes(String path, String contentType, byte[] body,
			boolean chunked) throws IOException, InterruptedException {
		return postBytes(path, contentType, body, chunked, false);
	}

	private static HttpResponse<String> postBytes(String path, String contentType, byte[] body,
			boolean chunked, boolean expectContinue) throws IOException, InterruptedException {
		BodyPublisher publisher = chunked
				? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
				: HttpRequest.BodyPublishers.ofByteArray(body);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(HOST_PATH+path))
				.header("Content-Type", contentType)
				.expectContinue(expectContinue)
				.POST(publisher)
				.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
