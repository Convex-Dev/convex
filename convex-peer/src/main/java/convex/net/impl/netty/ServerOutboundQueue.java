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
 * thread, which flushes each touched channel once per batch. The queue is
 * bounded by total encoded bytes, counted from {@link #offer} until Netty
 * reports the write complete (sent, failed or channel closed), so the bound
 * covers everything not yet on the wire. Sharing one bound across all
 * connections absorbs bursts of results without per-connection
 * over-provisioning; a per-connection share stops one slow reader from
 * consuming the whole queue.</p>
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
	 * @param byteLimit Maximum encoded bytes queued across all connections
	 * @param connectionByteLimit Maximum encoded bytes queued for one connection
	 */
	ServerOutboundQueue(long byteLimit, long connectionByteLimit) {
		if (byteLimit < 1 || connectionByteLimit < 1) throw new IllegalArgumentException("Byte limits must be positive");
		this.byteLimit = byteLimit;
		this.connectionByteLimit = connectionByteLimit;
		this.writer = Thread.ofVirtual().name("Server outbound writer").start(this::drain);
	}

	/**
	 * Queues a message for a connection. Never blocks.
	 *
	 * @return true if queued, false if the queue is closed or either bound would be exceeded
	 */
	boolean offer(NettyServerConnection connection, Channel channel, Message message, int bytes) {
		if (!running || bytes < 0) return false;
		long current;
		do {
			current = queuedBytes.get();
			if (current + bytes > byteLimit) return false;
		} while (!queuedBytes.compareAndSet(current, current + bytes));
		if (!connection.reserveOutbound(bytes, connectionByteLimit)) {
			queuedBytes.addAndGet(-bytes);
			return false;
		}
		queue.add(new Entry(connection, channel, message, bytes));
		return true;
	}

	long getQueuedBytes() {
		return queuedBytes.get();
	}

	private void release(Entry e) {
		queuedBytes.addAndGet(-e.bytes());
		e.connection().releaseOutbound(e.bytes());
	}

	private void drain() {
		LinkedHashSet<Channel> touched = new LinkedHashSet<>();
		while (running) {
			Entry e;
			try {
				e = queue.take();
			} catch (InterruptedException ex) {
				break;
			}
			touched.clear();
			int n = 0;
			while (e != null) {
				write(e, touched);
				e = (++n < MAX_BATCH) ? queue.poll() : null;
			}
			for (Channel ch : touched) ch.flush();
		}
		Entry e;
		while ((e = queue.poll()) != null) release(e);
	}

	private void write(Entry e, LinkedHashSet<Channel> touched) {
		Channel ch = e.channel();
		if (!ch.isActive()) {
			release(e);
			return;
		}
		try {
			ch.write(e.message()).addListener(f -> release(e));
			touched.add(ch);
		} catch (RuntimeException ex) {
			release(e);
			log.warn("Failed to write server reply", ex);
		}
	}

	void close() {
		running = false;
		writer.interrupt();
	}
}
