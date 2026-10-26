package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class LatestUpdateQueueTest {

	@Test
	void latestOfferWins() {
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>();

		queue.offer(1);
		queue.offer(2);

		assertEquals(2,queue.poll());
		assertNull(queue.poll());
	}

	@Test
	void takeWaitsForOffer() throws Exception {
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>();
		CountDownLatch started=new CountDownLatch(1);
		AtomicReference<Integer> result=new AtomicReference<>();

		Thread consumer=Thread.ofPlatform().start(()-> {
			started.countDown();
			try {
				result.set(queue.take());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		assertTrue(started.await(1,TimeUnit.SECONDS));
		queue.offer(13);
		consumer.join(Duration.ofSeconds(1));

		assertFalse(consumer.isAlive());
		assertEquals(13,result.get());
	}

	@Test
	void takeIgnoresSpuriousNotification() throws Exception {
		CountDownLatch firstWait=new CountDownLatch(1);
		CountDownLatch notifiedWait=new CountDownLatch(1);
		AtomicBoolean notified=new AtomicBoolean();
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>() {
			@Override
			void awaitUpdate() throws InterruptedException {
				if (notified.get()) notifiedWait.countDown();
				else firstWait.countDown();
				super.awaitUpdate();
			}
		};
		try (var executor=Executors.newSingleThreadExecutor()) {
			var result=executor.submit(queue::take);
			try {
				assertTrue(firstWait.await(10,TimeUnit.SECONDS));
				synchronized (queue) {
					notified.set(true);
					queue.notifyAll();
				}
				assertTrue(notifiedWait.await(10,TimeUnit.SECONDS));
				assertFalse(result.isDone(),"A notification without an item must not complete take");
				queue.offer(13);
				assertEquals(13,result.get(10,TimeUnit.SECONDS));
			} finally {
				result.cancel(true);
			}
		}
	}

	@Test
	void timedPollSupportsSubMillisecondTimeouts() {
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>();

		assertTimeoutPreemptively(Duration.ofSeconds(1),()->
			assertNull(queue.poll(1,TimeUnit.NANOSECONDS)));
	}

	/** Close is how an owner stops its consumer without interrupting it (#776). */
	@Test
	void closeWakesWaitingTimedPoll() throws Exception {
		CountDownLatch waiting=new CountDownLatch(1);
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>() {
			@Override
			void awaitUpdate(long nanos) throws InterruptedException {
				waiting.countDown();
				super.awaitUpdate(nanos);
			}
		};
		try (var executor=Executors.newSingleThreadExecutor()) {
			var result=executor.submit(() -> queue.poll(1,TimeUnit.HOURS));
			try {
				assertTrue(waiting.await(10,TimeUnit.SECONDS));
				queue.close();
				assertNull(result.get(10,TimeUnit.SECONDS),"close must end a waiting timed poll");
			} finally {
				result.cancel(true);
			}
		}
	}

	@Test
	void closedQueueHandsOverFinalItemThenStopsWaiting() throws Exception {
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>();
		queue.close();
		assertTrue(queue.offer(7),"offers are still accepted after close");
		assertTimeoutPreemptively(Duration.ofSeconds(10),() -> {
			assertEquals(7,queue.poll(1,TimeUnit.HOURS));
			assertNull(queue.poll(1,TimeUnit.HOURS));
		});

		queue.open();
		assertNull(queue.poll(1,TimeUnit.MILLISECONDS),"an open queue waits for its timeout");
	}

	@Test
	void timedPollReceivesOfferedValue() throws Exception {
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>();

		Thread producer=Thread.ofPlatform().start(()->queue.offer(42));
		assertEquals(42,queue.poll(1,TimeUnit.SECONDS));
		producer.join(Duration.ofSeconds(1));
		assertFalse(producer.isAlive());
	}

}
