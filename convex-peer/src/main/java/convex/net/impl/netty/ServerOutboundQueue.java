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
 * pin the shared bound or delay other connections. Traffic to trusted peers is
 * queued in a lane that the writer drains first and that is exempt from the
 * shared bound, so consensus and lattice replies are never held up behind
 * client results.</p>
 *
 * <p>Nothing waits to add a message: it is queued, or refused at once. The writer
 * is the only thread that waits, for work. Each connection's backlog, the bytes
 * queued for it or handed to Netty and not yet written, is bounded by an
 * allowance: a small one for a client, a large one for a verified peer, which is
 * buffered for rather than cut off when it lags. Over its allowance a
 * connection's further replies are refused, and only its own. Before that, from
 * half its allowance, a client's requests stop being read until its backlog is
 * written down to a quarter (see {@link NettyInboundHandler}), so a client that
 * sends faster than it reads is slowed at its socket rather than losing results.
 * A client reply that finds the shared bound full is refused: the server is
 * shedding load.</p>
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
	 * @param connectionByteLimit Backlog of unwritten bytes beyond which a client connection's replies are refused
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
	 * Queues a message for a connection, never waiting. The message's bytes count
	 * towards the connection's backlog until written or discarded.
	 *
	 * @return true if queued; false if the queue is closed, the shared bound is full, or the
	 *         connection's backlog is already over its allowance
	 */
	boolean offer(NettyServerConnection connection, Channel channel, Message message, int bytes) {
		if (bytes < 0 || connection.getPendingBytes() >= allowance(connection)) return false;
		Entry e = new Entry(connection, channel, message, bytes);
		synchronized (this) {
			if (!running) return false;
			if (connection.isTrusted()) {
				priority.addLast(e);
			} else {
				if (queuedBytes + bytes > byteLimit) return false;
				queuedBytes += bytes;
				ordinary.addLast(e);
			}
			connection.addPendingBytes(bytes);
			notify(); // only the writer waits
		}
		return true;
	}

	/**
	 * Whether a connection's backlog is high enough that its requests should not be
	 * read: half the client allowance. A verified peer's reads are never paused for
	 * its replies, which it is buffered for instead.
	 */
	boolean isBacklogged(NettyServerConnection connection) {
		return !connection.isTrusted() && connection.getPendingBytes() >= connectionByteLimit / 2;
	}

	/**
	 * Releases bytes of a connection's backlog once written or discarded. Crossing
	 * down to a quarter of the client allowance lets a connection that stopped
	 * reading resume.
	 */
	private void release(NettyServerConnection connection, int bytes) {
		long after = connection.addPendingBytes(-bytes);
		long resume = connectionByteLimit / 4;
		if (after <= resume && after + bytes > resume) connection.backlogDrained();
	}

	private long allowance(NettyServerConnection connection) {
		return connection.isTrusted() ? trustedConnectionByteLimit : connectionByteLimit;
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
		if (e != null) queuedBytes -= e.bytes();
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
		NettyServerConnection conn = e.connection();
		int bytes = e.bytes();
		if (!ch.isActive()) {
			release(conn, bytes);
			return;
		}
		try {
			ch.write(e.message()).addListener(f -> release(conn, bytes));
			touched.add(ch);
		} catch (RuntimeException ex) {
			release(conn, bytes);
			log.warn("Failed to write server reply", ex);
		}
	}

	/** Stops the writer. Replies still queued are discarded: their channels are closed by now. */
	void close() {
		synchronized (this) {
			running = false;
			notifyAll();
		}
		if (writer != null) writer.interrupt();
	}
}
