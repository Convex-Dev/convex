package convex.net.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.message.AConnection;
import convex.core.message.Message;

public class QueueDestinationTest {

	private static Message message() {
		return Message.create((AConnection) null, Blob.wrap(new byte[] { 1, 2, 3 }));
	}

	@Test
	public void testCapacityActionRunsOnDequeueOnly() throws InterruptedException {
		QueueDestination d = new QueueDestination(1, 0);
		assertTrue(d.tryOffer(message()));
		assertFalse(d.tryOffer(message()));

		AtomicInteger runs = new AtomicInteger();
		d.whenCapacity(runs::incrementAndGet);
		assertEquals(0, runs.get(), "the queue is full: the action waits");

		assertNotNull(d.poll(0, TimeUnit.MILLISECONDS));
		assertEquals(1, runs.get());
		assertTrue(d.tryOffer(message()));
		d.drainTo(new ArrayList<>());
		assertEquals(1, runs.get(), "an action runs once");
	}

	@Test
	public void testCapacityActionRunsAtOnceWithRoom() {
		QueueDestination d = new QueueDestination(1, 0);
		AtomicInteger runs = new AtomicInteger();
		d.whenCapacity(runs::incrementAndGet);
		assertEquals(1, runs.get());
	}

	@Test
	public void testEmptyDequeueRunsNothing() throws InterruptedException {
		QueueDestination d = new QueueDestination(1, 0);
		assertTrue(d.tryOffer(message()));
		AtomicInteger runs = new AtomicInteger();
		d.whenCapacity(runs::incrementAndGet);
		d.drainTo(new ArrayList<>());
		assertEquals(1, runs.get());
		d.whenCapacity(runs::incrementAndGet); // room now: runs at once
		assertEquals(2, runs.get());
		d.drainTo(new ArrayList<>());
		assertEquals(0, d.size());
		assertEquals(2, runs.get(), "a dequeue that takes nothing runs no action");
	}

	@Test
	public void testConsumerNeverWaits() {
		QueueDestination d = new QueueDestination(1, 60_000);
		d.setConsumer(Thread.currentThread());
		assertTrue(d.test(message()));
		assertFalse(d.test(message()), "on its consumer thread a full queue refuses at once");
	}
}
