package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;

import convex.dlfs.DLFSServer;

/**
 * WebDAV tests using the Sardine client for PROPFIND/MKCOL,
 * plus raw HTTP for edge cases. All tests operate within pre-seeded drives.
 */
public class WebDAVTest {

	private static DLFSServer server;
	private static String baseURL;
	/** Base URL for the pre-seeded "webdav" drive */
	private static String driveURL;
	private static HttpClient httpClient;

	@BeforeAll
	static void setUp() {
		server = DLFSServer.create(null);
		server.getDriveManager().createDrive(null, "webdav");
		server.start(0);
		baseURL = "http://localhost:" + server.getPort() + "/dlfs/";
		driveURL = baseURL + "webdav/";
		httpClient = HttpClient.newHttpClient();
	}

	@AfterAll
	static void tearDown() {
		if (server != null) server.close();
	}

	// ==================== PROPFIND ====================

	@Test
	void testPropfindDriveRoot() throws Exception {
		Sardine sardine = SardineFactory.begin();
		List<DavResource> resources = sardine.list(driveURL);
		assertFalse(resources.isEmpty(), "Drive root listing should not be empty");

		DavResource root = resources.get(0);
		assertTrue(root.isDirectory(), "Drive root should be a directory");
	}

	@Test
	void testPropfindDriveListing() throws Exception {
		Sardine sardine = SardineFactory.begin();
		List<DavResource> resources = sardine.list(baseURL);
		assertFalse(resources.isEmpty(), "Drive listing should not be empty");
		assertTrue(resources.get(0).isDirectory(), "Root should be a directory");
	}

	@Test
	void testPropfindAfterPut() throws Exception {
		Sardine sardine = SardineFactory.begin();
		byte[] content = "propfind test".getBytes();
		sardine.put(driveURL + "propfind-file.txt", content);

		// PROPFIND drive root should list the file
		List<DavResource> resources = sardine.list(driveURL);
		boolean found = resources.stream()
				.anyMatch(r -> r.getName().equals("propfind-file.txt"));
		assertTrue(found, "File should appear in PROPFIND listing");
	}

	@Test
	void testPropfindDepthZero() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL))
				.method("PROPFIND", HttpRequest.BodyPublishers.noBody())
				.header("Depth", "0")
				.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(207, resp.statusCode());
		String body = resp.body();
		assertTrue(body.contains("multistatus"), "Should contain multistatus XML");
		// Depth 0 should only have the root, not children
		int responseCount = countOccurrences(body, "<D:response>");
		assertEquals(1, responseCount, "Depth 0 should have exactly 1 response element");
	}

	@Test
	void testPropfindDriveListingDepthZero() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL))
				.method("PROPFIND", HttpRequest.BodyPublishers.noBody())
				.header("Depth", "0")
				.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(207, resp.statusCode());
		String body = resp.body();
		int responseCount = countOccurrences(body, "<D:response>");
		assertEquals(1, responseCount, "Depth 0 on drive listing should have exactly 1 response");
	}

	@Test
	void testPropfindNotFound() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(driveURL + "nonexistent-dir/"))
				.method("PROPFIND", HttpRequest.BodyPublishers.noBody())
				.header("Depth", "0")
				.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	@Test
	void testPropfindNonexistentDrive() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(baseURL + "nosuchdrive/"))
				.method("PROPFIND", HttpRequest.BodyPublishers.noBody())
				.header("Depth", "0")
				.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	// ==================== MKCOL ====================

	@Test
	void testMkcol() throws Exception {
		Sardine sardine = SardineFactory.begin();
		String dirURL = driveURL + "testdir/";

		sardine.createDirectory(dirURL);

		// Verify directory exists via PROPFIND
		List<DavResource> resources = sardine.list(dirURL);
		assertFalse(resources.isEmpty());
		assertTrue(resources.get(0).isDirectory());
	}

	@Test
	void testMkcolAndListContents() throws Exception {
		Sardine sardine = SardineFactory.begin();
		String dirURL = driveURL + "parent-dir/";

		sardine.createDirectory(dirURL);

		// Put a file inside
		sardine.put(dirURL + "child.txt", "child content".getBytes());

		// List directory
		List<DavResource> resources = sardine.list(dirURL);
		boolean foundChild = resources.stream()
				.anyMatch(r -> r.getName().equals("child.txt"));
		assertTrue(foundChild, "Child file should appear in directory listing");
	}

	@Test
	void testMkcolAlreadyExists() throws Exception {
		String dirURL = driveURL + "existing-dir/";

		// Create directory
		HttpRequest req1 = HttpRequest.newBuilder()
				.uri(URI.create(dirURL))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, resp1.statusCode());

		// Try to create again — should fail
		HttpRequest req2 = HttpRequest.newBuilder()
				.uri(URI.create(dirURL))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
		assertEquals(405, resp2.statusCode(), "MKCOL on existing resource should return 405");
	}

	@Test
	void testMkcolNoParent() throws Exception {
		String dirURL = driveURL + "no-such-parent/child-dir/";
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(dirURL))
				.method("MKCOL", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(409, resp.statusCode(), "MKCOL without parent should return 409");
	}

	// ==================== Sardine CRUD Lifecycle ====================

	@Test
	void testSardineCrudLifecycle() throws Exception {
		Sardine sardine = SardineFactory.begin();

		// Create directory
		String dir = driveURL + "crud-dir/";
		sardine.createDirectory(dir);

		// Put file
		String fileURL = dir + "lifecycle.txt";
		sardine.put(fileURL, "version 1".getBytes());

		// Read file
		InputStream is = sardine.get(fileURL);
		String content = new String(is.readAllBytes());
		assertEquals("version 1", content);
		is.close();

		// Overwrite
		sardine.put(fileURL, "version 2".getBytes());

		// Verify
		is = sardine.get(fileURL);
		content = new String(is.readAllBytes());
		assertEquals("version 2", content);
		is.close();

		// Delete file
		sardine.delete(fileURL);

		// Verify deleted (should not appear in listing)
		List<DavResource> resources = sardine.list(dir);
		boolean found = resources.stream()
				.anyMatch(r -> r.getName().equals("lifecycle.txt"));
		assertFalse(found, "Deleted file should not appear");
	}

	// ==================== Utilities ====================

	private static int countOccurrences(String str, String sub) {
		int count = 0;
		int idx = 0;
		while ((idx = str.indexOf(sub, idx)) != -1) {
			count++;
			idx += sub.length();
		}
		return count;
	}
}
