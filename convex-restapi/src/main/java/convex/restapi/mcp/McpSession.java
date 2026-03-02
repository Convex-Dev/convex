package convex.restapi.mcp;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;

/**
 * An MCP session, created on initialize, that tracks SSE connections and
 * state watches.
 *
 * <p>Sessions are identified by a UUID and stored server-side. The session ID
 * is communicated to the client via the {@code Mcp-Session-Id} response header.
 * Watches are session-scoped and automatically cleared when all SSE connections
 * close.</p>
 *
 * <p>Shared infrastructure for MCP servers that support the Streamable HTTP
 * transport with SSE notifications.</p>
 */
public class McpSession {
	public final String id;
	public final Set<SseConnection> sseConnections = ConcurrentHashMap.newKeySet();
	public final ConcurrentHashMap<String, StateWatcher.WatchEntry> watches = new ConcurrentHashMap<>();
	private final AtomicLong watchCounter = new AtomicLong(0);

	public McpSession(String id) {
		this.id = id;
	}

	/**
	 * Add a watch to this session.
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
	 * Remove a watch from this session.
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
	 * Check if this session has any active watches.
	 * @return true if at least one watch is registered
	 */
	public boolean hasWatches() {
		return !watches.isEmpty();
	}

	/**
	 * Clear all watches in this session.
	 */
	public void clearWatches() {
		watches.clear();
	}

	/**
	 * Close this session: clear watches and close all SSE connections.
	 */
	public void close() {
		clearWatches();
		for (SseConnection conn : sseConnections) conn.close();
		sseConnections.clear();
	}
}
