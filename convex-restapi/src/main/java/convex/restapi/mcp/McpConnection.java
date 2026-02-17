package convex.restapi.mcp;

import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;

/**
 * An MCP Streamable HTTP connection opened via GET /mcp.
 *
 * <p>Wraps a {@link PrintWriter} for SSE event delivery and owns state watches
 * that die with the connection. When the client disconnects (or the server
 * closes the connection), all watches are implicitly destroyed.</p>
 *
 * <p>Used by Convex peers. Covia uses {@link McpSession} instead (persistent
 * sessions across reconnects).</p>
 */
public class McpConnection {
	final PrintWriter writer;
	volatile boolean closed = false;

	/** Watches owned by this connection */
	final ConcurrentHashMap<String, StateWatcher.WatchEntry> watches = new ConcurrentHashMap<>();
	private final AtomicLong watchCounter = new AtomicLong(0);

	public McpConnection(PrintWriter writer) {
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
	 * Add a watch to this connection.
	 * @param path Array of keys for RT.getIn navigation
	 * @param pathVec Vector form of the path (for prefix comparison)
	 * @param pathString String form of the path (for notification payloads)
	 * @param initialHash Hash of the current value at the path
	 * @return The watch ID (e.g. "w-1")
	 */
	public String addWatch(ACell[] path, AVector<ACell> pathVec, String pathString, Hash initialHash) {
		String watchId = "w-" + watchCounter.incrementAndGet();
		StateWatcher.WatchEntry entry = new StateWatcher.WatchEntry(watchId, path, pathVec, pathString, initialHash);
		watches.put(watchId, entry);
		return watchId;
	}

	/**
	 * Remove a watch from this connection.
	 * @param watchId The watch ID to remove
	 * @return true if the watch existed and was removed
	 */
	public boolean removeWatch(String watchId) {
		return watches.remove(watchId) != null;
	}

	/**
	 * Remove all watches whose path vector starts with the given prefix vector.
	 * @param prefix Path prefix to match
	 * @return The number of watches removed
	 */
	public long removeWatchesByPathPrefix(AVector<ACell> prefix) {
		long count = 0;
		var it = watches.values().iterator();
		while (it.hasNext()) {
			if (it.next().pathStartsWith(prefix)) {
				it.remove();
				count++;
			}
		}
		return count;
	}

	/**
	 * Check if this connection has any active watches.
	 * @return true if at least one watch is registered
	 */
	public boolean hasWatches() {
		return !watches.isEmpty();
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
