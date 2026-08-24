package convex.core.message;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * FIFO message queue bounded by both message count and encoded body bytes.
 * Count-only bounds are insufficient when one entry may occupy several MiB.
 */
public final class BoundedMessageQueue {
	private final ArrayDeque<Message> queue=new ArrayDeque<>();
	private final int messageLimit;
	private final long byteLimit;
	private long queuedBytes;

	public BoundedMessageQueue(int messageLimit, long byteLimit) {
		if (messageLimit<1) throw new IllegalArgumentException("Message limit must be positive");
		if (byteLimit<1) throw new IllegalArgumentException("Byte limit must be positive");
		this.messageLimit=messageLimit;
		this.byteLimit=byteLimit;
	}

	public synchronized boolean offer(Message message) {
		long bytes=messageBytes(message);
		if (!canOffer(bytes)) return false;
		queue.addLast(message);
		queuedBytes+=bytes;
		notifyAll();
		return true;
	}

	public synchronized boolean offer(Message message, long timeout, TimeUnit unit)
			throws InterruptedException {
		long bytes=messageBytes(message);
		if (bytes>byteLimit) return false;
		long remaining=unit.toNanos(timeout);
		long deadline=System.nanoTime()+remaining;
		while (!canOffer(bytes)) {
			if (remaining<=0) return false;
			TimeUnit.NANOSECONDS.timedWait(this,remaining);
			remaining=deadline-System.nanoTime();
		}
		queue.addLast(message);
		queuedBytes+=bytes;
		notifyAll();
		return true;
	}

	public synchronized Message poll() {
		return removeFirst();
	}

	public synchronized Message poll(long timeout, TimeUnit unit) throws InterruptedException {
		long remaining=unit.toNanos(timeout);
		long deadline=System.nanoTime()+remaining;
		while (queue.isEmpty()) {
			if (remaining<=0) return null;
			TimeUnit.NANOSECONDS.timedWait(this,remaining);
			remaining=deadline-System.nanoTime();
		}
		return removeFirst();
	}

	public synchronized int drainTo(Collection<? super Message> target) {
		int count=0;
		Message message;
		while ((message=removeFirst())!=null) {
			target.add(message);
			count++;
		}
		return count;
	}

	public synchronized boolean remove(Message message) {
		if (!queue.removeFirstOccurrence(message)) return false;
		queuedBytes-=messageBytes(message);
		notifyAll();
		return true;
	}

	public synchronized void clear() {
		queue.clear();
		queuedBytes=0;
		notifyAll();
	}

	public synchronized boolean isEmpty() {
		return queue.isEmpty();
	}

	public synchronized int size() {
		return queue.size();
	}

	public synchronized long getQueuedBytes() {
		return queuedBytes;
	}

	/** Wakes timed producers, for example when the owning service stops admission. */
	public synchronized void signalAll() {
		notifyAll();
	}

	private boolean canOffer(long bytes) {
		return bytes<=byteLimit && queue.size()<messageLimit && queuedBytes+bytes<=byteLimit;
	}

	private Message removeFirst() {
		Message message=queue.pollFirst();
		if (message!=null) {
			queuedBytes-=messageBytes(message);
			notifyAll();
		}
		return message;
	}

	private static long messageBytes(Message message) {
		if (message==null) throw new NullPointerException("Message");
		return message.getMessageData().count();
	}
}
