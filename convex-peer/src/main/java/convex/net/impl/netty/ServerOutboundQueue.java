package convex.net.impl.netty;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.message.Message;
import convex.peer.Config;
import io.netty.channel.Channel;

/**
 * Server-wide outbound queue for replies to inbound connections.
 *
 * <p>Replies are queued in FIFO order and handed to Netty by a single writer
 * thread, which flushes each touched channel once per batch. The queue is a
 * burst absorber bounded by the total encoded bytes it holds: bytes are
 * released as soon as a message is handed to Netty, so a slow reader can never
 * pin the shared bound or delay other connections. What a connection has
 * handed to Netty but not yet written is bounded separately per connection: a
 * small allowance for a client, a large one for a verified peer, which is
 * buffered for rather than cut off when it lags. Once over its allowance a
 * connection's further replies are refused, and only its own.</p>
 *
 * <p>Two policies sit on top of the bound. Traffic to trusted peers is queued
 * in a lane that the writer drains first and that is exempt from the shared
 * bound, so consensus and lattice replies are never held up behind client
 * results. Client results may instead wait for space through
 * {@link #offerBlocking}, applying backpressure to the reporting thread rather
 * than dropping results when the server as a whole is overloaded.</p>
 */
final class ServerOutboundQueue {

	static final Logger log = LoggerFactory.getLogger(ServerOutboundQueue.class);

	/** Maximum messages handed to Netty between flushes. */
	private static final int MAX_BATCH = 256;

	private record Entry(NettyServerConnection connection, Channel channel, Message message, int bytes) {}

	private final long byteLimit;
	private final long connectionByteLimit;
	private final long trustedConnectionByteLimit;

	/** Replies to trusted peers, drained first and exempt from the shared bound. Guarded by this. */
	private final ArrayDeque<Entry> priority = new ArrayDeque<>();
	/** Replies to everyone else, bounded by {@link #byteLimit}. Guarded by this. */
	private final ArrayDeque<Entry> ordinary = new ArrayDeque<>();
	/** Encoded bytes held in {@link #ordinary}. Guarded by this. */
	private long queuedBytes;

	private volatile boolean running = true;
	private final Thread writer;

	/**
	 * @param byteLimit Maximum encoded bytes held in the queue for untrusted connections
	 * @param connectionByteLimit Bytes handed to Netty but unwritten beyond which a client connection's replies are refused
	 */
	ServerOutboundQueue(long byteLimit, long connectionByteLimit) {
		this(byteLimit, connectionByteLimit, Config.PEER_OUTBOUND_QUEUE_BYTE_LIMIT, true);
	}

	/** Test hook: a queue without a writer thread is drained explicitly via {@link #drainBatch()}. */
	ServerOutboundQueue(long byteLimit, long connectionByteLimit, boolean startWriter) {
		this(byteLimit, connectionByteLimit, Config.PEER_OUTBOUND_QUEUE_BYTE_LIMIT, startWriter);
	}

	/**
	 * @param trustedConnectionByteLimit The same allowance for a verified peer connection
	 */
	ServerOutboundQueue(long byteLimit, long connectionByteLimit, long trustedConnectionByteLimit, boolean startWriter) {
		if (byteLimit < 1 || connectionByteLimit < 1 || trustedConnectionByteLimit < 1) {
			throw new IllegalArgumentException("Byte limits must be positive");
		}
		this.byteLimit = byteLimit;
		this.connectionByteLimit = connectionByteLimit;
		this.trustedConnectionByteLimit = trustedConnectionByteLimit;
		this.writer = startWriter ? Thread.ofVirtual().name("Server outbound writer").start(this::drain) : null;
	}

	/**
	 * Queues a message for a connection without waiting.
	 *
	 * @return true if queued; false if the queue is closed, the shared bound is full, or the
	 *         connection already has more than its cap handed to Netty and unwritten
	 */
	boolean offer(NettyServerConnection connection, Channel channel, Message message, int bytes) {
		try {
			return offer(connection, channel, message, bytes, 0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Queues a message for a connection, waiting up to the timeout for space in the
	 * shared bound. Never waits for a connection that is over its own cap or closed:
	 * those are refused at once, so a stalled reader cannot hold up the caller.
	 *
	 * @return true if queued, false if refused or the wait timed out
	 */
	boolean offerBlocking(NettyServerConnection connection, Channel channel, Message message, int bytes,
			long timeoutMillis) throws InterruptedException {
		return offer(connection, channel, message, bytes, Math.max(0, timeoutMillis));
	}

	private boolean offer(NettyServerConnection connection, Channel channel, Message message, int bytes,
			long timeoutMillis) throws InterruptedException {
		long allowance = connection.isTrusted() ? trustedConnectionByteLimit : connectionByteLimit;
		if (bytes < 0 || connection.getPendingBytes() >= allowance) return false;
		Entry e = new Entry(connection, channel, message, bytes);
		synchronized (this) {
			if (!running) return false;
			if (connection.isTrusted()) {
				priority.addLast(e);
			} else {
				if (bytes > byteLimit) return false;
				long deadline = System.currentTimeMillis() + timeoutMillis;
				while (queuedBytes + bytes > byteLimit) {
					long remaining = deadline - System.currentTimeMillis();
					if (remaining <= 0) return false;
					wait(remaining);
					if (!running) return false;
				}
				queuedBytes += bytes;
				ordinary.addLast(e);
			}
			notifyAll();
		}
		return true;
	}

	/** Encoded bytes currently held for untrusted connections, not yet handed to Netty. */
	synchronized long getQueuedBytes() {
		return queuedBytes;
	}

	/** Next entry to hand over, trusted lane first, or null if nothing is queued. */
	private synchronized Entry poll() {
		Entry e = priority.pollFirst();
		if (e != null) return e;
		e = ordinary.pollFirst();
		if (e != null) {
			queuedBytes -= e.bytes();
			notifyAll(); // space freed for a blocked offer
		}
		return e;
	}

	private synchronized Entry take() throws InterruptedException {
		while (priority.isEmpty() && ordinary.isEmpty()) {
			if (!running) return null;
			wait();
		}
		return poll();
	}

	private void drain() {
		try {
			while (running) {
				Entry e = take();
				if (e == null) break;
				drainBatch(e, MAX_BATCH);
			}
		} catch (InterruptedException ex) {
			// shutting down
		}
	}

	/** Test hook: hands queued messages to Netty up to one batch, then flushes. Returns the number handed over. */
	int drainBatch() {
		return drainBatch(MAX_BATCH);
	}

	/** Test hook: hands up to {@code max} queued messages to Netty, then flushes. */
	int drainBatch(int max) {
		Entry first = poll();
		return (first == null) ? 0 : drainBatch(first, max);
	}

	private int drainBatch(Entry first, int max) {
		LinkedHashSet<Channel> touched = new LinkedHashSet<>();
		Entry e = first;
		int n = 0;
		while (e != null) {
			write(e, touched);
			e = (++n < max) ? poll() : null;
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
		synchronized (this) {
			running = false;
			notifyAll();
		}
		if (writer != null) writer.interrupt();
	}
}
