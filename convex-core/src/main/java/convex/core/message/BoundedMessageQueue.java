package convex.core.message;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * FIFO message queue bounded by both message count and encoded body bytes.
 * Count-only bounds are insufficient when one entry may occupy several MiB.
 *
 * <p>The byte bound is strict by default: a message that would take the queue over
 * it is refused, and so is any message larger than the bound itself. A queue created
 * with {@code allowOvershoot} instead admits any message while it holds fewer bytes
 * than the bound, so the bound is exceeded by at most the last message admitted.
 * That suits an outbound queue, where refusing one large message leaves the receiver
 * with a gap, while the queue must still stop growing once it is full.</p>
 */
public final class BoundedMessageQueue {
	private final ArrayDeque<Message> queue=new ArrayDeque<>();
	private final boolean allowOvershoot;
	private int messageLimit;
	private long byteLimit;
	private long queuedBytes;

	public BoundedMessageQueue(int messageLimit, long byteLimit) {
		this(messageLimit,byteLimit,false);
	}

	public BoundedMessageQueue(int messageLimit, long byteLimit, boolean allowOvershoot) {
		checkLimits(messageLimit,byteLimit);
		this.messageLimit=messageLimit;
		this.byteLimit=byteLimit;
		this.allowOvershoot=allowOvershoot;
	}

	private static void checkLimits(int messageLimit, long byteLimit) {
		if (messageLimit<1) throw new IllegalArgumentException("Message limit must be positive");
		if (byteLimit<1) throw new IllegalArgumentException("Byte limit must be positive");
	}

	/**
	 * Replaces both bounds, for example once the remote end is known to be a trusted
	 * peer. Raising them wakes any producer waiting for space.
	 *
	 * @param messageLimit New maximum number of queued messages
	 * @param byteLimit New maximum queued encoded bytes
	 */
	public synchronized void setLimits(int messageLimit, long byteLimit) {
		checkLimits(messageLimit,byteLimit);
		this.messageLimit=messageLimit;
		this.byteLimit=byteLimit;
		notifyAll();
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
		if (!allowOvershoot && bytes>byteLimit) return false;
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

	public synchronized int getMessageLimit() {
		return messageLimit;
	}

	public synchronized long getByteLimit() {
		return byteLimit;
	}

	/**
	 * Gets how full the queue is as a fraction of its bounds: the larger of the
	 * message count ratio and the byte ratio. It is 0.0 when empty, at least 1.0
	 * whenever an offer would be refused, and above 1.0 when the last message
	 * admitted took the queue over its byte bound. Callers apply their own policy
	 * to it, such as treating anything above one half as pressure.
	 *
	 * @return Fill fraction of the fuller bound
	 */
	public synchronized double getFillFraction() {
		double byCount=(double)queue.size()/messageLimit;
		double byBytes=(double)queuedBytes/byteLimit;
		return Math.max(byCount,byBytes);
	}

	/** Wakes timed producers, for example when the owning service stops admission. */
	public synchronized void signalAll() {
		notifyAll();
	}

	private boolean canOffer(long bytes) {
		if (queue.size()>=messageLimit) return false;
		if (allowOvershoot) return queuedBytes<byteLimit;
		return bytes<=byteLimit && queuedBytes+bytes<=byteLimit;
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
