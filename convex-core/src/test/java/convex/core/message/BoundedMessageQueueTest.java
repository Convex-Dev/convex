package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		assertTrue(queue.isOverHalfFull());
		assertSame(large,queue.poll());
		assertFalse(queue.isOverHalfFull());

		assertTrue(queue.offer(m));
		assertTrue(queue.offer(m),"the last message admitted may take the queue over the bound");
		assertFalse(queue.offer(m));
		assertTrue(queue.isOverHalfFull(),"a queue that has just refused a message is under pressure");

		// Raising the bounds admits more at once
		queue.setLimits(10,oneSize*10);
		assertTrue(queue.offer(m));
		assertEquals(3,queue.size());
		assertFalse(queue.isOverHalfFull());
	}
}
