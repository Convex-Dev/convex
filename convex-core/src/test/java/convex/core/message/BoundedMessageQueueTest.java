package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
