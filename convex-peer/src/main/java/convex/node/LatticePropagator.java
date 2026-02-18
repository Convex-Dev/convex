package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.Utils;

/**
 * Self-contained component for propagating lattice values to peer nodes.
 *
 * <p>A LatticePropagator owns:
 * <ul>
 *   <li>An {@link AStore} — used for delta tracking (announce/novelty detection) and
 *       persistence. If the store is persistent (e.g. EtchStore), values are automatically
 *       persisted. If not (e.g. MemoryStore), the propagator handles delta tracking only.</li>
 *   <li>A {@link LatticeConnectionManager} — outbound peer connections and broadcast.</li>
 *   <li>A background thread — event-driven broadcast loop with periodic root sync.</li>
 * </ul>
 *
 * <p>The propagator has no back-reference to {@link NodeServer}. Values are pushed in
 * via {@link #triggerBroadcast(ACell)}. The propagator announces each value to its store
 * (detecting novelty for delta encoding), broadcasts deltas to peers, and periodically
 * persists the root data pointer for restore.
 *
 * <p>The store also serves as the <b>security boundary</b>: peer connections are configured
 * with the propagator's store, so DATA_REQUEST from peers can only resolve data that
 * exists in that store. A public propagator with a filtered input and a MemoryStore
 * cannot leak private data.
 *
 * <p>Designed so the peer {@code BeliefPropagator} can eventually compose or extend
 * this class. Belief is an ACell; belief broadcast uses the same delta encoding
 * ({@code Cells.announce} + {@code Format.encodeDelta}).
 */
