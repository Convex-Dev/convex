package convex.restapi.mcp;

import java.io.PrintWriter;

/**
 * A server-to-client SSE connection opened via GET /mcp.
 * Wraps a PrintWriter with synchronised event sending.
 *
 * <p>Shared infrastructure for MCP servers that support the Streamable HTTP
 * transport with SSE notifications.</p>
 */
public class SseConnection {
	final PrintWriter writer;
	volatile boolean closed = false;

	public SseConnection(PrintWriter writer) {
		this.writer = writer;
	}

	/**
	 * Send an SSE event to this connection.
	 * @param eventType The event type (e.g. "message")
	 * @param data The event data (e.g. JSON string)
	 */
	public void sendEvent(String eventType, String data) {
		if (closed) return;
		synchronized (writer) {
			writer.write("event: " + eventType + "\n");
			writer.write("data: " + data + "\n\n");
			writer.flush();
			if (writer.checkError()) close();
		}
	}

	/**
	 * Close this connection. Idempotent.
	 */
	public void close() {
		closed = true;
	}

	/**
	 * Check if this connection is closed.
	 * @return true if closed
	 */
	public boolean isClosed() {
		return closed;
	}
}
