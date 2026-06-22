package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.AccountKey;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.exceptions.MissingDataException;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.Utils;
import convex.lattice.cursor.Root;

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
	 * (setRootData), and security boundary for peer data resolution. Missing data requests 
	 * vs announced value should be routed here.
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
	 * Cursor holding the last value announced to this propagator's store.
	 *
	 * <p>This is the propagator's cached view of what it has published — the
	 * filtered, store-backed snapshot it most recently announced. LATTICE_QUERY
	 * responses are served from this cursor, so peers only see data this
	 * propagator has actually committed (and whose cells the store can resolve
	 * via DATA_REQUEST).
	 *
	 * <p>Each propagator owns its own announced cursor; secondary propagators
	 * with filters publish a different (filtered) view from the primary, which
	 * is the security boundary for cross-propagator data segregation.
	 */
	private final Root<ACell> announcedCursor = new Root<>();

	/**
	 * Last value that was triggered (used for periodic root sync). Volatile
	 * because it may be written by the caller's thread (synchronous commit
	 * path) and read by the background propagation thread.
	 */
	private volatile ACell lastTriggeredValue;

	/**
	 * Timestamp of last broadcast. Volatile for cross-thread visibility — the
	 * caller's thread (synchronous commit path) and the background propagation
	 * thread may both read and write this.
	 */
	private volatile long lastBroadcastTime = 0L;

	/**
	 * Timestamp of last root sync broadcast (background thread only).
	 */
	private long lastRootSyncTime = 0L;

	/**
	 * Count of broadcasts sent. Atomic because both the caller's thread and
	 * the background thread may increment.
	 */
	private final java.util.concurrent.atomic.AtomicLong broadcastCount = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Count of root sync broadcasts sent
	 */
	private long rootSyncCount = 0L;

	/**
	 * Serialises all store-writing pipelines through this propagator. The
	 * propagator is the sole live writer of {@code setRootData} on its store
	 * (see {@code PERSISTENCE.md} — sole-writer invariant), and pipelines
	 * must not interleave: an older snapshot's {@code setRootData} landing
	 * after a newer snapshot's would silently demote the root pointer and
	 * break the durability promise of {@code cursor.sync()}. {@link
	 * #processSnapshot} and {@link #persist} both acquire this lock so the
	 * caller's thread (sync hook), the background propagation thread (pull,
	 * drain), and explicit persistence calls run their full pipelines
	 * sequentially.
	 */
	private final Object writeLock = new Object();

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

	// ========== Peer Management ==========

	/**
	 * Adds an outbound peer connection with known identity. The peer's store
	 * is set to this propagator's store, establishing the security boundary.
	 *
	 * @param peerKey AccountKey identifying the remote peer
	 * @param peer Convex connection to the peer node
	 */
	public void addPeer(AccountKey peerKey, Convex peer) {
		connectionManager.addPeer(peerKey, peer);
	}

	/**
	 * Removes a peer by identity, closing the connection if active.
	 *
	 * @param peerKey AccountKey of the peer to remove
	 */
	public void removePeer(AccountKey peerKey) {
		connectionManager.removePeer(peerKey);
	}

	/**
	 * Gets a snapshot of current peer connections.
	 *
	 * @return Defensive copy of the peer set
	 */
	public Set<Convex> getPeers() {
		return connectionManager.getPeers();
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
	public long getBroadcastCount() { return broadcastCount.get(); }
	public ACell getLastAnnouncedValue() { return announcedCursor.get(); }

	/**
	 * Future completing with the next value announced by this propagator.
	 *
	 * <p>Gives callers something to wait on for propagation: capture the future
	 * <em>before</em> triggering the change, then {@code get(timeout)} — no
	 * sleep-polling on {@link #getLastAnnouncedValue()} required. Each announce
	 * completes the current future and installs a fresh one, so the returned
	 * future always reflects an announce that happens after the call.
	 *
	 * @return Future for the next announced (store-backed) value
	 */
	public CompletableFuture<ACell> nextAnnounce() { return nextAnnounceFuture; }

	/**
	 * Future for the next announce. Swapped under {@link #writeLock} in
	 * {@link #processSnapshot}, completed outside it (dependent actions must
	 * not run while holding the pipeline lock).
	 */
	private volatile CompletableFuture<ACell> nextAnnounceFuture = new CompletableFuture<>();
	/**
	 * Cursor holding the last value announced by this propagator. See
	 * {@link #announcedCursor} for ownership and security semantics.
	 */
	public Root<ACell> getAnnouncedCursor() { return announcedCursor; }
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
		announcedCursor.set(null);
		lastTriggeredValue = null;
		lastBroadcastTime = 0L;
		lastRootSyncTime = 0L;
		broadcastCount.set(0L);

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
					processSnapshotSafe(value);
				}

				// Periodic root sync only while running
				if (running) {
					maybePerformRootSync(lastTriggeredValue, Utils.getCurrentTimestamp());
				}

			} catch (InterruptedException e) {
				// Drain remaining items before exiting
				ACell remaining;
				while ((remaining = triggerQueue.poll()) != null) {
					processSnapshotSafe(remaining);
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
	 * Background-thread wrapper around {@link #processSnapshot}. IOException
	 * is logged rather than propagated — the background path is best-effort
	 * (callers who need durability errors should call {@link #processSnapshot}
	 * directly).
	 */
	private void processSnapshotSafe(ACell value) {
		try {
			processSnapshot(value);
		} catch (IOException e) {
			log.warn("Error processing lattice value", e);
		}
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
	 *
	 * <p>Callable from any thread. The background propagation loop calls this
	 * for queued triggers; for synchronous commit, NodeServer's sync callback
	 * calls this directly on the caller's thread for the primary propagator.
	 * Pipelines are serialised by {@link #writeLock} — see field javadoc for
	 * the sole-writer invariant.
	 *
	 * @param value Snapshot to process (must not be null)
	 * @return The announced (store-backed) value
	 * @throws IOException If announce or setRootData fails
	 */
	public ACell processSnapshot(ACell value) throws IOException {
		CompletableFuture<ACell> announceFuture;
		synchronized (writeLock) {
			// Track latest snapshot for periodic root sync (used by background thread)
			lastTriggeredValue = value;

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
				broadcastCount.incrementAndGet();
			}

			announcedCursor.set(value);

			// Swap the announce future under the lock; complete it outside
			announceFuture = nextAnnounceFuture;
			nextAnnounceFuture = new CompletableFuture<>();
		}
		announceFuture.complete(value);
		return value;
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
		synchronized (writeLock) {
			try {
				value = Cells.announce(value, r -> {}, store);
				store.setRootData(value);
				log.debug("Persisted lattice snapshot to store");
			} catch (IOException e) {
				log.warn("Error persisting lattice snapshot", e);
			}
		}
	}

	// ========== Pull (Fetch from Peers) ==========

	/**
	 * Pulls the latest lattice value from a specific peer into this propagator's store.
	 *
	 * <p>Sends a LATTICE_QUERY to the peer, acquires the full value tree into
	 * this propagator's store via {@link Convex#acquire}, feeds the acquired value
	 * into the cursor via the merge callback, and queues it for background
	 * processing (announce + persist + broadcast to other peers).
	 *
	 * <p>The future completes after the merge callback has run (cursor is updated)
	 * but before the background broadcast to other peers.
	 *
	 * @param peer Convex connection to the peer node
	 * @return CompletableFuture that completes with the acquired value
	 */
	public CompletableFuture<ACell> pull(Convex peer) {
		if (peer == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Peer cannot be null"));
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				if (!peer.isConnected()) {
					throw new RuntimeException("Peer is not connected");
				}

				// 1. Query peer for their root lattice value
				CVMLong queryId = CVMLong.create(System.currentTimeMillis());
				AVector<?> queryPayload = Vectors.create(MessageTag.LATTICE_QUERY, queryId, Vectors.empty());
				Message queryMessage = Message.create(MessageType.LATTICE_QUERY, queryPayload);

				CompletableFuture<Result> resultFuture = peer.message(queryMessage);
				Result result = resultFuture.get(10, TimeUnit.SECONDS);

				if (result.isError()) {
					throw new RuntimeException("Pull query failed: " + result);
				}

				ACell receivedValue = result.getValue();
				if (receivedValue == null) return null;

				// 2. Store the received value locally. For small values that are
				// fully encoded in the result, announce succeeds immediately.
				// For large values with missing children, fall back to acquire.
				ACell acquired;
				try {
					acquired = Cells.announce(receivedValue, r -> {}, store);
				} catch (MissingDataException mde) {
					// Value has children not in our store — acquire full tree from peer
					Hash rootHash = Hash.get(receivedValue);
					acquired = peer.acquire(rootHash, store).get(30, TimeUnit.SECONDS);
				}

				// 3. Feed into cursor via merge callback (inline — cursor updated before future completes)
				if (mergeCallback != null) {
					mergeCallback.accept(acquired);
				}

				// 4. Queue for background processing (announce + persist + broadcast to other peers)
				triggerBroadcast(acquired);

				log.debug("Pulled value from peer: {}", peer.getHostAddress());
				return acquired;

			} catch (Exception e) {
				log.warn("Pull failed from peer: {}", peer.getHostAddress(), e);
				throw new RuntimeException("Pull failed from peer", e);
			}
		});
	}

	/**
	 * Pulls the latest lattice value from all connected peers.
	 *
	 * <p>Sends LATTICE_QUERY to each connected peer in parallel, acquires their
	 * values into this propagator's store, and merges via the merge callback.
	 *
	 * @return CompletableFuture that completes when all pulls are done
	 */
	public CompletableFuture<ACell> pull() {
		Set<Convex> peerSet = connectionManager.getPeers();
		if (peerSet.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		List<CompletableFuture<ACell>> futures = new ArrayList<>();
		for (Convex peer : peerSet) {
			if (peer != null && peer.isConnected()) {
				futures.add(pull(peer));
			}
		}

		if (futures.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
			.thenApply(v -> announcedCursor.get());
	}
}
