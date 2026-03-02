package convex.restapi.mcp;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Best-efforts state watcher that monitors paths in a state tree and
 * pushes SSE notifications when values change.
 *
 * <p>Uses a single daemon virtual thread to poll all active watches across all
 * sessions. Watches are stored in their owning {@link McpSession} and
 * are automatically cleaned up when the session's SSE connections close.</p>
 *
 * <p>Notifications are delivered on a best-efforts basis — if a session's SSE
 * connection is slow or closed, the notification is dropped silently.</p>
 *
 * <p>This class is state-source-agnostic: it takes a {@link StateResolver}
 * that abstracts how to navigate to a value at a given path. Convex uses
 * CVM global state; Covia uses lattice state.</p>
 */
public class StateWatcher {

	private static final Logger log = LoggerFactory.getLogger(StateWatcher.class);

	/** Minimum interval between state polls */
	private static final long POLL_INTERVAL_MS = 1000;

	/** Max memory size of a value to include inline in notifications */
	public static final long VALUE_SIZE_THRESHOLD = 1024;

	/**
	 * Functional interface for resolving a value at a path in the state tree.
	 * Implementations provide the state source (CVM state, lattice, etc.).
	 */
	@FunctionalInterface
	public interface StateResolver {
		/**
		 * Resolve the current value at the given path.
		 * @param path Array of keys to navigate into the state
		 * @return The value at the path, or null if not found
		 */
		ACell resolve(ACell[] path);
	}

	/**
	 * A single watch entry tracking a path in the state.
	 */
	public static class WatchEntry {
		public final String watchId;
		public final ACell[] path;        // for RT.getIn navigation
		public final AVector<ACell> pathVec; // for structural prefix comparison
		public final String pathString;   // for notification payloads
		public volatile Hash lastHash;

		public WatchEntry(String watchId, ACell[] path, AVector<ACell> pathVec, String pathString, Hash initialHash) {
			this.watchId = watchId;
			this.path = path;
			this.pathVec = pathVec;
			this.pathString = pathString;
			this.lastHash = initialHash;
		}

		/**
		 * Check if this watch's path starts with the given prefix vector.
		 */
		public boolean pathStartsWith(AVector<ACell> prefix) {
			return pathVec.commonPrefixLength(prefix) >= prefix.count();
		}
	}

	private final StateResolver resolver;
	private final ConcurrentHashMap<String, McpSession> sessions;

	private volatile Thread watcherThread;
	private volatile boolean running;

	/**
	 * Create a StateWatcher with a pluggable state resolver.
	 * @param resolver Provides state values at given paths
	 * @param sessions Map of session ID to McpSession (shared with the MCP server)
	 */
	public StateWatcher(StateResolver resolver, ConcurrentHashMap<String, McpSession> sessions) {
		this.resolver = resolver;
		this.sessions = sessions;
	}

	/**
	 * Ensure the watcher thread is running. Called after a watch is added.
	 */
	public synchronized void ensureRunning() {
		if (running) return;
		running = true;
		watcherThread = Thread.ofVirtual().name("mcp-state-watcher").start(this::pollLoop);
	}

	/**
	 * Shut down the watcher thread.
	 */
	public void shutdown() {
		running = false;
		Thread t = watcherThread;
		if (t != null) t.interrupt();
	}

	/**
	 * Resolve the current value at a path using the configured resolver.
	 * @param path Array of keys to navigate
	 * @return The value at the path, or null
	 */
	public ACell resolveValue(ACell[] path) {
		return resolver.resolve(path);
	}

	// ===== Internal =====

	private void pollLoop() {
		try {
			while (running) {
				if (!hasAnyWatches()) {
					break;
				}
				try {
					checkAllSessions();
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

	private boolean hasAnyWatches() {
		for (McpSession session : sessions.values()) {
			if (session.hasWatches()) return true;
		}
		return false;
	}

	private void checkAllSessions() {
		for (McpSession session : sessions.values()) {
			if (!session.hasWatches()) continue;
			for (WatchEntry entry : session.watches.values()) {
				try {
					ACell value = resolver.resolve(entry.path);
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

	private void notifyChange(McpSession session, WatchEntry entry, ACell newValue) {
		var params = Maps.of(
			"watchId", entry.watchId,
			"path", entry.pathString,
			"changed", CVMBool.TRUE
		);

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

		for (SseConnection conn : session.sseConnections) {
			try {
				conn.sendEvent("message", json);
			} catch (Exception e) {
				log.debug("Failed to send watch notification to SSE connection", e);
			}
		}
	}
}
