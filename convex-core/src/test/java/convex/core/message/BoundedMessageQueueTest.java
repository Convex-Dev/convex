package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.Blobs;

public class BoundedMessageQueueTest {

	@Test
	public void testCountAndEncodedByteBounds() {
		Message first=Message.create(MessageType.UNKNOWN,Blobs.createRandom(300));
		Message second=Message.create(MessageType.UNKNOWN,Blobs.createRandom(300));
		long oneSize=first.getMessageData().count();
		BoundedMessageQueue queue=new BoundedMessageQueue(2,oneSize*2-1);

		assertTrue(queue.offer(first));
		assertFalse(queue.offer(second));
		assertEquals(oneSize,queue.getQueuedBytes());
		assertSame(first,queue.poll());
		assertEquals(0,queue.getQueuedBytes());
		assertTrue(queue.offer(second));
		assertSame(second,queue.poll());
		assertNull(queue.poll());
	}

	@Test
	public void testOvershootAdmitsOneMessageOverTheByteBound() throws InterruptedException {
		Message m=Message.create(MessageType.UNKNOWN,Blobs.createRandom(300));
		long oneSize=m.getMessageData().count();
		BoundedMessageQueue queue=new BoundedMessageQueue(10,oneSize+oneSize/2,true);

		// Below the bound anything is admitted, even a message larger than the bound itself
		Message large=Message.create(MessageType.UNKNOWN,Blobs.createRandom(1000));
		assertTrue(queue.offer(large,1,TimeUnit.MILLISECONDS));
		assertFalse(queue.offer(m),"at or over the bound nothing more is admitted");
		assertTrue(queue.getFillFraction()>1.0,"a message larger than the bound overshoots it");
		assertSame(large,queue.poll());
		assertEquals(0.0,queue.getFillFraction());

		assertTrue(queue.offer(m));
		assertTrue(queue.offer(m),"the last message admitted may take the queue over the bound");
		assertFalse(queue.offer(m));
		assertTrue(queue.getFillFraction()>=1.0,"a queue that has just refused a message is full");

		// Raising the bounds admits more at once
		queue.setLimits(10,oneSize*10);
		assertTrue(queue.offer(m));
		assertEquals(3,queue.size());
		assertEquals(0.3,queue.getFillFraction(),1e-9);
	}

	/** Close is how an owner stops its consumer without interrupting it (#776). */
	@Test
	public void testCloseStopsAdmissionAndWaiting() throws Exception {
		Message first=Message.create(MessageType.UNKNOWN,Blobs.createRandom(300));
		Message second=Message.create(MessageType.UNKNOWN,Blobs.createRandom(300));
		BoundedMessageQueue queue=new BoundedMessageQueue(1,1_000_000);
		assertTrue(queue.offer(first));
		queue.close();

		assertFalse(queue.offer(second),"a closed queue refuses offers");
		assertTimeoutPreemptively(Duration.ofSeconds(10),() -> {
			assertFalse(queue.offer(second,1,TimeUnit.HOURS),"a closed queue refuses timed offers at once");
			assertSame(first,queue.poll(1,TimeUnit.HOURS),"queued messages still drain");
			assertNull(queue.poll(1,TimeUnit.HOURS),"an empty closed queue returns at once");
		});

		queue.open();
		assertTrue(queue.offer(second),"open admits offers again");
		assertSame(second,queue.poll());
	}
}
