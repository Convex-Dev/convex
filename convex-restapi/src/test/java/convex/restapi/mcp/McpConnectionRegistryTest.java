package convex.restapi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
	public void testConcurrentOpensNeverExceedCap() throws InterruptedException {
		ConcurrentHashMap<String, McpConnection> conns = new ConcurrentHashMap<>();
		int cap = 50, threads = 200;
		AtomicInteger ok = new AtomicInteger();
		Thread[] ts = new Thread[threads];
		for (int i = 0; i < threads; i++) {
			String id = "c" + i; // distinct session IDs, so only the cap can reject
			ts[i] = new Thread(() -> {
				if (McpAPI.registerConnection(conns, id, conn(), cap) == McpAPI.RegisterResult.OK) {
					ok.incrementAndGet();
				}
			});
		}
		for (Thread t : ts) t.start();
		for (Thread t : ts) t.join();
		// The security invariant: no interleaving can admit more than the cap
		assertTrue(conns.size() <= cap, "connections exceeded cap: " + conns.size());
		// Every retained connection is exactly an OK result — no orphans, no double-count
		assertEquals(conns.size(), ok.get(), "retained connections must equal OK registrations");
	}
}
