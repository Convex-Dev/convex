package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.dlfs.DLFSServer;

/**
 * Basic HTTP tests for the DLFS WebDAV server with multi-drive support.
 */
public class DLFSServerTest {

	private static DLFSServer server;
	private static HttpClient client;
	private static String baseURL;
	/** Base URL for the pre-seeded "test" drive */
	private static String driveURL;

	@BeforeAll
	static void setUp() {
		server = DLFSServer.create(null); // no auth for basic tests
		// Pre-seed a "test" drive for anonymous user
		server.getDriveManager().createDrive(null, "test");
		server.start(0); // random port
		baseURL = "http://localhost:" + server.getPort() + "/dlfs/";
		driveURL = baseURL + "test/";
		client = HttpClient.newHttpClient();
	}

	@AfterAll
	static void tearDown() {
		if (server != null) server.close();
	}

	@Test
	void testPutAndGetRoundTrip() throws Exception {
		String content = "Hello DLFS!";
		String path = driveURL + "test.txt";

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
		String path = driveURL + "overwrite.txt";

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
				.uri(URI.create(driveURL + "nonexistent.txt"))
				.GET()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	@Test
	void testDelete() throws Exception {
		String path = driveURL + "to-delete.txt";

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
				.uri(URI.create(driveURL + "no-such-file.txt"))
				.DELETE()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	@Test
	void testHead() throws Exception {
		String content = "head test content";
		String path = driveURL + "head-test.txt";

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
				.uri(URI.create(driveURL + "missing.txt"))
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
	void testGetDriveRoot() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL))
				.GET()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().startsWith("Directory:"), "Drive root should be a directory");
	}

	@Test
	void testBinaryContent() throws Exception {
		byte[] binary = new byte[256];
		for (int i = 0; i < 256; i++) binary[i] = (byte) i;
		String path = driveURL + "binary.bin";

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
		String path = driveURL + "data.json";
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
		String path = driveURL + "no-parent/child.txt";
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("orphan"))
				.build();
		HttpResponse<String> resp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(409, resp.statusCode(), "Should conflict when parent doesn't exist");
	}

	@Test
	void testPutToDriveRoot() throws Exception {
		// Trying to PUT at drive level (no file path) should be rejected
		String path = baseURL + "test";
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("bad"))
				.build();
		HttpResponse<String> resp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(403, resp.statusCode(), "PUT to drive root should be Forbidden");
		assertTrue(resp.body().contains("MKCOL"), "Error should suggest using MKCOL");
	}

	@Test
	void testPutToNonexistentDrive() throws Exception {
		String path = baseURL + "nosuchdrive/file.txt";
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(path))
				.PUT(HttpRequest.BodyPublishers.ofString("orphan"))
				.build();
		HttpResponse<String> resp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode(), "Should return Not Found when drive doesn't exist");
	}

	@Test
	void testCreateAndDeleteDrive() throws Exception {
		// Create drive via MKCOL
		HttpRequest mkcolReq = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "newdrive/"))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> mkcolResp = client.send(mkcolReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, mkcolResp.statusCode());

		// Verify drive exists (PUT + GET)
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "newdrive/hello.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("hello"))
				.build();
		HttpResponse<String> putResp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, putResp.statusCode());

		// Duplicate create should fail
		HttpResponse<String> dup = client.send(mkcolReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(405, dup.statusCode());

		// Delete drive
		HttpRequest delReq = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "newdrive"))
				.DELETE()
				.build();
		HttpResponse<String> delResp = client.send(delReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(204, delResp.statusCode());
	}

	@Test
	void testRenameDrive() throws Exception {
		// Create drive via MKCOL
		HttpRequest mkcolReq = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "before-rename/"))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> mkcolResp = client.send(mkcolReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, mkcolResp.statusCode());

		// Put a file in it
		HttpRequest putReq = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "before-rename/data.txt"))
				.PUT(HttpRequest.BodyPublishers.ofString("rename test"))
				.build();
		client.send(putReq, HttpResponse.BodyHandlers.ofString());

		// Rename via MOVE
		HttpRequest moveReq = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "before-rename/"))
				.method("MOVE", HttpRequest.BodyPublishers.noBody())
				.header("Destination", baseURL + "after-rename/")
				.build();
		HttpResponse<String> moveResp = client.send(moveReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, moveResp.statusCode(), "Drive rename should succeed");

		// Old name should be gone
		HttpRequest getOld = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "before-rename/data.txt"))
				.GET()
				.build();
		HttpResponse<String> oldResp = client.send(getOld, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, oldResp.statusCode(), "Old drive name should not exist");

		// New name should have the file
		HttpRequest getNew = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "after-rename/data.txt"))
				.GET()
				.build();
		HttpResponse<String> newResp = client.send(getNew, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, newResp.statusCode());
		assertEquals("rename test", newResp.body(), "File content should survive rename");
	}

	@Test
	void testDriveListing() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL))
				.GET()
				.build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("test"), "Should list the 'test' drive");
	}
}
