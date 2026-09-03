package convex.net.impl.netty;

import java.util.LinkedHashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.message.Message;
import io.netty.channel.Channel;

/**
 * Server-wide outbound queue for replies to inbound connections.
 *
 * <p>Replies are queued in FIFO order and handed to Netty by a single writer
 * thread, which flushes each touched channel once per batch. The queue is a
 * burst absorber bounded by the total encoded bytes it holds: bytes are
 * released as soon as a message is handed to Netty, so a slow reader can never
 * pin the shared bound or delay other connections. What a connection has
 * handed to Netty but not yet written is bounded separately per connection;
 * once over that cap its further replies are refused, and only its own.</p>
 */
final class ServerOutboundQueue {

	static final Logger log = LoggerFactory.getLogger(ServerOutboundQueue.class);

	/** Maximum messages handed to Netty between flushes. */
	private static final int MAX_BATCH = 256;

	private record Entry(NettyServerConnection connection, Channel channel, Message message, int bytes) {}

	private final long byteLimit;
	private final long connectionByteLimit;
	private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
	private final AtomicLong queuedBytes = new AtomicLong();
	private volatile boolean running = true;
	private final Thread writer;

	/**
	 * @param byteLimit Maximum encoded bytes held in the queue across all connections
	 * @param connectionByteLimit Bytes handed to Netty but unwritten beyond which a connection's replies are refused
	 */
	ServerOutboundQueue(long byteLimit, long connectionByteLimit) {
		this(byteLimit, connectionByteLimit, true);
	}

	/** Test hook: a queue without a writer thread is drained explicitly via {@link #drainBatch()}. */
	ServerOutboundQueue(long byteLimit, long connectionByteLimit, boolean startWriter) {
		if (byteLimit < 1 || connectionByteLimit < 1) throw new IllegalArgumentException("Byte limits must be positive");
		this.byteLimit = byteLimit;
		this.connectionByteLimit = connectionByteLimit;
		this.writer = startWriter ? Thread.ofVirtual().name("Server outbound writer").start(this::drain) : null;
	}

	/**
	 * Queues a message for a connection. Never blocks.
	 *
	 * @return true if queued; false if the queue is closed, the shared bound is full, or the
	 *         connection already has more than its cap handed to Netty and unwritten
	 */
	boolean offer(NettyServerConnection connection, Channel channel, Message message, int bytes) {
		if (!running || bytes < 0) return false;
		if (connection.getPendingBytes() >= connectionByteLimit) return false;
		long current;
		do {
			current = queuedBytes.get();
			if (current + bytes > byteLimit) return false;
		} while (!queuedBytes.compareAndSet(current, current + bytes));
		queue.add(new Entry(connection, channel, message, bytes));
		return true;
	}

	/** Encoded bytes currently held in the queue, not yet handed to Netty. */
	long getQueuedBytes() {
		return queuedBytes.get();
	}

	private void drain() {
		while (running) {
			try {
				drainBatch(queue.take());
			} catch (InterruptedException ex) {
				break;
			}
		}
	}

	/** Hands queued messages to Netty up to one batch, then flushes. Returns the number handed over. */
	int drainBatch() {
		Entry first = queue.poll();
		return (first == null) ? 0 : drainBatch(first);
	}

	private int drainBatch(Entry first) {
		LinkedHashSet<Channel> touched = new LinkedHashSet<>();
		Entry e = first;
		int n = 0;
		while (e != null) {
			queuedBytes.addAndGet(-e.bytes());
			write(e, touched);
			e = (++n < MAX_BATCH) ? queue.poll() : null;
		}
		for (Channel ch : touched) ch.flush();
		return n;
	}

	private void write(Entry e, LinkedHashSet<Channel> touched) {
		Channel ch = e.channel();
		if (!ch.isActive()) return;
		NettyServerConnection conn = e.connection();
		int bytes = e.bytes();
		conn.addPendingBytes(bytes);
		try {
			ch.write(e.message()).addListener(f -> conn.addPendingBytes(-bytes));
			touched.add(ch);
		} catch (RuntimeException ex) {
			conn.addPendingBytes(-bytes);
			log.warn("Failed to write server reply", ex);
		}
	}

	void close() {
		running = false;
		if (writer != null) writer.interrupt();
	}
}
