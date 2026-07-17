package convex.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

		try {
			assertTrue(started.await(1,TimeUnit.SECONDS));
			awaitWaiting(consumer);
			synchronized (queue) {
				queue.notifyAll();
			}
			awaitWaiting(consumer);
			assertNull(result.get());
		} finally {
			queue.offer(13);
			consumer.join(Duration.ofSeconds(1));
		}

		assertFalse(consumer.isAlive());
		assertEquals(13,result.get());
	}

	@Test
	void timedPollSupportsSubMillisecondTimeouts() {
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>();

		assertTimeoutPreemptively(Duration.ofSeconds(1),()->
			assertNull(queue.poll(1,TimeUnit.NANOSECONDS)));
	}

	@Test
	void timedPollReceivesOfferedValue() throws Exception {
		LatestUpdateQueue<Integer> queue=new LatestUpdateQueue<>();

		Thread producer=Thread.ofPlatform().start(()->queue.offer(42));
		assertEquals(42,queue.poll(1,TimeUnit.SECONDS));
		producer.join(Duration.ofSeconds(1));
		assertFalse(producer.isAlive());
	}

	private static void awaitWaiting(Thread thread) throws InterruptedException {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
		while (thread.isAlive()&&(thread.getState()!=Thread.State.WAITING)&&(System.nanoTime()<deadline)) {
			Thread.sleep(1);
		}
		assertTrue(thread.isAlive(),"Consumer returned without an offered value");
		assertEquals(Thread.State.WAITING,thread.getState());
	}
}