public class LatticePropagator implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LatticePropagator.class.getName());

	/**
	 * Minimum delay between successive broadcasts to avoid flooding (milliseconds)
	 */
	public static final long MIN_BROADCAST_DELAY = 50L;

	/**
	 * Interval between root-only sync broadcasts (milliseconds).
	 * Provides a lightweight periodic sync mechanism for divergence detection.
	 */
	public static final long ROOT_SYNC_INTERVAL = 30_000L;

	/**
	 * Default interval between periodic persistence runs (milliseconds)
	 */
	public static final long DEFAULT_PERSIST_INTERVAL = 30_000L;

	/**
	 * Store for delta tracking (novelty detection via announce) and persistence.
	 * Also the security boundary for peer data resolution.
	 */
	private final AStore store;

	/**
	 * Connection manager for outbound peer connections and broadcast.
	 */
	private final LatticeConnectionManager connectionManager;

	/**
	 * Background propagation thread
	 */
	private Thread propagationThread;

	/**
	 * Flag indicating if the propagator is running
	 */
	private volatile boolean running = false;

	/**
	 * Queue for receiving lattice values to broadcast.
	 * Uses LatestUpdateQueue which only stores the most recent value,
	 * coalescing rapid updates into a single broadcast of the latest state.
	 */
	private final LatestUpdateQueue<ACell> triggerQueue = new LatestUpdateQueue<>();

	/**
	 * Last value that was announced to the store
	 */
	private ACell lastAnnouncedValue;

	/**
	 * Last value that was triggered for broadcast (used for periodic root sync)
	 */
	private volatile ACell lastTriggeredValue;

	/**
	 * Timestamp of last broadcast
	 */
	private long lastBroadcastTime = 0L;

	/**
	 * Timestamp of last root sync broadcast
	 */
	private long lastRootSyncTime = 0L;

	/**
	 * Count of broadcasts sent
	 */
	private long broadcastCount = 0L;

	/**
	 * Count of root sync broadcasts sent
	 */
	private long rootSyncCount = 0L;

	/**
	 * Timestamp of last persistence
	 */
	private long lastPersistTime = 0L;

	/**
	 * Interval between periodic persists
	 */
	private long persistInterval = DEFAULT_PERSIST_INTERVAL;

	/**
	 * Creates a new LatticePropagator with the given store and connection manager.
	 *
	 * @param store Store for delta tracking and persistence
	 * @param connectionManager Connection manager for outbound peers
	 */
	public LatticePropagator(AStore store, LatticeConnectionManager connectionManager) {
		if (store == null) throw new IllegalArgumentException("Store must not be null");
		if (connectionManager == null) throw new IllegalArgumentException("ConnectionManager must not be null");
		this.store = store;
		this.connectionManager = connectionManager;
	}

	/**
	 * Creates a new LatticePropagator with the given store, creating a new
	 * ConnectionManager that uses the same store.
	 *
	 * @param store Store for delta tracking, persistence, and peer data resolution
	 */
	public LatticePropagator(AStore store) {
		this(store, new LatticeConnectionManager(store));
	}

	/**
	 * Gets the connection manager for this propagator.
	 *
	 * @return The connection manager (for adding/removing peers)
	 */
	public LatticeConnectionManager getConnectionManager() {
		return connectionManager;
	}

	/**
	 * Gets the store used by this propagator.
	 *
	 * @return The store (delta tracking + persistence + security boundary)
	 */
	public AStore getStore() {
		return store;
	}

	/**
	 * Sets the interval between periodic persistence runs.
	 *
	 * @param intervalMs Interval in milliseconds (0 or negative to disable)
	 */
	public void setPersistInterval(long intervalMs) {
		this.persistInterval = intervalMs;
	}

	/**
	 * Restores the last persisted value from this propagator's store.
	 *
	 * @return The restored value, or null if no persisted value exists
	 *         or the store is not persistent
	 */
	public ACell restore() {
		if (!store.isPersistent()) return null;
		try {
			return store.getRootData();
		} catch (IOException e) {
			log.warn("Error restoring lattice value from store", e);
			return null;
		}
	}

	// ========== Lifecycle ==========

	/**
	 * Starts the propagation thread.
	 */
	public synchronized void start() {
		if (running) {
			log.warn("LatticePropagator already running");
			return;
		}

		running = true;
		lastAnnouncedValue = null;
		lastTriggeredValue = null;
		lastBroadcastTime = 0L;
		lastRootSyncTime = 0L;
		lastPersistTime = 0L;

		propagationThread = new Thread(this::propagationLoop, "Lattice propagator thread");
		propagationThread.setDaemon(true);
		propagationThread.start();

		log.debug("LatticePropagator started");
	}

	/**
	 * Stops the propagator gracefully.
	 */
	@Override
	public void close() {
		if (!running) return;

		log.debug("Stopping LatticePropagator");
		running = false;

		if (propagationThread != null) {
			propagationThread.interrupt();
			try {
				propagationThread.join(5000);
				if (propagationThread.isAlive()) {
					log.warn("LatticePropagator thread did not terminate within timeout");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while waiting for LatticePropagator to stop");
			}
		}

		log.debug("LatticePropagator stopped (sent {} delta broadcasts, {} root syncs)",
			broadcastCount, rootSyncCount);
	}

	// ========== Trigger API ==========

	/**
	 * Triggers an immediate broadcast of the given lattice value.
	 *
	 * <p>Uses LatestUpdateQueue which automatically overwrites any previous value,
	 * coalescing rapid updates into a single broadcast of the latest state.
	 *
	 * @param value The lattice value to broadcast (must not be null)
	 */
	public void triggerBroadcast(ACell value) {
		if (!running) return;
		if (value == null) return;
		lastTriggeredValue = value;
		triggerQueue.offer(value);
	}

	// ========== Propagation Loop ==========

	/**
	 * Main propagation loop (no external references).
	 */
	private void propagationLoop() {
		while (running && !Thread.currentThread().isInterrupted()) {
			try {
				// Wait for a value to broadcast (with timeout for periodic tasks)
				ACell value = triggerQueue.poll(ROOT_SYNC_INTERVAL, TimeUnit.MILLISECONDS);
				long currentTime = Utils.getCurrentTimestamp();

				// If we received a value, broadcast and persist it
				if (value != null) {
					sendDeltaIfReady(value, currentTime);
					maybePersist(value, currentTime);
				}

				// Periodic persistence even without broadcast trigger
				ACell current = lastTriggeredValue;
				if (current != null) {
					maybePersist(current, Utils.getCurrentTimestamp());
				}

				// Periodic root-only sync for divergence detection
				maybePerformRootSync(lastTriggeredValue, currentTime);

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.debug("LatticePropagator interrupted");
				break;
			} catch (Exception e) {
				log.warn("Unexpected error in propagation loop", e);
			}
		}
		log.debug("LatticePropagator loop ended");
	}

	/**
	 * Sends a delta broadcast if ready (respects minimum delay).
	 * After sending, checks if newer values arrived and sends again.
	 */
	private void sendDeltaIfReady(ACell value, long currentTime) {
		if (value == null) return;

		// Enforce minimum delay between broadcasts
		if (currentTime < lastBroadcastTime + MIN_BROADCAST_DELAY) {
			log.trace("Skipping broadcast due to minimum delay");
			return;
		}

		// Send delta broadcasts in a loop until no more changes
		do {
			try {
				broadcast(value);
				lastAnnouncedValue = value;
				lastBroadcastTime = currentTime;
				broadcastCount++;
				log.debug("Delta broadcast sent (count: {})", broadcastCount);

				// Check if a newer value arrived while we were broadcasting
				ACell newer = triggerQueue.poll();
				if (newer == null) break;

				value = newer;
				currentTime = Utils.getCurrentTimestamp();
				log.debug("Newer value arrived during broadcast, sending another delta");

			} catch (IOException e) {
				log.warn("Error during lattice broadcast", e);
				break;
			}
		} while (running && !Thread.currentThread().isInterrupted());
	}

	// ========== Broadcast ==========

	/**
	 * Broadcasts the given lattice value to all connected peers using delta encoding.
	 */
	private void broadcast(ACell value) throws IOException {
		if (connectionManager.getPeers().isEmpty()) {
			log.trace("No peers to broadcast to");
			return;
		}

		Message message = createLatticeUpdateMessage(value);
		connectionManager.broadcast(message);

		log.debug("Broadcasted lattice update ({} bytes)", message.getMessageData().count());
	}

	/**
	 * Creates a delta-encoded LATTICE_VALUE message for the given value.
	 * Announces value to own store, collecting novel cells for delta encoding.
	 */
	private Message createLatticeUpdateMessage(ACell value) throws IOException {
		ArrayList<ACell> novelty = new ArrayList<>();

		Consumer<Ref<ACell>> noveltyHandler = r -> {
			novelty.add(r.getValue());
		};

		// Announce to own store — this is both delta tracking AND persistence
		value = Cells.announce(value, noveltyHandler, store);

		// Ensure value is in the novelty list
		if (novelty.isEmpty() || !novelty.get(novelty.size() - 1).equals(value)) {
			novelty.add(value);
		}

		Blob deltaData = Format.encodeDelta(novelty);

		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, value);
		Message message = Message.create(MessageType.LATTICE_VALUE, payload, deltaData);

		log.trace("Created lattice update message with {} novel cells, {} bytes",
			novelty.size(), deltaData.count());

		return message;
	}

	// ========== Root Sync ==========

	/**
	 * Performs periodic root-only sync broadcast for divergence detection.
	 */
	private void maybePerformRootSync(ACell value, long currentTime) {
		if (value == null) return;
		if (currentTime < lastRootSyncTime + ROOT_SYNC_INTERVAL) return;
		if (connectionManager.getPeers().isEmpty()) return;

		try {
			Message message = createRootOnlyMessage(value);
			connectionManager.broadcast(message);
			lastRootSyncTime = currentTime;
			rootSyncCount++;
			log.debug("Sent root sync ({} bytes)", message.getMessageData().count());
		} catch (Exception e) {
			log.warn("Error during root sync broadcast", e);
		}
	}

	/**
	 * Creates a root-only LATTICE_VALUE message containing only the top cell.
	 */
	private Message createRootOnlyMessage(ACell value) {
		Blob rootData = value.getEncoding();
		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, value);
		return Message.create(MessageType.LATTICE_VALUE, payload, rootData);
	}

	// ========== Persistence ==========

	/**
	 * Persists the current value if sufficient time has elapsed.
	 * Called after broadcast so announced cells deduplicate.
	 */
	private void maybePersist(ACell value, long currentTime) {
		if (value == null) return;
		if (persistInterval <= 0) return;
		if (!store.isPersistent()) return;
		if (currentTime < lastPersistTime + persistInterval) return;

		persist(value);
		lastPersistTime = currentTime;
	}

	/**
	 * Persists a value to the store's root data.
	 *
	 * @param value The value to persist
	 */
	void persist(ACell value) {
		if (value == null) return;
		if (persistInterval <= 0) return;
		if (!store.isPersistent()) return;

		try {
			Cells.persist(value, store);
			store.setRootData(value);
			log.debug("Persisted lattice snapshot to store");
		} catch (IOException e) {
			log.warn("Error persisting lattice snapshot", e);
		}
	}

	// ========== Accessors ==========

	public boolean isRunning() { return running; }
	public long getBroadcastCount() { return broadcastCount; }
	public ACell getLastAnnouncedValue() { return lastAnnouncedValue; }
	public long getLastBroadcastTime() { return lastBroadcastTime; }
	public long getLastRootSyncTime() { return lastRootSyncTime; }
	public long getRootSyncCount() { return rootSyncCount; }
}
