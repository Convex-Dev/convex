package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.dlfs.DLFSServer;
import convex.dlfs.DLFSWebDAV;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFileSystem;

/**
 * Basic HTTP tests for the DLFS WebDAV server with multi-drive support.
 */
public class DLFSServerTest {

	private static DLFSServer server;
	private static HttpClient client;
	private static String baseURL;
	/** Base URL for the pre-seeded "test" drive */
	private static String driveURL;

	@Test
	void testCanonicalMountPathIsExposed() {
		assertEquals("/dlfs/", DLFSWebDAV.MOUNT_PATH);
	}

	@BeforeAll
	static void setUp() {
		server = DLFSServer.createEphemeral(); // no auth for basic tests
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
	void testMalformedMethodRejected() throws Exception {
		// Method tokens Javalin cannot route (hyphenated/lowercase) → 501, not 500
		HttpRequest hyphenated = HttpRequest.newBuilder()
				.uri(URI.create(driveURL))
				.method("M-SEARCH", HttpRequest.BodyPublishers.noBody())
				.build();
		assertEquals(501, client.send(hyphenated, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest lowercase = HttpRequest.newBuilder()
				.uri(URI.create(driveURL))
				.method("propfind", HttpRequest.BodyPublishers.noBody())
				.build();
		assertEquals(501, client.send(lowercase, HttpResponse.BodyHandlers.ofString()).statusCode());

		// Well-formed but unknown method falls through to normal routing → 404
		HttpRequest unknown = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "no-such-place"))
				.method("FOOBAR", HttpRequest.BodyPublishers.noBody())
				.build();
		assertEquals(404, client.send(unknown, HttpResponse.BodyHandlers.ofString()).statusCode());
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
	void testCopyAboveRequestLimitOverwritesAndSharesBlobData() throws Exception {
		try (DLFSServer copyServer=DLFSServer.createEphemeral().setMaxRequestSize(1024)) {
			copyServer.getDriveManager().createDrive(null,"copy-test");
			DLFileSystem fs=(DLFileSystem)copyServer.getDriveManager().getDrive(null,"copy-test");
			Path source=fs.getPath("/source.bin");
			Path target=fs.getPath("/target.bin");
			Files.write(source,new byte[2048]);
			Files.write(target,new byte[] {1});
			ABlob sourceData=DLFSNode.getData(fs.getNode((convex.lattice.fs.DLPath)source));

			copyServer.start(0);
			String sourceURL="http://localhost:"+copyServer.getPort()+"/dlfs/copy-test/source.bin";
			String targetURL="http://localhost:"+copyServer.getPort()+"/dlfs/copy-test/target.bin";
			HttpRequest copy=HttpRequest.newBuilder(URI.create(sourceURL))
				.method("COPY",HttpRequest.BodyPublishers.noBody())
				.header("Destination",targetURL)
				.build();

			HttpResponse<String> response=client.send(copy,HttpResponse.BodyHandlers.ofString());
			assertEquals(204,response.statusCode());
			ABlob targetData=DLFSNode.getData(fs.getNode((convex.lattice.fs.DLPath)target));
			assertSame(sourceData,targetData,"WebDAV COPY should structurally share immutable blob data");
		}
	}

	@Test
	void testMoveAboveRequestLimitOverwritesAndSharesBlobData() throws Exception {
		try (DLFSServer moveServer=DLFSServer.createEphemeral().setMaxRequestSize(1024)) {
			moveServer.getDriveManager().createDrive(null,"move-test");
			DLFileSystem fs=(DLFileSystem)moveServer.getDriveManager().getDrive(null,"move-test");
			Path source=fs.getPath("/source.bin");
			Path target=fs.getPath("/target.bin");
			Files.write(source,new byte[2048]);
			Files.write(target,new byte[] {1});
			ABlob sourceData=DLFSNode.getData(fs.getNode((convex.lattice.fs.DLPath)source));

			moveServer.start(0);
			String sourceURL="http://localhost:"+moveServer.getPort()+"/dlfs/move-test/source.bin";
			String targetURL="http://localhost:"+moveServer.getPort()+"/dlfs/move-test/target.bin";
			HttpRequest move=HttpRequest.newBuilder(URI.create(sourceURL))
				.method("MOVE",HttpRequest.BodyPublishers.noBody())
				.header("Destination",targetURL)
				.build();

			HttpResponse<String> response=client.send(move,HttpResponse.BodyHandlers.ofString());
			assertEquals(204,response.statusCode());
			assertFalse(Files.exists(source));
			ABlob targetData=DLFSNode.getData(fs.getNode((convex.lattice.fs.DLPath)target));
			assertSame(sourceData,targetData,"WebDAV MOVE should structurally share immutable blob data");
		}
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
		assertEquals("1", dav, "Only implemented DAV class 1 semantics should be advertised");
		assertFalse(allow.contains("LOCK"), "Unsupported locking must not be advertised");
	}

	@Test
	void testUnsupportedWebDavMethodsFailHonestly() throws Exception {
		for (String method : new String[] { "LOCK", "UNLOCK", "PROPPATCH" }) {
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL))
				.method(method, HttpRequest.BodyPublishers.noBody())
				.build();
			assertEquals(501, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode(), method);
		}
	}

