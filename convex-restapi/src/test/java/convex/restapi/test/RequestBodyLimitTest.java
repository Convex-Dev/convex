package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
	public void testChunkedBoundary() throws IOException, InterruptedException {
		// A chunked body declares no length, so only the read itself bounds it
		byte[] exact = validMcpBody((int) RESTServer.MAX_REQUEST_BODY_BYTES);
		assertEquals(200, postBytes("/mcp", ContentTypes.JSON, exact, true).statusCode());
		byte[] oversized = validMcpBody((int) RESTServer.MAX_REQUEST_BODY_BYTES+1);
		assertEquals(413, postBytes("/mcp", ContentTypes.JSON, oversized, true).statusCode());
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

	/**
	 * A request answered without its handler reading the body still has the body read before
	 * the response is sent, so the response arrives and the connection stays open.
	 */
	@Test
	public void testUnreadBodyIsReadBeforeResponding() throws IOException {
		// More than Jetty reads by itself when completing a request (16 reads of at most
		// 8 KB), and within the body limit
		byte[] body = new byte[256*1024];
		Arrays.fill(body, (byte) ' ');
		try (Socket socket = new Socket("localhost", port)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = new BufferedInputStream(socket.getInputStream());
			out.write(("POST /api/v1/no-such-endpoint HTTP/1.1\r\nHost: localhost\r\n"
					+"Content-Type: application/json\r\nContent-Length: "+body.length+"\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			out.write(body);
			out.flush();
			Map<String,String> headers = new HashMap<>();
			assertEquals(404, readResponse(in, headers));
			assertNull(headers.get("connection"), "The server should keep the connection open");

			out.write("GET /api/v1/status HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			assertEquals(200, readResponse(in, new HashMap<>()));
		}
	}

	/**
	 * Reads one HTTP/1.1 response with a Content-Length or chunked body.
	 *
	 * @return Status code
	 */
	private static int readResponse(InputStream in, Map<String,String> headers) throws IOException {
		String status = readLine(in);
		for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
			int colon = line.indexOf(':');
			headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon+1).trim());
		}
		if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
			for (long size = Long.parseLong(readLine(in), 16); size > 0; size = Long.parseLong(readLine(in), 16)) {
				in.readNBytes((int) size);
				readLine(in);
			}
			while (!readLine(in).isEmpty()) {} // trailers
		} else if (headers.containsKey("content-length")) {
			in.readNBytes(Integer.parseInt(headers.get("content-length")));
		}
		return Integer.parseInt(status.split(" ")[1]);
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		for (int b = in.read(); b != '\n'; b = in.read()) {
			if (b < 0) throw new EOFException("Connection closed mid-line");
			if (b != '\r') line.write(b);
		}
		return line.toString(StandardCharsets.US_ASCII);
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
