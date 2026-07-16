package convex.core.util;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Asynchronously passes the latest supplied value to a consumer.
 *
 * <p>The delivery contract is level-triggered: while this distributor remains
 * open, once updates quiesce the consumer is eventually called at least once with
 * the latest supplied value, provided earlier consumer calls return. Intermediate
 * values may be omitted, and duplicate calls are permitted. No stronger delivery
 * count or event ordering is implied. The consumer is called on a dedicated
 * virtual thread, never on the thread calling {@link #update(Object)}.</p>
 *
 * <p>This class deliberately defines no subscription or watcher semantics;
 * callers may implement fan-out, filtering and lifecycle policy in the
 * consumer.</p>
 *
 * <p>The distributor thread starts lazily on the first update. At most one update
 * is retained regardless of producer speed.</p>
 *
 * @param <V> Type of distributed value
 */
public final class StateWatcher<V> implements AutoCloseable {

	private static final System.Logger log=System.getLogger(StateWatcher.class.getName());
	private static final AtomicLong THREAD_COUNTER=new AtomicLong();

	private final LatestUpdateQueue<V> updates=new LatestUpdateQueue<>();
	private final Consumer<? super V> consumer;

	private volatile boolean closed;
	private volatile Thread dispatcher;

	/**
	 * Creates a distributor for a consumer.
	 *
	 * @param consumer Consumer to call asynchronously for latest values
	 */
	public StateWatcher(Consumer<? super V> consumer) {
		this.consumer=Objects.requireNonNull(consumer,"Consumer cannot be null");
	}

	/**
	 * Supplies a value for asynchronous latest-state delivery. This method never
	 * invokes the consumer and does not wait for delivery.
	 *
	 * @param value New value, must not be {@code null}
	 * @throws IllegalStateException if this distributor is closed
	 */
	public void update(V value) {
		Objects.requireNonNull(value,"StateWatcher value cannot be null");
		if (closed) throw new IllegalStateException("StateWatcher is closed");
		ensureDispatcher();
		updates.offer(value);
	}

	private void ensureDispatcher() {
		if (dispatcher!=null) return;
		synchronized (this) {
			if (closed) throw new IllegalStateException("StateWatcher is closed");
			if (dispatcher!=null) return;
			dispatcher=Thread.ofVirtual()
				.name("state-watcher-"+THREAD_COUNTER.incrementAndGet())
				.start(this::dispatchLoop);
		}
	}

	private void dispatchLoop() {
		Thread thread=Thread.currentThread();
		try {
			while (!closed) {
				V value;
				try {
					value=updates.take();
				} catch (InterruptedException e) {
					if (closed) break;
					continue;
				}
				if (closed) break;
				try {
					consumer.accept(value);
				} catch (Exception e) {
					log.log(System.Logger.Level.DEBUG,"StateWatcher consumer failed",e);
				}
			}
		} finally {
			synchronized (this) {
				if (dispatcher==thread) dispatcher=null;
			}
		}
	}

	/**
	 * Tests whether this distributor has been closed.
	 *
	 * @return {@code true} if closed
	 */
	public boolean isClosed() {
		return closed;
	}

	/**
	 * Closes this distributor, discards any pending update and interrupts its
	 * dispatcher. A consumer invocation already in progress may finish. This
	 * operation is idempotent.
	 */
	@Override
	public void close() {
		Thread thread;
		synchronized (this) {
			if (closed) return;
			closed=true;
			updates.clear();
			thread=dispatcher;
		}
		if (thread!=null) thread.interrupt();
	}

	boolean isDispatcherRunning() {
		Thread thread=dispatcher;
		return (thread!=null)&&thread.isAlive();
	}
}
