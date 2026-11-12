package convex.restapi.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests the per-client admission accounting.
 *
 * <p>These exercise {@link ConcurrentLimit#acquireClient} and
 * {@link ConcurrentLimit#releaseClient} directly rather than through a handler, since
 * the accounting is where this can go subtly wrong: an acquire that reports success
 * without incrementing, or a release that decrements a count it never took, both leave
 * a client permanently over- or under-credited rather than failing visibly.</p>
 */
public class ConcurrentLimitTest {

	@Test
	public void testPerClientLimitIsEnforced() {
		ConcurrentLimit limit = new ConcurrentLimit(100, 2);

		assertTrue(limit.acquireClient("a"));
		assertTrue(limit.acquireClient("a"));
		assertFalse(limit.acquireClient("a"), "third concurrent request from one client is refused");

		// A refused acquire must not have incremented: after releasing the two we did
		// take, the client is fully clear rather than stuck one permit short
		limit.releaseClient("a");
		limit.releaseClient("a");
		assertTrue(limit.clientCounts.isEmpty(), "entry removed once the client goes idle");
		assertTrue(limit.acquireClient("a"), "client is clear again");
		limit.releaseClient("a");
	}

	@Test
	public void testClientsAreIndependent() {
		ConcurrentLimit limit = new ConcurrentLimit(100, 1);

		assertTrue(limit.acquireClient("a"));
		assertFalse(limit.acquireClient("a"));
		assertTrue(limit.acquireClient("b"), "one client at its limit does not block another");

		limit.releaseClient("a");
		limit.releaseClient("b");
		assertTrue(limit.clientCounts.isEmpty());
	}

	@Test
	public void testNoPerClientLimitAdmitsAll() {
		ConcurrentLimit limit = new ConcurrentLimit(100, 0);
		for (int i = 0; i < 50; i++) {
			assertTrue(limit.acquireClient("a"));
		}
		assertTrue(limit.clientCounts.isEmpty(), "no accounting kept when the per-client bound is off");
	}

	@Test
	public void testUnidentifiedClientIsAdmitted() {
		ConcurrentLimit limit = new ConcurrentLimit(100, 1);
		// A request whose remote address could not be determined must not be refused;
		// the server-wide cap is what still bounds it
		assertTrue(limit.acquireClient(null));
		assertTrue(limit.acquireClient(null));
		limit.releaseClient(null);
	}

	/**
	 * The limit must hold when many threads race on one client, which is exactly the
	 * case a non-atomic check-then-increment gets wrong.
	 */
	@Test
	@Timeout(15)
	public void testConcurrentAcquireNeverExceedsLimit() throws Exception {
		int perClient = 8;
		int threads = 64;
		ConcurrentLimit limit = new ConcurrentLimit(1000, perClient);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return limit.acquireClient("busy");
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not reach the start gate");
			start.countDown();
			int granted = 0;
			for (var result : results) {
				if (result.get(5, TimeUnit.SECONDS)) granted++;
			}
			assertEquals(perClient, granted, "exactly the per-client allowance is granted");
			assertEquals(perClient, limit.clientCounts.get("busy")[0], "count matches what was granted");
			for (int i = 0; i < granted; i++) limit.releaseClient("busy");
			assertTrue(limit.clientCounts.isEmpty(), "releases balance the grants exactly");
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "admission workers did not stop");
		}
	}

	@Test
	public void testRejectsInvalidConfiguration() {
		assertThrows(IllegalArgumentException.class, () -> new ConcurrentLimit(0, 10));
		assertThrows(IllegalArgumentException.class, () -> new ConcurrentLimit(10, -1));
	}
}
