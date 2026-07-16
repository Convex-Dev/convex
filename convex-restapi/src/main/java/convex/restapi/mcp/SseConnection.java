package convex.restapi.mcp;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * A server-to-client SSE connection opened via GET /mcp.
 * Queues events for delivery by a per-connection virtual thread.
 *
 * <p>Shared infrastructure for MCP servers that support the Streamable HTTP
 * transport with SSE notifications. Sending is non-blocking. If a slow client
 * fills the bounded queue, only that connection is closed.</p>
 */
public class SseConnection {
	static final int DEFAULT_EVENT_QUEUE_CAPACITY=64;

	private record Event(String type, String data) {}

	private final PrintWriter writer;
	private final ArrayBlockingQueue<Event> events;
	private volatile boolean closed;
	private volatile Thread dispatcher;

	public SseConnection(PrintWriter writer) {
		this(writer,DEFAULT_EVENT_QUEUE_CAPACITY);
	}

	SseConnection(PrintWriter writer, int capacity) {
		this.writer=Objects.requireNonNull(writer,"SSE writer cannot be null");
		if (capacity<=0) throw new IllegalArgumentException("SSE queue capacity must be positive");
		this.events=new ArrayBlockingQueue<>(capacity);
	}

	/**
	 * Queues an SSE event for this connection. If the client cannot keep up and
	 * the bounded event queue is full, the connection is closed.
	 *
	 * @param eventType The event type (e.g. "message")
	 * @param data The event data (e.g. JSON string)
	 */
	public void sendEvent(String eventType, String data) {
		if (closed) return;
		Event event=new Event(
			Objects.requireNonNull(eventType,"SSE event type cannot be null"),
			Objects.requireNonNull(data,"SSE event data cannot be null"));
		ensureDispatcher();
		if (closed) return;
		if (!events.offer(event)) close();
	}

	private void ensureDispatcher() {
		if ((dispatcher!=null)||closed) return;
		synchronized (this) {
			if ((dispatcher!=null)||closed) return;
			dispatcher=Thread.ofVirtual().name("sse-connection-writer").start(this::dispatchLoop);
		}
	}

	private void dispatchLoop() {
		Thread thread=Thread.currentThread();
		try {
			while (!closed) {
				Event event;
				try {
					event=events.take();
				} catch (InterruptedException e) {
					if (closed) break;
					continue;
				}
				if (closed) break;
				synchronized (writer) {
					if (closed) break;
					writer.write("event: " + event.type() + "\n");
					writer.write("data: " + event.data() + "\n\n");
					writer.flush();
					if (writer.checkError()) close();
				}
			}
		} finally {
			synchronized (this) {
				if (dispatcher==thread) dispatcher=null;
			}
		}
	}

	/**
	 * Close this connection. Idempotent.
	 */
	public void close() {
		Thread thread;
		synchronized (this) {
			if (closed) return;
			closed=true;
			events.clear();
			thread=dispatcher;
		}
		if (thread!=null) thread.interrupt();
	}

	/**
	 * Check if this connection is closed.
	 * @return true if closed
	 */
	public boolean isClosed() {
		return closed;
	}
}
