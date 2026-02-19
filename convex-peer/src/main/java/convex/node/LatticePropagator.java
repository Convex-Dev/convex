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
 * Self-contained component for propagating lattice values.
 *
 * <p>A LatticePropagator handles the complete output pipeline for a lattice node:
 * announce to store (writes cells + tracks novelty), set root data (persistence),
 * invoke merge callback (feed store-backed refs to cursor), and broadcast deltas
 * to peers.
 *
 * <p>A LatticePropagator owns:
 * <ul>
 *   <li>An {@link AStore} — for delta tracking (announce/novelty detection),
 *       persistence (setRootData), and security boundary (DATA_REQUEST resolution).</li>
 *   <li>A {@link LatticeConnectionManager} — outbound peer connections and broadcast.</li>
 *   <li>An optional merge callback — called after announce with the store-backed value.
 *       Set by NodeServer on the primary propagator to feed store-backed refs into
 *       the cursor via lattice merge.</li>
 *   <li>A background thread — event-driven processing loop with periodic root sync.</li>
 * </ul>
 *
 * <p>The propagator has no knowledge of cursors or lattices. Values are pushed in
 * via {@link #triggerBroadcast(ACell)}. The merge callback is a plain
 * {@code Consumer<ACell>} — NodeServer owns the merge logic.
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
	 * Store for delta tracking (novelty detection via announce), persistence
	 * (setRootData), and security boundary for peer data resolution.
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
	 * Queue for receiving lattice values to process.
	 * Uses LatestUpdateQueue which only stores the most recent value,
	 * coalescing rapid updates into a single processing of the latest state.
	 * Safe because lattice values are monotonic (V2 >= V1 implies V1 is subsumed).
	 */
	private final LatestUpdateQueue<ACell> triggerQueue = new LatestUpdateQueue<>();

	/**
	 * Merge callback — called after announce with the store-backed value.
	 * Set by NodeServer on the primary propagator to feed store-backed refs
	 * into the cursor via lattice merge.
	 *
	 * <p>The propagator has no knowledge of cursors or lattices — it just calls
	 * this Consumer with the announced value. NodeServer owns the merge logic.
	 */
	private Consumer<ACell> mergeCallback;

	/**
	 * Controls whether setRootData is called after announce.
	 * Positive = persist enabled; zero or negative = disabled.
	 * This does NOT affect announce (which always runs for delta tracking
	 * and store-backed refs).
	 */
	private long persistInterval = 30_000L;

	/**
	 * Last value that was announced to the store
	 */
	private ACell lastAnnouncedValue;

	/**
	 * Last value that was triggered (used for periodic root sync)
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

	// ========== Configuration ==========

	/**
	 * Sets the merge callback, called after announce with the store-backed value.
	 *
	 * <p>Typically set by NodeServer on the primary propagator:
	 * <pre>{@code
	 * propagator.setMergeCallback(persisted ->
	 *     cursor.updateAndGet(current -> lattice.merge(persisted, current)));
	 * }</pre>
	 *
	 * @param callback Consumer receiving the store-backed value after announce,
	 *                 or null to disable
	 */
	public void setMergeCallback(Consumer<ACell> callback) {
		this.mergeCallback = callback;
	}

	/**
	 * Sets the persist interval. Positive enables setRootData after announce;
	 * zero or negative disables it. This does NOT affect announce (which always
	 * runs for delta tracking).
	 *
	 * @param intervalMs Interval in milliseconds (0 or negative to disable)
	 */
	public void setPersistInterval(long intervalMs) {
		this.persistInterval = intervalMs;
	}

	// ========== Accessors ==========

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

	public boolean isRunning() { return running; }
	public long getBroadcastCount() { return broadcastCount; }
	public ACell getLastAnnouncedValue() { return lastAnnouncedValue; }
	public long getLastBroadcastTime() { return lastBroadcastTime; }
	public long getLastRootSyncTime() { return lastRootSyncTime; }
	public long getRootSyncCount() { return rootSyncCount; }

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

		propagationThread = new Thread(this::propagationLoop, "Lattice propagator thread");
		propagationThread.setDaemon(true);
		propagationThread.start();

		log.debug("LatticePropagator started");
	}

	/**
	 * Triggers a final value and shuts down gracefully.
	 *
	 * <p>The propagator processes any remaining queued values (including the
	 * final value if non-null) before stopping. This is the only blocking
	 * handoff in the system — used during shutdown to guarantee persistence.
	 *
	 * @param finalValue Final value to process before stopping, or null
	 */
	public void triggerAndClose(ACell finalValue) {
		if (!running && propagationThread == null) return;

		running = false;

		if (finalValue != null) {
			triggerQueue.offer(finalValue); // wakes thread via notify
		} else if (propagationThread != null) {
			propagationThread.interrupt(); // wake thread from poll wait
		}

		if (propagationThread != null) {
			try {
				propagationThread.join(10_000);
				if (propagationThread.isAlive()) {
					log.warn("LatticePropagator thread did not drain within timeout, interrupting");
					propagationThread.interrupt();
					propagationThread.join(2000);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			propagationThread = null;
		}

		log.debug("LatticePropagator closed (sent {} delta broadcasts, {} root syncs)",
			broadcastCount, rootSyncCount);
	}

	/**
	 * Stops the propagator gracefully. Equivalent to {@code triggerAndClose(null)}.
	 */
	@Override
	public void close() {
		triggerAndClose(null);
	}

	// ========== Trigger API ==========

	/**
	 * Triggers processing of the given lattice value.
	 *
	 * <p>Non-blocking: the value is queued and processed by the background thread.
	 * Uses LatestUpdateQueue which automatically coalesces rapid triggers —
	 * safe because lattice values are monotonic (V2 >= V1 implies V1 is subsumed).
	 *
	 * @param value The lattice value to process (must not be null)
	 */
	public void triggerBroadcast(ACell value) {
		if (!running) return;
		if (value == null) return;
		lastTriggeredValue = value;
		triggerQueue.offer(value);
	}

	// ========== Propagation Loop ==========

	/**
	 * Main propagation loop. Processes values from the trigger queue through
	 * the full output pipeline: announce, setRootData, mergeCallback, broadcast.
	 *
	 * <p>When {@code running} is false, switches to drain mode: processes
	 * remaining queued values without waiting, then exits.
	 */
	private void propagationLoop() {
		while (running || !triggerQueue.isEmpty()) {
			try {
				ACell value;
				if (running) {
					value = triggerQueue.poll(ROOT_SYNC_INTERVAL, TimeUnit.MILLISECONDS);
				} else {
					// Drain mode: non-blocking poll, exit when empty
					value = triggerQueue.poll();
					if (value == null) break;
				}

				if (value != null) {
					processValue(value);
				}

				// Periodic root sync only while running
				if (running) {
					maybePerformRootSync(lastTriggeredValue, Utils.getCurrentTimestamp());
				}

			} catch (InterruptedException e) {
				// Drain remaining items before exiting
				ACell remaining;
				while ((remaining = triggerQueue.poll()) != null) {
					processValue(remaining);
				}
				break;
			} catch (Exception e) {
				log.warn("Unexpected error in propagation loop", e);
				if (!running) break;
			}
		}
		log.debug("LatticePropagator loop ended");
	}

	/**
	 * Processes a single lattice value through the full output pipeline:
	 * <ol>
	 *   <li>Announce to store — writes cells, collects novelty for delta encoding</li>
	 *   <li>Set root data — anchor for restore (if persist enabled)</li>
	 *   <li>Merge callback — feed store-backed value back to cursor (if set)</li>
	 *   <li>Broadcast delta to peers (if peers exist and delay elapsed)</li>
	 * </ol>
	 *
	 * <p>Announce always runs (for delta tracking and store-backed refs).
	 * setRootData is gated by {@link #persistInterval}. The merge callback
	 * is gated by whether it was set (primary propagator only). Broadcast
	 * is gated by peer existence and minimum delay.
	 */
	private void processValue(ACell value) {
		try {
			// 1. Announce to store (writes cells, collects novelty for delta)
			ArrayList<ACell> novelty = new ArrayList<>();
			Consumer<Ref<ACell>> noveltyHandler = r -> novelty.add(r.getValue());
			value = Cells.announce(value, noveltyHandler, store);

			// 2. Set root data for restore (if persist enabled)
			if (persistInterval > 0) {
				store.setRootData(value);
			}

			// 3. Merge callback (feed store-backed value back to cursor)
			if (mergeCallback != null) {
				mergeCallback.accept(value);
			}

			// 4. Broadcast to peers (only if peers exist and delay elapsed)
			long currentTime = Utils.getCurrentTimestamp();
			if (!connectionManager.getPeers().isEmpty()
					&& currentTime >= lastBroadcastTime + MIN_BROADCAST_DELAY) {
				// Ensure root value is in the novelty list
				if (novelty.isEmpty() || !novelty.get(novelty.size() - 1).equals(value)) {
					novelty.add(value);
				}
				Blob deltaData = Format.encodeDelta(novelty);
				AVector<ACell> emptyPath = Vectors.empty();
				AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, value);
				Message message = Message.create(MessageType.LATTICE_VALUE, payload, deltaData);
				connectionManager.broadcast(message);
				lastBroadcastTime = currentTime;
				broadcastCount++;
			}

			lastAnnouncedValue = value;

		} catch (IOException e) {
			log.warn("Error processing lattice value", e);
		}
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
			Blob rootData = value.getEncoding();
			AVector<ACell> emptyPath = Vectors.empty();
			AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, value);
			Message message = Message.create(MessageType.LATTICE_VALUE, payload, rootData);
			connectionManager.broadcast(message);
			lastRootSyncTime = currentTime;
			rootSyncCount++;
			log.debug("Sent root sync ({} bytes)", rootData.count());
		} catch (Exception e) {
			log.warn("Error during root sync broadcast", e);
		}
	}

	// ========== Explicit Persistence ==========

	/**
	 * Explicitly persists a value to the store. Used for forced persistence
	 * (e.g. {@link NodeServer#persistSnapshot}) regardless of persistInterval.
	 *
	 * @param value The value to persist
	 */
	void persist(ACell value) {
		if (value == null) return;
		if (!store.isPersistent()) return;
		try {
			value = Cells.announce(value, r -> {}, store);
			store.setRootData(value);
			log.debug("Persisted lattice snapshot to store");
		} catch (IOException e) {
			log.warn("Error persisting lattice snapshot", e);
		}
	}
}
