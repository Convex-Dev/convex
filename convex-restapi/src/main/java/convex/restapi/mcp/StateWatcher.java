package convex.restapi.mcp;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.peer.Server;

/**
 * Best-efforts state watcher that monitors paths in the CVM global state and
 * pushes SSE notifications when values change.
 *
 * <p>Uses a single daemon virtual thread to poll all active watches across all
 * sessions. Watches are stored in their owning {@link McpAPI.McpSession} and
 * are automatically cleaned up when the session's SSE connections close.</p>
 *
 * <p>Notifications are delivered on a best-efforts basis — if a session's SSE
 * connection is slow or closed, the notification is dropped silently.</p>
 */
class StateWatcher {

	private static final Logger log = LoggerFactory.getLogger(StateWatcher.class);

	/** Minimum interval between state polls */
	private static final long POLL_INTERVAL_MS = 1000;

	/** Max memory size of a value to include inline in notifications */
	static final long VALUE_SIZE_THRESHOLD = 1024;

	private final Server server;
	private final ConcurrentHashMap<String, McpAPI.McpSession> sessions;

	private volatile Thread watcherThread;
	private volatile boolean running;

	StateWatcher(Server server, ConcurrentHashMap<String, McpAPI.McpSession> sessions) {
		this.server = server;
		this.sessions = sessions;
	}

	/**
	 * A single watch entry tracking a path in the global state.
	 */
	static class WatchEntry {
		final String watchId;
		final ACell[] path;        // for RT.getIn navigation
		final AVector<ACell> pathVec; // for structural prefix comparison
		final String pathString;   // for notification payloads
		volatile Hash lastHash;

		WatchEntry(String watchId, ACell[] path, AVector<ACell> pathVec, String pathString, Hash initialHash) {
			this.watchId = watchId;
			this.path = path;
			this.pathVec = pathVec;
			this.pathString = pathString;
			this.lastHash = initialHash;
		}

		/**
		 * Check if this watch's path starts with the given prefix vector.
		 */
		boolean pathStartsWith(AVector<ACell> prefix) {
			return pathVec.commonPrefixLength(prefix) >= prefix.count();
		}
	}

	/**
	 * Ensure the watcher thread is running. Called after a watch is added.
	 */
	synchronized void ensureRunning() {
		if (running) return;
		running = true;
		watcherThread = Thread.ofVirtual().name("mcp-state-watcher").start(this::pollLoop);
	}

	/**
	 * Shut down the watcher thread.
	 */
	void shutdown() {
		running = false;
		Thread t = watcherThread;
		if (t != null) t.interrupt();
	}

	/**
	 * Resolve the current value at a path in the global state.
	 */
	ACell resolveValue(ACell[] path) {
		State state = server.getState();
		if (state == null) return null;
		return RT.getIn(state, path);
	}

	// ===== Internal =====

	private void pollLoop() {
		try {
			while (running) {
				if (!hasAnyWatches()) {
					// Nothing to watch — stop the thread
					break;
				}
				try {
					State state = server.getState();
					if (state != null) {
						checkAllSessions(state);
					}
				} catch (Exception e) {
					log.debug("Error in state watcher poll", e);
				}
				Thread.sleep(POLL_INTERVAL_MS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			running = false;
			watcherThread = null;
		}
	}

	/**
	 * Check if any session has active watches.
	 */
	private boolean hasAnyWatches() {
		for (McpAPI.McpSession session : sessions.values()) {
			if (session.hasWatches()) return true;
		}
		return false;
	}

	/**
	 * Check watches across all sessions.
	 */
	private void checkAllSessions(State state) {
		for (McpAPI.McpSession session : sessions.values()) {
			if (!session.hasWatches()) continue;
			for (WatchEntry entry : session.watches.values()) {
				try {
					ACell value = RT.getIn(state, entry.path);
					Hash currentHash = Hash.get(value);
					if (!currentHash.equals(entry.lastHash)) {
						entry.lastHash = currentHash;
						notifyChange(session, entry, value);
					}
				} catch (Exception e) {
					log.debug("Error checking watch {}", entry.watchId, e);
				}
			}
		}
	}

	private void notifyChange(McpAPI.McpSession session, WatchEntry entry, ACell newValue) {
		// Build JSON-RPC notification
		var params = Maps.of(
			"watchId", entry.watchId,
			"path", entry.pathString,
			"changed", CVMBool.TRUE
		);

		// Include value if small enough
		long memSize = ACell.getMemorySize(newValue);
		if (memSize <= VALUE_SIZE_THRESHOLD) {
			params = params.assoc(Strings.create("value"), RT.print(newValue));
		}

		var notification = Maps.of(
			"jsonrpc", "2.0",
			"method", "notifications/stateChanged",
			"params", params
		);

		String json = JSON.print(notification).toString();

		// Push to all SSE connections in the session — best efforts
		for (McpAPI.SseConnection conn : session.sseConnections) {
			try {
				conn.sendEvent("message", json);
			} catch (Exception e) {
				log.debug("Failed to send watch notification to SSE connection", e);
			}
		}
	}
}
