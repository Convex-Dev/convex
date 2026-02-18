package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;

import convex.dlfs.DLFSServer;

/**
 * Full Sardine WebDAV integration tests covering CRUD lifecycle,
 * nested directories, binary content, large files, and edge cases.
 */
public class SardineIntegrationTest {

	private static DLFSServer server;
	private static String baseURL;

	@BeforeAll
	static void setUp() {
		server = DLFSServer.create(null);
		server.start(0);
		baseURL = "http://localhost:" + server.getPort() + "/dlfs/";
	}

	@AfterAll
	static void tearDown() {
		if (server != null) server.close();
	}

	// ==================== CRUD Lifecycle ====================

	@Test
	void testFullLifecycle() throws Exception {
		Sardine sardine = SardineFactory.begin();

		// Create directory
		String dir = baseURL + "lifecycle/";
		sardine.createDirectory(dir);

		// Put file
		String file = dir + "doc.txt";
		sardine.put(file, "version 1".getBytes());

		// Read back
		InputStream is = sardine.get(file);
		assertEquals("version 1", new String(is.readAllBytes()));
		is.close();

		// Overwrite
		sardine.put(file, "version 2".getBytes());

		// Read updated
		is = sardine.get(file);
		assertEquals("version 2", new String(is.readAllBytes()));
		is.close();

		// List directory
		List<DavResource> resources = sardine.list(dir);
		boolean found = resources.stream().anyMatch(r -> r.getName().equals("doc.txt"));
		assertTrue(found, "File should appear in listing");

		// Delete file
		sardine.delete(file);

		// Verify deleted
		resources = sardine.list(dir);
		found = resources.stream().anyMatch(r -> r.getName().equals("doc.txt"));
		assertFalse(found, "Deleted file should not appear");
	}

	// ==================== Nested Directories ====================

	@Test
	void testNestedDirectories() throws Exception {
		Sardine sardine = SardineFactory.begin();

		sardine.createDirectory(baseURL + "level1/");
		sardine.createDirectory(baseURL + "level1/level2/");
		sardine.createDirectory(baseURL + "level1/level2/level3/");

		// Put file deep in tree
		String file = baseURL + "level1/level2/level3/deep.txt";
		sardine.put(file, "deep content".getBytes());

		// Read back
		InputStream is = sardine.get(file);
		assertEquals("deep content", new String(is.readAllBytes()));
		is.close();

		// List intermediate directory
		List<DavResource> resources = sardine.list(baseURL + "level1/level2/");
		boolean foundLevel3 = resources.stream().anyMatch(r -> r.getName().equals("level3") && r.isDirectory());
		assertTrue(foundLevel3, "Level3 directory should be listed");
	}

	// ==================== Binary Content ====================

	@Test
	void testBinaryRoundTrip() throws Exception {
		Sardine sardine = SardineFactory.begin();

		byte[] binary = new byte[256];
		for (int i = 0; i < 256; i++) binary[i] = (byte) i;

		String file = baseURL + "binary.bin";
		sardine.put(file, binary);

		InputStream is = sardine.get(file);
		byte[] result = is.readAllBytes();
		is.close();

		assertArrayEquals(binary, result, "Binary content should round-trip exactly");
	}

	// ==================== Large Files ====================

	@Test
	void testLargeFile() throws Exception {
		// Use raw HTTP client to avoid Sardine/gzip issues with large binary
		var httpClient = java.net.http.HttpClient.newHttpClient();

		byte[] large = new byte[100 * 1024]; // 100KB
		new Random(42).nextBytes(large);

		String file = baseURL + "large.bin";

		// PUT
		var putReq = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(file))
				.PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(large))
				.build();
		httpClient.send(putReq, java.net.http.HttpResponse.BodyHandlers.ofString());

		// GET
		var getReq = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(file))
				.GET()
				.build();
		var getResp = httpClient.send(getReq, java.net.http.HttpResponse.BodyHandlers.ofByteArray());

		assertEquals(200, getResp.statusCode());
		assertArrayEquals(large, getResp.body(), "Large file should round-trip exactly");
	}

	// ==================== Empty File ====================

	@Test
	void testEmptyFile() throws Exception {
		Sardine sardine = SardineFactory.begin();

		String file = baseURL + "empty.txt";
		sardine.put(file, new byte[0]);

		InputStream is = sardine.get(file);
		byte[] result = is.readAllBytes();
		is.close();

		assertEquals(0, result.length, "Empty file should have zero bytes");
	}

	// ==================== Listing Properties ====================

	@Test
	void testContentLengthInListing() throws Exception {
		Sardine sardine = SardineFactory.begin();

		byte[] content = "known length".getBytes();
		String file = baseURL + "sized.txt";
		sardine.put(file, content);

		List<DavResource> resources = sardine.list(baseURL);
		DavResource sized = resources.stream()
				.filter(r -> r.getName().equals("sized.txt"))
				.findFirst()
				.orElse(null);
		assertNotNull(sized, "File should be in listing");
		assertEquals(content.length, sized.getContentLength().intValue(),
				"Content-Length should match");
	}

	@Test
	void testDirectoryResourceType() throws Exception {
		Sardine sardine = SardineFactory.begin();

		sardine.createDirectory(baseURL + "typedir/");

		List<DavResource> resources = sardine.list(baseURL + "typedir/");
		assertFalse(resources.isEmpty());
		assertTrue(resources.get(0).isDirectory(), "Directory should have collection resourcetype");
	}

	// ==================== Multiple Files in Directory ====================

	@Test
	void testMultipleFilesInDirectory() throws Exception {
		Sardine sardine = SardineFactory.begin();

		String dir = baseURL + "multi/";
		sardine.createDirectory(dir);
		sardine.put(dir + "a.txt", "aaa".getBytes());
		sardine.put(dir + "b.txt", "bbb".getBytes());
		sardine.put(dir + "c.txt", "ccc".getBytes());

		List<DavResource> resources = sardine.list(dir);
		// First entry is the directory itself, rest are children
		long fileCount = resources.stream().filter(r -> !r.isDirectory()).count();
		assertEquals(3, fileCount, "Should have 3 files");
	}

	// ==================== Overwrite Existing ====================

	@Test
	void testOverwritePreservesCorrectContent() throws Exception {
		Sardine sardine = SardineFactory.begin();

		String file = baseURL + "overwrite-test.txt";
		sardine.put(file, "short".getBytes());
		sardine.put(file, "this is a much longer string that replaces the short one".getBytes());

		InputStream is = sardine.get(file);
		String result = new String(is.readAllBytes());
		is.close();

		assertEquals("this is a much longer string that replaces the short one", result,
				"Overwritten content should be the new value, not truncated");
	}

	// ==================== Root Listing ====================

	@Test
	void testRootListing() throws Exception {
		Sardine sardine = SardineFactory.begin();
		List<DavResource> resources = sardine.list(baseURL);
		assertNotNull(resources);
		assertFalse(resources.isEmpty(), "Root should be listable");
		assertTrue(resources.get(0).isDirectory(), "Root should be a directory");
	}
}
