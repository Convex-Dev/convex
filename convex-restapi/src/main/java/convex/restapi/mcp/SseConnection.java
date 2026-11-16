package convex.restapi.mcp;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.util.ThreadUtils;

/**
 * A server-to-client SSE connection. Queues events for delivery by a
 * per-connection virtual thread.
 *
 * <p>Shared infrastructure for MCP servers that support the Streamable HTTP
 * transport with SSE notifications. Sending is non-blocking; a full event queue
 * is handled per the connection's {@link OverflowPolicy}, affecting only that
 * connection.</p>
 */
public class SseConnection {
	private static final Logger log=LoggerFactory.getLogger(SseConnection.class);

	static final int DEFAULT_EVENT_QUEUE_CAPACITY=64;

	/** Bound on how long a close waits for an event being written */
	static final Duration CLOSE_TIMEOUT=Duration.ofSeconds(5);

	/** Policy applied when an event arrives and the bounded queue is full. */
	public enum OverflowPolicy {
		/** Close the connection. Correct where every event matters and silent
		 * loss would corrupt the stream (MCP messages, log events). */
		CLOSE,
		/** Drop the oldest queued event to make room. Correct for conflatable
		 * streams where the newest value supersedes anything queued (query
		 * watches): a fast-changing source degrades to bounded staleness
		 * instead of losing the connection. */
		DROP_OLDEST
	}

	/** A null type denotes an SSE comment frame. */
	private record Event(String id, String type, String data) {}

	private final PrintWriter writer;
	private final ArrayBlockingQueue<Event> events;
	private final OverflowPolicy overflowPolicy;
	private volatile boolean closed;
	private volatile Thread dispatcher;

	public SseConnection(PrintWriter writer) {
		this(writer,DEFAULT_EVENT_QUEUE_CAPACITY);
	}

	/**
	 * Creates an SSE connection with an explicit bounded queue capacity.
	 *
	 * @param writer Destination writer
	 * @param capacity Maximum queued events
	 */
	public SseConnection(PrintWriter writer, int capacity) {
		this(writer,capacity,OverflowPolicy.CLOSE);
	}

	/**
	 * Creates an SSE connection with an explicit bounded queue capacity and
	 * overflow policy.
	 *
	 * @param writer Destination writer
	 * @param capacity Maximum queued events
	 * @param overflowPolicy Behaviour when the queue is full
	 */
	public SseConnection(PrintWriter writer, int capacity, OverflowPolicy overflowPolicy) {
		this.writer=Objects.requireNonNull(writer,"SSE writer cannot be null");
		if (capacity<=0) throw new IllegalArgumentException("SSE queue capacity must be positive");
		this.events=new ArrayBlockingQueue<>(capacity);
		this.overflowPolicy=Objects.requireNonNull(overflowPolicy,"SSE overflow policy cannot be null");
	}

	/**
	 * Queues an SSE event for this connection. If the client cannot keep up and
	 * the bounded event queue is full, the {@link OverflowPolicy} applies.
	 *
	 * @param eventType The event type (e.g. "message")
	 * @param data The event data (e.g. JSON string)
	 */
	public void sendEvent(String eventType, String data) {
		sendEvent(null,eventType,data);
	}

	/**
	 * Queues an identified SSE event for this connection. The event ID may be used
	 * by a client to identify its position in a stream.
	 *
	 * @param eventID Event ID, or {@code null} for no ID
	 * @param eventType The event type (e.g. "message")
	 * @param data The event data
	 */
	public void sendEvent(String eventID, String eventType, String data) {
		if (closed) return;
		Event event=new Event(
			validateField(eventID,"SSE event ID"),
			Objects.requireNonNull(validateField(eventType,"SSE event type"),"SSE event type cannot be null"),
			Objects.requireNonNull(data,"SSE event data cannot be null"));
		send(event);
	}

	/**
	 * Queues an SSE comment on the same dispatcher as events. This is used for
	 * connection and keepalive frames so the HTTP response has exactly one writer.
	 *
	 * @param comment Comment text without a line break
	 */
	public void sendComment(String comment) {
		if (closed) return;
		send(new Event(null,null,Objects.requireNonNull(
			validateField(comment,"SSE comment"),"SSE comment cannot be null")));
	}

