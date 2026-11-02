package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class StateWatcherTest {

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
			seen.await();
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
			initialStarted.await();
			// The consumer is held until after these updates, so an update that
			// waited for it would deadlock here
			watcher.update(1);
			watcher.update(2);
			watcher.update(3);
			releaseInitial.countDown();

			latestSeen.await();
			assertEquals(3,latest.get());
		}
	}

	@Test
	void closeStopsDelivery() throws Exception {
		// The consumer runs on the dispatcher thread, so it can report which thread that is
		CompletableFuture<Thread> dispatcher=new CompletableFuture<>();
		StateWatcher<Integer> watcher=new StateWatcher<>(v->dispatcher.complete(Thread.currentThread()));
		watcher.update(1);
		Thread thread=dispatcher.get();

		watcher.close();
		watcher.close();
		assertTrue(watcher.isClosed());
		assertThrows(IllegalStateException.class,()->watcher.update(2));
		thread.join();
		assertFalse(watcher.isDispatcherRunning());
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

			consumerStarted.await();
			assertFalse(returned.get());
			releaseConsumer.countDown();
			caller.join();

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
			consumerStarted.await();
			watcher.close();
			caller.join();
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
}
