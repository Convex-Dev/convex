package convex.restapi.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
	public void testConcurrentAcquireNeverExceedsLimit() throws InterruptedException {
		int perClient = 8;
		int threads = 64;
		ConcurrentLimit limit = new ConcurrentLimit(1000, perClient);

		AtomicInteger granted = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		List<Thread> workers = new ArrayList<>();

		for (int i = 0; i < threads; i++) {
			Thread t = new Thread(() -> {
				try {
					start.await();
					if (limit.acquireClient("busy")) granted.incrementAndGet();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
			workers.add(t);
			t.start();
		}

		start.countDown();
		done.await();
		for (Thread t : workers) t.join();

		assertEquals(perClient, granted.get(), "exactly the per-client allowance is granted");
		assertEquals(perClient, limit.clientCounts.get("busy")[0], "count matches what was granted");

		for (int i = 0; i < granted.get(); i++) limit.releaseClient("busy");
		assertTrue(limit.clientCounts.isEmpty(), "releases balance the grants exactly");
	}

	@Test
	public void testRejectsInvalidConfiguration() {
		assertThrows(IllegalArgumentException.class, () -> new ConcurrentLimit(0, 10));
		assertThrows(IllegalArgumentException.class, () -> new ConcurrentLimit(10, -1));
	}
}
