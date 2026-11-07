package convex.restapi.handler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.javalin.Javalin;

/**
 * Serves a file through {@link ContentResponse}, so ranges are read from a
 * channel other than a blob's. Requests run one at a time, so each one waits
 * for its own channel to close.
 */
@Execution(ExecutionMode.SAME_THREAD)
public class ContentResponseTest {

	private static final String ETAG = "\"content-tag\"";
	private static final String TYPE = "application/octet-stream";

	@TempDir
	static Path dir;

	private static byte[] content;
	private static Javalin app;
	private static HttpClient client;
	private static String url;

	/** Released each time the server closes a content channel */
	private static final Semaphore closed = new Semaphore(0);

	@BeforeAll
	static void setup() throws IOException {
		content = new byte[10_000];
		new Random(790).nextBytes(content);
		Path file = dir.resolve("content.bin");
		Files.write(file, content);

		app = Javalin.create(config -> {
			config.routes.get("/content", ctx -> ContentResponse.send(ctx, ETAG, TYPE, new ClosingChannel(Files.newByteChannel(file))));
			config.routes.head("/content", ctx -> ContentResponse.send(ctx, ETAG, TYPE, new ClosingChannel(Files.newByteChannel(file))));
		}).start(0);
		url = "http://localhost:" + app.port() + "/content";
		client = HttpClient.newHttpClient();
	}

	@AfterAll
	static void teardown() {
		app.stop();
		client.close();
	}

	@Test
	public void testWholeContent() throws Exception {
		HttpResponse<byte[]> full = get(null, null, null);
		assertEquals(200, full.statusCode());
		assertArrayEquals(content, full.body());
		assertEquals(ETAG, header(full, "ETag"));
		assertEquals(TYPE, header(full, "Content-Type"));
		assertEquals("bytes", header(full, "Accept-Ranges"));
		assertEquals(String.valueOf(content.length), header(full, "Content-Length"));

		HttpResponse<byte[]> gzip = send(HttpRequest.newBuilder(URI.create(url)).header("Accept-Encoding", "gzip").GET());
		assertEquals(null, header(gzip, "Content-Encoding"));
		assertArrayEquals(content, gzip.body());

		HttpResponse<byte[]> head = send(HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()));
		assertEquals(200, head.statusCode());
		assertEquals(0, head.body().length);
		assertEquals(String.valueOf(content.length), header(head, "Content-Length"));
		assertEquals(ETAG, header(head, "ETag"));

		// HEAD ignores Range, which is defined only for GET
		HttpResponse<byte[]> headRange = send(HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=0-9")
			.method("HEAD", HttpRequest.BodyPublishers.noBody()));
		assertEquals(200, headRange.statusCode());
		assertEquals(String.valueOf(content.length), header(headRange, "Content-Length"));
	}

	@Test
	public void testRanges() throws Exception {
		int n = content.length;
		assertPartial(get("bytes=4000-4199", null, null), 4000, 4199);
		assertPartial(get("bytes=9000-", null, null), 9000, n - 1);
		assertPartial(get("bytes=-100", null, null), n - 100, n - 1);
		assertPartial(get("bytes=0-0", ETAG, null), 0, 0);

		HttpResponse<byte[]> beyond = get("bytes=" + n + "-", null, null);
		assertEquals(416, beyond.statusCode());
		assertEquals("bytes */" + n, header(beyond, "Content-Range"));

		// Several ranges, a stale If-Range and a weak If-Range get the whole content
		for (String[] request : new String[][] { { "bytes=0-9,20-29", null }, { "bytes=0-9", "\"stale\"" }, { "bytes=0-9", "W/" + ETAG } }) {
			HttpResponse<byte[]> whole = get(request[0], request[1], null);
			assertEquals(200, whole.statusCode());
			assertArrayEquals(content, whole.body());
		}
	}

	@Test
	public void testNotModified() throws Exception {
		// If-None-Match compares weakly, so a tag weakened by an intermediary still matches
		for (String ifNoneMatch : new String[] { ETAG, "W/" + ETAG, "*", "\"other\", " + ETAG }) {
			HttpResponse<byte[]> resp = get(null, null, ifNoneMatch);
			assertEquals(304, resp.statusCode(), ifNoneMatch);
			assertEquals(ETAG, header(resp, "ETag"));
		}
		assertEquals(200, get(null, null, "\"other\"").statusCode());
	}

	private static HttpResponse<byte[]> get(String range, String ifRange, String ifNoneMatch) throws Exception {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).GET();
		if (range != null) req.header("Range", range);
		if (ifRange != null) req.header("If-Range", ifRange);
		if (ifNoneMatch != null) req.header("If-None-Match", ifNoneMatch);
		return send(req);
	}

	/** Sends a request, then waits for the server to close the channel it opened */
	private static HttpResponse<byte[]> send(HttpRequest.Builder req) throws Exception {
		HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
		assertTrue(closed.tryAcquire(10, TimeUnit.SECONDS), "content channel was not closed");
		return resp;
	}

	private static String header(HttpResponse<?> resp, String name) {
		return resp.headers().firstValue(name).orElse(null);
	}

	private static void assertPartial(HttpResponse<byte[]> resp, int first, int last) {
		assertEquals(206, resp.statusCode());
		assertEquals("bytes " + first + "-" + last + "/" + content.length, header(resp, "Content-Range"));
		assertEquals(String.valueOf(last - first + 1), header(resp, "Content-Length"));
		assertArrayEquals(Arrays.copyOfRange(content, first, last + 1), resp.body());
	}

	/** Channel that reports when it is closed */
	private static final class ClosingChannel implements SeekableByteChannel {
		private final SeekableByteChannel channel;

		private ClosingChannel(SeekableByteChannel channel) {
			this.channel = channel;
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			return channel.read(dst);
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			return channel.write(src);
		}

		@Override
		public long position() throws IOException {
			return channel.position();
		}

		@Override
		public SeekableByteChannel position(long newPosition) throws IOException {
			channel.position(newPosition);
			return this;
		}

		@Override
		public long size() throws IOException {
			return channel.size();
		}

		@Override
		public SeekableByteChannel truncate(long size) throws IOException {
			channel.truncate(size);
			return this;
		}

		@Override
		public boolean isOpen() {
			return channel.isOpen();
		}

		@Override
		public void close() throws IOException {
			channel.close();
			closed.release();
		}
	}
}
