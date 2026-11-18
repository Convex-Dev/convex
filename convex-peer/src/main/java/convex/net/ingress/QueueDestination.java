package convex.net.ingress;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.message.Message;
import convex.core.util.Utils;

/**
 * A bounded queue of messages that one consumer thread drains, as a
 * {@link Destination}. Each dequeue that takes messages runs the actions waiting
 * for capacity.
 */
public final class QueueDestination implements Destination {

	private static final Logger log = LoggerFactory.getLogger(QueueDestination.class.getName());

	private final ArrayBlockingQueue<Message> queue;
	private final ConcurrentLinkedQueue<Runnable> waiters = new ConcurrentLinkedQueue<>();
	private final long waitMillis;
	private volatile Thread consumer;

	/**
	 * Creates a queue destination.
	 *
	 * @param capacity Messages the queue holds
	 * @param waitMillis Longest {@link #test(Message)} waits for room
	 */
	public QueueDestination(int capacity, long waitMillis) {
		this.queue = new ArrayBlockingQueue<>(capacity);
		this.waitMillis = waitMillis;
	}

	/**
	 * Sets the thread that drains this queue. {@link #test(Message)} does not wait on
	 * it, since nothing else would make room.
	 *
	 * @param thread Consumer thread
	 */
	public void setConsumer(Thread thread) {
		this.consumer = thread;
	}

	@Override
	public boolean tryOffer(Message message) {
		return queue.offer(message);
	}

	@Override
	public void whenCapacity(Runnable action) {
		waiters.add(action);
		// After adding: a dequeue since the caller's failed offer ran no waiter for it
		if (queue.remainingCapacity() > 0) signal();
	}

	@Override
	public boolean test(Message message) {
		if (Thread.currentThread() == consumer) return queue.offer(message);
		try {
			return queue.offer(message, waitMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Takes the next message, waiting up to a timeout for one.
	 *
	 * @param timeout Longest wait
	 * @param unit Unit of the timeout
	 * @return Message taken, or null if none arrived in time
	 * @throws InterruptedException if interrupted while waiting
	 */
	public Message poll(long timeout, TimeUnit unit) throws InterruptedException {
		Message m = queue.poll(timeout, unit);
		if (m != null) signal();
		return m;
	}

	/**
	 * Takes every message queued now.
	 *
	 * @param target Collection to add the messages to
	 * @return Number of messages taken
	 */
	public int drainTo(Collection<? super Message> target) {
		int n = queue.drainTo(target);
		if (n > 0) signal();
		return n;
	}

	/**
	 * Gets the number of messages queued.
	 *
	 * @return Messages queued
	 */
	public int size() {
		return queue.size();
	}

	private void signal() {
		Runnable action;
		while ((action = waiters.poll()) != null) {
			try {
				action.run();
			} catch (Throwable e) {
				// Caught here, the consumer that made room carries on
				Utils.checkRequestFailure(e);
				log.warn("Capacity action failed", e);
			}
		}
	}
}
