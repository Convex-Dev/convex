package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for #604: {@link Shutdown#addHook} raced on the shared backing
 * map, corrupting it (NPE from tree rebalancing) and losing updates under concurrent
 * registration (e.g. parallel server / node launches).
 *
 * <p>Deliberately does NOT call {@link Shutdown#shutdownNow()}: that would run and
 * clear every registered hook process-wide (each open Etch store registers one),
 * disturbing other tests. Instead we inspect the private map directly (same package)
 * and tidy up our own entries afterwards.</p>
 *
 * <p>Also covers {@link Shutdown#once}, the run-once task services register.</p>
 */
public class ShutdownTest {

	/** Priority base chosen well clear of the real hook priorities (60..120). */
	private static final int BASE = 900_000;

	@Test
	public void testRemoveHookByIdentity() throws Exception {
		int priority=BASE+2000;
		Runnable retained=()->{};
		Runnable equalOnly=()->{};
		Shutdown.addHook(priority,retained);
		try {
			Shutdown.removeHook(priority,equalOnly);
			assertEquals(1,hookCount(order().get(priority)));
			Shutdown.removeHook(priority,retained);
			assertEquals(0,hookCount(order().get(priority)));
		} finally {
			order().remove(priority);
		}
	}

	@Test
	public void testHookMayDeregisterItself() throws Exception {
		Shutdown.Group group=new Shutdown.Group(BASE+3000);
		AtomicInteger calls=new AtomicInteger();
		Runnable[] holder=new Runnable[1];
		holder[0]=()->{
			calls.incrementAndGet();
			group.removeHook(holder[0]);
		};
		group.addHook(holder[0]);
		group.runHooks();
		assertEquals(1,calls.get());
		assertEquals(0,hookCount(group));
	}

	@Test
	public void testConcurrentAddHookDistinctPriorities() throws Exception {
		// The #604 path: concurrent inserts of distinct keys corrupted the TreeMap and
		// could lose groups. Assert no exception and that every group survives.
		final int n = 64;
		final Runnable noop = () -> {};
		final CyclicBarrier barrier = new CyclicBarrier(n);
		final CountDownLatch done = new CountDownLatch(n);
		final AtomicReference<Throwable> failure = new AtomicReference<>();

		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			final int priority = BASE + i;
			Thread t = new Thread(() -> {
				try {
					barrier.await();               // release all threads together for max contention
					Shutdown.addHook(priority, noop);
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				} finally {
					done.countDown();
				}
			});
			threads.add(t);
			t.start();
		}
		assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not complete");
		for (Thread t : threads) t.join();
		assertNull(failure.get(), () -> "addHook threw under concurrency: " + failure.get());

		ConcurrentSkipListMap<Integer, Object> order = order();
		try {
			for (int i = 0; i < n; i++) {
				assertNotNull(order.get(BASE + i), "lost hook group for priority " + (BASE + i));
			}
		} finally {
			for (int i = 0; i < n; i++) order.remove(BASE + i); // don't leak test hooks to JVM shutdown
		}
	}

	@Test
	public void testConcurrentAddHookSamePriority() throws Exception {
		// Contend on a single priority: computeIfAbsent must yield one shared Group and
		// retain every (distinct-identity) hook.
		final int n = 64;
		final int priority = BASE + 1000;
		final CyclicBarrier barrier = new CyclicBarrier(n);
		final CountDownLatch done = new CountDownLatch(n);
		final AtomicReference<Throwable> failure = new AtomicReference<>();

		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Thread t = new Thread(() -> {
				// Anonymous instance => distinct identity, so the IdentityHashMap keeps all n
				Runnable r = new Runnable() { @Override public void run() {} };
				try {
					barrier.await();
					Shutdown.addHook(priority, r);
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				} finally {
					done.countDown();
				}
			});
			threads.add(t);
			t.start();
		}
		assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not complete");
		for (Thread t : threads) t.join();
		assertNull(failure.get(), () -> "addHook threw under concurrency: " + failure.get());

		ConcurrentSkipListMap<Integer, Object> order = order();
		Object group = order.get(priority);
		try {
			assertNotNull(group);
			assertEquals(n, hookCount(group), "lost hooks in shared group");
		} finally {
			order.remove(priority);
		}
	}

	/** Every caller of a service's shutdown, however concurrent, runs it at most once. */
	@Test
	public void testOnceRunsTheActionOnce() throws Exception {
		final int n = 16;
		AtomicInteger calls = new AtomicInteger();
		Shutdown.Once once = Shutdown.once(calls::incrementAndGet);
		CyclicBarrier barrier = new CyclicBarrier(n);
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Thread t = new Thread(() -> {
				try {
					barrier.await();
					once.run();
				} catch (Exception e) {
					throw new AssertionError(e);
				}
			});
			threads.add(t);
			t.start();
		}
		for (Thread t : threads) t.join();
		assertEquals(1, calls.get());
		once.await(); // returns at once: the action has run
	}

	/** The first caller sees a failed shutdown; it still counts as run for later callers. */
	@Test
	public void testOnceFailureReachesTheFirstCallerOnly() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		Shutdown.Once once = Shutdown.once(() -> {
			calls.incrementAndGet();
			throw new IllegalStateException("save failed");
		});
		assertThrows(IllegalStateException.class, once::run);
		once.run();
		once.await();
		assertEquals(1, calls.get());
	}

	/** A caller waiting for a shutdown in progress stops waiting when interrupted and keeps its interrupt. */
	@Test
	public void testOnceInterruptedWaiterKeepsItsInterrupt() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		Shutdown.Once once = Shutdown.once(() -> {
			calls.incrementAndGet();
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		Thread first = new Thread(once);
		first.start();
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS));
			Thread.currentThread().interrupt();
			once.run();
			assertTrue(Thread.interrupted(), "the waiter keeps its interrupt");
			assertEquals(1, calls.get(), "a waiting caller never runs the action itself");
		} finally {
			release.countDown();
			first.join();
		}
	}

	@SuppressWarnings("unchecked")
	private static ConcurrentSkipListMap<Integer, Object> order() throws Exception {
		Field f = Shutdown.class.getDeclaredField("order");
		f.setAccessible(true);
		return (ConcurrentSkipListMap<Integer, Object>) f.get(null);
	}

	private static int hookCount(Object group) throws Exception {
		Field f = group.getClass().getDeclaredField("hookSet");
		f.setAccessible(true);
		return ((Map<?, ?>) f.get(group)).size();
	}
}
