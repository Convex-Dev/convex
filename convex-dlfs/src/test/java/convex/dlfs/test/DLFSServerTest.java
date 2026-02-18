package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.dlfs.DLFSServer;

/**
 * Basic HTTP tests for the DLFS WebDAV server.
 */
public class DLFSServerTest {

	private static DLFSServer server;
	private static HttpClient client;
	private static String baseURL;

	@BeforeAll
	static void setUp() {
		server = DLFSServer.create(null); // no auth for basic tests
		server.start(0); // random port
		baseURL = "http://localhost:" + server.getPort() + "/dlfs/";
		client = HttpClient.newHttpClient();
	}

	@AfterAll
	static void tearDown() {
		if (server != null) server.close();
	}

	@Test
	void testPutAndGetRoundTrip() throws Exception {
		String content = "Hello DLFS!";
		String path = baseURL + "test.txt";

		// PUT file
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString(content))
				.build();
		HttpResponse<String> putResp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, putResp.statusCode(), "New file should return 201 Created");

		// GET file
		HttpRequest getReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.GET()
				.build();
		HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, getResp.statusCode());
		assertEquals(content, getResp.body());
	}

	@Test
	void testPutOverwrite() throws Exception {
		String path = baseURL + "overwrite.txt";

		// First PUT — create
		HttpRequest put1 = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("v1"))
				.build();
		HttpResponse<String> resp1 = client.send(put1, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, resp1.statusCode());

		// Second PUT — overwrite
		HttpRequest put2 = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("v2"))
				.build();
		HttpResponse<String> resp2 = client.send(put2, HttpResponse.BodyHandlers.ofString());
		assertEquals(204, resp2.statusCode(), "Overwrite should return 204 No Content");

		// GET — verify updated content
		HttpRequest getReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.GET()
				.build();
		HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
		assertEquals("v2", getResp.body());
	}

	@Test
	void testGetNotFound() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "nonexistent.txt"))
				.GET()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	@Test
	void testDelete() throws Exception {
		String path = baseURL + "to-delete.txt";

		// Create file
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("delete me"))
				.build();
		client.send(putReq, HttpResponse.BodyHandlers.ofString());

		// Delete file
		HttpRequest delReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.DELETE()
				.build();
		HttpResponse<String> delResp = client.send(delReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(204, delResp.statusCode());

		// Verify gone
		HttpRequest getReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.GET()
				.build();
		HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, getResp.statusCode());
	}

	@Test
	void testDeleteNotFound() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "no-such-file.txt"))
				.DELETE()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	@Test
	void testHead() throws Exception {
		String content = "head test content";
		String path = baseURL + "head-test.txt";

		// Create file
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString(content))
				.build();
		client.send(putReq, HttpResponse.BodyHandlers.ofString());

		// HEAD request
		HttpRequest headReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.method("HEAD", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> headResp = client.send(headReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, headResp.statusCode());
		assertEquals(String.valueOf(content.length()),
				headResp.headers().firstValue("Content-Length").orElse(null));
		assertTrue(headResp.body().isEmpty(), "HEAD should have no body");
	}

	@Test
	void testHeadNotFound() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "missing.txt"))
				.method("HEAD", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	@Test
	void testOptions() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());

		String allow = resp.headers().firstValue("Allow").orElse("");
		assertTrue(allow.contains("GET"), "Allow should contain GET");
		assertTrue(allow.contains("PUT"), "Allow should contain PUT");
		assertTrue(allow.contains("DELETE"), "Allow should contain DELETE");

		String dav = resp.headers().firstValue("DAV").orElse("");
		assertTrue(dav.contains("1"), "DAV header should include class 1");
	}

	@Test
	void testGetRoot() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL))
				.GET()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().startsWith("Directory:"), "Root should be a directory");
	}

	@Test
	void testBinaryContent() throws Exception {
		byte[] binary = new byte[256];
		for (int i = 0; i < 256; i++) binary[i] = (byte) i;
		String path = baseURL + "binary.bin";

		// PUT binary
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofByteArray(binary))
				.build();
		client.send(putReq, HttpResponse.BodyHandlers.ofString());

		// GET binary
		HttpRequest getReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.GET()
				.build();
		HttpResponse<byte[]> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, getResp.statusCode());
		assertArrayEquals(binary, getResp.body());
	}

	@Test
	void testContentType() throws Exception {
		String path = baseURL + "data.json";
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("{\"key\":\"value\"}"))
				.build();
		client.send(putReq, HttpResponse.BodyHandlers.ofString());

		HttpRequest getReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.GET()
				.build();
		HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
		assertTrue(getResp.headers().firstValue("Content-Type").orElse("")
				.contains("application/json"));
	}

	@Test
	void testPutToNonexistentParent() throws Exception {
		// PUT to a path where parent directory doesn't exist
		String path = baseURL + "no-parent/child.txt";
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("orphan"))
				.build();
		HttpResponse<String> resp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(409, resp.statusCode(), "Should conflict when parent doesn't exist");
	}
}
