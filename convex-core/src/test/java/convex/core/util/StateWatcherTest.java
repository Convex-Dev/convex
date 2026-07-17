package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class StateWatcherTest {

	private static final Duration TIMEOUT=Duration.ofSeconds(1);

	@Test
	void noUpdateFastPath() {
		try (StateWatcher<Integer> watcher=new StateWatcher<>(v-> {})) {
			assertFalse(watcher.isDispatcherRunning());
		}
	}

	@Test
	void consumerReceivesUpdateAsynchronously() throws Exception {
		CountDownLatch seen=new CountDownLatch(1);
		AtomicReference<Thread> callbackThread=new AtomicReference<>();
		AtomicInteger value=new AtomicInteger();

		try (StateWatcher<Integer> watcher=new StateWatcher<>(v-> {
			callbackThread.set(Thread.currentThread());
			value.set(v);
			seen.countDown();
		})) {
			watcher.update(2);
			assertTrue(await(seen));
			assertNotEquals(Thread.currentThread(),callbackThread.get());
			assertEquals(2,value.get());
		}
	}

	@Test
	void consumerEventuallyReceivesLatestValue() throws Exception {
		CountDownLatch initialStarted=new CountDownLatch(1);
		CountDownLatch releaseInitial=new CountDownLatch(1);
		CountDownLatch latestSeen=new CountDownLatch(1);
		AtomicInteger latest=new AtomicInteger();

		try (StateWatcher<Integer> watcher=new StateWatcher<>(v-> {
			if (v==0) {
				initialStarted.countDown();
				awaitUninterruptibly(releaseInitial);
			}
			latest.set(v);
			if (v==3) latestSeen.countDown();
		})) {
			watcher.update(0);
			assertTrue(await(initialStarted));
			org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(TIMEOUT,()-> {
				watcher.update(1);
				watcher.update(2);
				watcher.update(3);
			});
			releaseInitial.countDown();

			assertTrue(await(latestSeen));
			assertEquals(3,latest.get());
		}
	}

	@Test
	void closeStopsDelivery() throws Exception {
		CountDownLatch seen=new CountDownLatch(1);
		StateWatcher<Integer> watcher=new StateWatcher<>(v->seen.countDown());
		watcher.update(1);
		assertTrue(await(seen));

		watcher.close();
		watcher.close();
		assertTrue(watcher.isClosed());
		assertThrows(IllegalStateException.class,()->watcher.update(2));
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(TIMEOUT,()-> {
			while (watcher.isDispatcherRunning()) Thread.yield();
		});
	}

	@Test
	void updateAndWaitBlocksUntilConsumerRoundCompletes() throws Exception {
		CountDownLatch consumerStarted=new CountDownLatch(1);
		CountDownLatch releaseConsumer=new CountDownLatch(1);
		AtomicBoolean returned=new AtomicBoolean();
		AtomicReference<Throwable> failure=new AtomicReference<>();

		try (StateWatcher<Integer> watcher=new StateWatcher<>(v-> {
			consumerStarted.countDown();
			awaitUninterruptibly(releaseConsumer);
		})) {
			Thread caller=Thread.ofVirtual().start(()-> {
				try {
					watcher.updateAndWait(1);
					returned.set(true);
				} catch (Throwable t) {
					failure.set(t);
				}
			});

			assertTrue(await(consumerStarted));
			assertFalse(returned.get());
			releaseConsumer.countDown();
			caller.join(TIMEOUT.toMillis());

			assertFalse(caller.isAlive());
			assertNull(failure.get());
			assertTrue(returned.get());
		}
	}

	@Test
	void closeReleasesUpdateAndWait() throws Exception {
		CountDownLatch consumerStarted=new CountDownLatch(1);
		CountDownLatch releaseConsumer=new CountDownLatch(1);
		AtomicReference<Throwable> failure=new AtomicReference<>();
		StateWatcher<Integer> watcher=new StateWatcher<>(v-> {
			consumerStarted.countDown();
			awaitUninterruptibly(releaseConsumer);
		});
		Thread caller=Thread.ofVirtual().start(()-> {
			try {
				watcher.updateAndWait(1);
			} catch (Throwable t) {
				failure.set(t);
			}
		});

		try {
			assertTrue(await(consumerStarted));
			watcher.close();
			caller.join(TIMEOUT.toMillis());
			assertFalse(caller.isAlive());
			assertTrue(failure.get() instanceof IllegalStateException);
		} finally {
			releaseConsumer.countDown();
			watcher.close();
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted=false;
		while (true) {
			try {
				latch.await();
				break;
			} catch (InterruptedException e) {
				interrupted=true;
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private static boolean await(CountDownLatch latch) throws InterruptedException {
		return latch.await(TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
	}
}
