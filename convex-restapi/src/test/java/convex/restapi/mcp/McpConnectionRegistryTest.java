package convex.restapi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link McpAPI#registerConnection} — atomic SSE connection registration
 * and cap enforcement (#659). Pure and deterministic: no server, no HTTP.
 */
public class McpConnectionRegistryTest {

	private static McpConnection conn() {
		// Construction does not start any thread (the dispatcher is lazy), so this is cheap.
		return new McpConnection(new PrintWriter(Writer.nullWriter()));
	}

	@Test
	public void testRegistersUnderCap() {
		ConcurrentHashMap<String, McpConnection> conns = new ConcurrentHashMap<>();
		assertEquals(McpAPI.RegisterResult.OK, McpAPI.registerConnection(conns, "a", conn(), 2));
		assertEquals(McpAPI.RegisterResult.OK, McpAPI.registerConnection(conns, "b", conn(), 2));
		assertEquals(2, conns.size());
	}

	@Test
	public void testCapRejectsAndDoesNotRetain() {
		ConcurrentHashMap<String, McpConnection> conns = new ConcurrentHashMap<>();
		McpAPI.registerConnection(conns, "a", conn(), 1);
		McpConnection over = conn();
		assertEquals(McpAPI.RegisterResult.CAP_EXCEEDED, McpAPI.registerConnection(conns, "b", over, 1));
		assertEquals(1, conns.size());
		assertFalse(conns.containsValue(over), "a rejected connection must not be left in the registry");
	}

	@Test
	public void testSessionReuseDoesNotReplace() {
		ConcurrentHashMap<String, McpConnection> conns = new ConcurrentHashMap<>();
		McpConnection first = conn();
		assertEquals(McpAPI.RegisterResult.OK, McpAPI.registerConnection(conns, "s", first, 10));
		McpConnection second = conn();
		assertEquals(McpAPI.RegisterResult.SESSION_IN_USE, McpAPI.registerConnection(conns, "s", second, 10));
		// The original connection is untouched, not replaced/orphaned (#659)
		assertSame(first, conns.get("s"));
		assertEquals(1, conns.size());
	}

	@Test
	@Timeout(15)
	public void testConcurrentOpensNeverExceedCap() throws Exception {
		ConcurrentHashMap<String, McpConnection> conns = new ConcurrentHashMap<>();
		int cap = 50, threads = 200;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<McpAPI.RegisterResult>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String id = "c" + i; // distinct session IDs, so only the cap can reject
				results.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return McpAPI.registerConnection(conns, id, conn(), cap);
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not reach the start gate");
			start.countDown();
			int ok = 0, rejected = 0;
			for (var result : results) {
				// get() brings worker exceptions and assertion failures back to JUnit.
				var outcome = result.get(5, TimeUnit.SECONDS);
				if (outcome == McpAPI.RegisterResult.OK) ok++;
				else {
					assertEquals(McpAPI.RegisterResult.CAP_EXCEEDED, outcome);
					rejected++;
				}
			}
			assertEquals(cap, ok, "registrations must fill the available capacity");
			assertEquals(threads - cap, rejected);
			assertEquals(ok, conns.size(), "retained connections must equal OK registrations");
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "registration workers did not stop");
		}
	}

	/** Shutdown ends every stream, so a graceful server stop need not wait for them (#777). */
	@Test
	public void testCloseConnectionsEndsEveryStream() {
		ConcurrentHashMap<String, McpConnection> conns = new ConcurrentHashMap<>();
		McpConnection first = conn();
		McpConnection second = conn();
		McpAPI.registerConnection(conns, "a", first, 10);
		McpAPI.registerConnection(conns, "b", second, 10);

		McpAPI.closeConnections(conns);

		assertTrue(first.isClosed());
		assertTrue(second.isClosed());
	}
}