	private void send(Event event) {
		if (closed) return;
		ensureDispatcher();
		if (closed) return;
		if (!events.offer(event)) {
			if (overflowPolicy==OverflowPolicy.CLOSE) {
				// Not close(): sending never waits, least of all for a write to this slow client
				stop();
				return;
			}
			// DROP_OLDEST: evict until the new event fits; the dispatcher may be
			// draining concurrently, so poll and offer race benignly until success
			do {
				events.poll();
			} while (!closed&&!events.offer(event));
		}
	}

	private static String validateField(String value, String name) {
		if ((value!=null)&&((value.indexOf('\r')>=0)||(value.indexOf('\n')>=0))) {
			throw new IllegalArgumentException(name+" cannot contain a line break");
		}
		return value;
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
				String frame=frame(event);
				synchronized (writer) {
					if (closed) break;
					// One write per event: a close interrupts at most this call, never half a frame
					writer.write(frame);
					// After a close, no flush: the write it cut short failed, and a flush could block again
					if (closed) break;
					// checkError() flushes an open stream (documented behaviour) and reports any
					// IOException recorded by the write above, so this delivers the event and
					// detects a broken client in one step. A separate flush() would only repeat it.
					if (writer.checkError()) stop();
				}
			}
		} finally {
			synchronized (this) {
				if (dispatcher==thread) dispatcher=null;
			}
		}
	}

	/** An event's SSE frame, or a comment's, ending with the blank line that ends it. */
	private static String frame(Event event) {
		StringBuilder frame=new StringBuilder();
		if (event.type()==null) {
			frame.append(": ").append(event.data()).append("\n\n");
			return frame.toString();
		}
		if (event.id()!=null) frame.append("id: ").append(event.id()).append('\n');
		frame.append("event: ").append(event.type()).append('\n');
		String data=event.data();
		int start=0;
		int length=data.length();
		for (int i=0;i<=length;i++) {
			if ((i<length)&&(data.charAt(i)!='\r')&&(data.charAt(i)!='\n')) continue;
			frame.append("data: ").append(data,start,i).append('\n');
			if ((i<length)&&(data.charAt(i)=='\r')&&(i+1<length)&&(data.charAt(i+1)=='\n')) i++;
			start=i+1;
		}
		frame.append('\n');
		return frame.toString();
	}

	/**
	 * Closes this connection and waits until it has stopped writing, so the caller may
	 * then finish the response. A write that does not end when interrupted is waited for
	 * up to {@link #CLOSE_TIMEOUT}. Idempotent; called while writing, returns at once.
	 */
	public void close() {
		stop();
		// Also after a close by a sender, which marks the connection closed without waiting
		Thread thread=dispatcher;
		if (thread==null) return;
		if (!ThreadUtils.joinUninterruptibly(thread,CLOSE_TIMEOUT) && (thread!=Thread.currentThread())) {
			log.warn("SSE writer still writing {} after close",CLOSE_TIMEOUT);
		}
	}

	/** Marks this connection closed and interrupts a write under way, without waiting. */
	private void stop() {
		Thread thread;
		synchronized (this) {
			if (closed) return;
			closed=true;
			events.clear();
			thread=dispatcher;
			notifyAll();
		}
		if (thread!=null) thread.interrupt();
	}

	/**
	 * Waits until this connection closes or the timeout expires.
	 *
	 * @param timeout Maximum wait
	 * @param unit Timeout unit
	 * @return {@code true} if closed, or {@code false} on timeout
	 * @throws InterruptedException if interrupted while waiting
	 */
	public synchronized boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
		Objects.requireNonNull(unit,"Timeout unit cannot be null");
		if (timeout<0) throw new IllegalArgumentException("Timeout cannot be negative");
		if (closed) return true;
		long timeoutNanos=unit.toNanos(timeout);
		long remaining=timeoutNanos;
		long start=System.nanoTime();
		while (!closed&&(remaining>0)) {
			TimeUnit.NANOSECONDS.timedWait(this,remaining);
			remaining=timeoutNanos-(System.nanoTime()-start);
		}
		return closed;
	}

	/**
	 * Check if this connection is closed.
	 * @return true if closed
	 */
	public boolean isClosed() {
		return closed;
	}
}