	@Test
	void testInvalidDriveNameRejected() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(URI.create(baseURL + "bad%3Aname/"))
			.method("MKCOL", HttpRequest.BodyPublishers.noBody())
			.build();
		assertEquals(400, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	@Test
	void testEncodedTraversalRejectedForWebDavPaths() throws Exception {
		var fs = server.getDriveManager().getDrive(null, "test");
		java.nio.file.Files.createDirectories(fs.getPath("/traversal-source"));
		java.nio.file.Files.writeString(fs.getPath("/traversal-secret.txt"), "secret");
		String traversal = driveURL + "traversal-source/%2e%2e/traversal-secret.txt";

		for (String method : new String[] { "GET", "HEAD", "DELETE", "PROPFIND" }) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(traversal))
				.method(method, HttpRequest.BodyPublishers.noBody())
				.build();
			assertEquals(400, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(), method);
		}

		HttpRequest put = HttpRequest.newBuilder(URI.create(traversal))
			.PUT(HttpRequest.BodyPublishers.ofString("overwritten"))
			.build();
		assertEquals(400, client.send(put, HttpResponse.BodyHandlers.ofString()).statusCode());
		assertEquals("secret", java.nio.file.Files.readString(fs.getPath("/traversal-secret.txt")));
	}

	@Test
	void testEncodedTraversalRejectedForMoveAndCopyDestinations() throws Exception {
		var fs = server.getDriveManager().getDrive(null, "test");
		java.nio.file.Files.createDirectories(fs.getPath("/destination-source"));
		java.nio.file.Files.writeString(fs.getPath("/destination-source/source.txt"), "source");
		String source = driveURL + "destination-source/source.txt";
		String destination = driveURL + "destination-source/%2e%2e/escaped.txt";

		for (String method : new String[] { "MOVE", "COPY" }) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(source))
				.method(method, HttpRequest.BodyPublishers.noBody())
				.header("Destination", destination)
				.build();
			assertEquals(409, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(), method);
			assertTrue(java.nio.file.Files.exists(fs.getPath("/destination-source/source.txt")));
			assertFalse(java.nio.file.Files.exists(fs.getPath("/escaped.txt")));
		}
	}

	@Test
	void testDirectoryMoveIsStructural() throws Exception {
		String source = driveURL + "move-directory-source/";
		HttpRequest create = HttpRequest.newBuilder(URI.create(source))
			.method("MKCOL", HttpRequest.BodyPublishers.noBody()).build();
		assertEquals(201, client.send(create, HttpResponse.BodyHandlers.ofString()).statusCode());

		HttpRequest move = HttpRequest.newBuilder(URI.create(source))
			.method("MOVE", HttpRequest.BodyPublishers.noBody())
			.header("Destination", driveURL + "move-directory-target/").build();
		assertEquals(201, client.send(move, HttpResponse.BodyHandlers.ofString()).statusCode());
		assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(source)).GET().build(),
			HttpResponse.BodyHandlers.ofString()).statusCode());
		assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(driveURL + "move-directory-target/")).GET().build(),
			HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	@Test
	void testETagWritePreconditions() throws Exception {
		String path = driveURL + "conditional.txt";
		HttpResponse<String> created = client.send(HttpRequest.newBuilder(URI.create(path))
			.PUT(HttpRequest.BodyPublishers.ofString("v1")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(201, created.statusCode());

		HttpResponse<String> get = client.send(HttpRequest.newBuilder(URI.create(path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		String etag = get.headers().firstValue("ETag").orElseThrow();

		HttpResponse<String> stale = client.send(HttpRequest.newBuilder(URI.create(path))
			.header("If-Match", "\"stale\"")
			.PUT(HttpRequest.BodyPublishers.ofString("bad")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(412, stale.statusCode());

		HttpResponse<String> replace = client.send(HttpRequest.newBuilder(URI.create(path))
			.header("If-Match", etag)
			.PUT(HttpRequest.BodyPublishers.ofString("v2")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(204, replace.statusCode());
		assertEquals("v2", client.send(HttpRequest.newBuilder(URI.create(path)).GET().build(),
			HttpResponse.BodyHandlers.ofString()).body());

		HttpResponse<String> createOnly = client.send(HttpRequest.newBuilder(URI.create(path))
			.header("If-None-Match", "*")
			.PUT(HttpRequest.BodyPublishers.ofString("v3")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(412, createOnly.statusCode());
	}

	@Test
	void testMutationUsesDynamicContextTimestamp() throws Exception {
		String path = driveURL + "timestamped.txt";
		client.send(HttpRequest.newBuilder(URI.create(path))
			.PUT(HttpRequest.BodyPublishers.ofString("time")).build(), HttpResponse.BodyHandlers.ofString());
		var fs = server.getDriveManager().getDrive(null, "test");
		long timestamp = java.nio.file.Files.getLastModifiedTime(fs.getPath("/timestamped.txt")).toMillis();
		assertTrue(timestamp > 0,"The default context policy supplies runtime time");
	}

	@Test
	void testRequestSizeLimit() throws Exception {
		try (DLFSServer limited = DLFSServer.createEphemeral().setMaxRequestSize(8)) {
			limited.getDriveManager().createDrive(null, "limited");
			limited.start(0);
			String url = "http://localhost:" + limited.getPort() + "/dlfs/limited/large.txt";
			HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
				.PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[9])).build(),
				HttpResponse.BodyHandlers.ofString());
			assertEquals(413, response.statusCode());
		}
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
