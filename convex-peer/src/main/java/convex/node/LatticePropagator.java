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
import convex.core.cpos.CPoSConstants;
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
import convex.core.message.Message;
import convex.core.message.MessageTag;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.lattice.cursor.Root;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.RootLatticeCursor;

/**
 * Self-contained component for propagating lattice values.
 *
 * <p>A LatticePropagator handles the complete output pipeline for a lattice node:
 * announce to store (writes cells + tracks novelty), set root data (persistence),
 * and broadcast deltas to peers. Snapshot processing returns the store-backed
 * value to its caller; it does not mutate a cursor through a side callback.
 *
 * <p>A LatticePropagator owns:
 * <ul>
	 *   <li>An {@link AStore} — for delta tracking (announce/novelty detection),
	 *       persistence (setRootData), and scoped DATA_REQUEST resolution.</li>
 *   <li>A {@link LatticeConnectionManager} — outbound peer connections and broadcast.</li>
 *   <li>A background thread — event-driven processing loop with periodic root sync.</li>
 * </ul>
 *
 * <p>Values are pushed in via {@link #triggerBroadcast(ACell)}. Each propagator owns
 * its filter and lattice-aware working view, while NodeServer owns the authoritative
 * root cursor. For synchronous primary snapshots the caller owns installation of the
 * returned value. Pull operations only acquire store-backed values; NodeServer owns
 * authoritative merge and re-propagation through its root cursor.
 *
	 * <p>The store also scopes peer capabilities: peer connections are configured with
	 * the propagator's store, so DATA_REQUEST from peers can only resolve data that exists
	 * there. The filter governs outbound publication; independently acquired inbound cells
	 * may already exist in the store and remain an operator access-policy concern.
 *
 * <p>Designed so the peer {@code BeliefPropagator} can eventually compose or extend
 * this class. Belief is an ACell; belief broadcast uses the same delta encoding
 * ({@code Cells.announce} + {@code Format.encodeDelta}).
 */
public class LatticePropagator implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LatticePropagator.class.getName());

	/**
	 * Interval between root-only sync broadcasts (milliseconds).
	 * Provides a lightweight periodic sync mechanism for divergence detection.
	 */
	public static final long ROOT_SYNC_INTERVAL = 30_000L;

	/**
	 * Store for delta tracking (novelty detection via announce), persistence
	 * (setRootData), and peer data resolution. Missing data requests for an announced
	 * value should be routed here.
	 */
	private final AStore store;

	/**
	 * Connection manager for outbound peer connections and broadcast.
	 */
	private final LatticeConnectionManager connectionManager;

	/** Lattice used to reconcile this propagator's own subset with new snapshots. */
	private ALattice<ACell> lattice;

	/** Merge context shared with the owning NodeServer. */
	private LatticeContext mergeContext = LatticeContext.EMPTY;

	/** Projection applied before any value crosses this propagator's store boundary. */
	private LatticeFilter<ACell> filter = value -> value;

	/**
	 * This propagator's current logical view. It may contain accepted inbound values
	 * which have not yet appeared in a later projection from the authoritative root.
	 */
	private RootLatticeCursor<ACell> workingCursor;

	/** Primary publication replaces its view from the authoritative cursor. */
	private boolean primary;

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

	/** Whether snapshots publish the store root after announcement. */
	private volatile boolean persistenceEnabled = true;

	/** Maximum encoded body size for one outbound delta or DATA-ahead chunk. */
	private volatile int maxDeltaMessageSize = NodeConfig.DEFAULT_MAX_MESSAGE_SIZE;

	/** Maximum combined encoded bodies materialised for one eager delta broadcast. */
	private volatile int maxDeltaBroadcastSize = NodeConfig.DEFAULT_MAX_DELTA_BROADCAST_SIZE;

	/** Whether root publication has changed the persistent store since its last checkpoint. */
	private boolean dirty;

	/**
	 * Cursor holding the last value announced to this propagator's store.
	 *
	 * <p>This is the propagator's cached view of what it has published — the
	 * store-backed snapshot it most recently announced. LATTICE_QUERY
	 * responses are served from this cursor, so peers only see data this
	 * propagator has actually committed. NodeServer exposes this view only to
	 * connections assigned to this propagator; reverse DATA_REQUEST resolution
	 * uses the same store.
	 *
	 * <p>Each propagator owns its own announced cursor. This keeps query and data
	 * access scoped to one store. Filtering and working-view reconciliation complete
	 * before this cursor advances.
	 */
	private final Root<ACell> announcedCursor = new Root<>();

	/**
	 * Timestamp of last broadcast. Volatile for cross-thread visibility — the
	 * caller's thread (synchronous publication path) and the background propagation
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
	 * after a newer snapshot's would silently demote the published root. {@link
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

	/**
	 * Creates a propagator which owns a lattice projection and reconciles later
	 * snapshots with its current view using current/propagator state as {@code own}.
	 */
	@SuppressWarnings("unchecked")
	public <V extends ACell> LatticePropagator(AStore store, ALattice<V> lattice,
			LatticeFilter<V> filter) {
		this(store, new LatticeConnectionManager(store), lattice, filter);
	}

	/** Creates a filtered propagator with an explicitly configured connection manager. */
	@SuppressWarnings("unchecked")
	public <V extends ACell> LatticePropagator(AStore store,
			LatticeConnectionManager connectionManager, ALattice<V> lattice,
			LatticeFilter<V> filter) {
		this(store, connectionManager);
		if (lattice == null) throw new IllegalArgumentException("Lattice must not be null");
		if (filter == null) throw new IllegalArgumentException("Lattice filter must not be null");
		this.lattice = (ALattice<ACell>) lattice;
		this.filter = (LatticeFilter<ACell>) filter;
	}

	/** Configures the lattice semantics supplied by the owning node before launch. */
	@SuppressWarnings("unchecked")
	void configure(ALattice<?> lattice, LatticeContext context, boolean primary) {
		synchronized (writeLock) {
			if (running) throw new IllegalStateException("Cannot configure a running propagator");
			this.lattice = (ALattice<ACell>) lattice;
			this.mergeContext = (context != null) ? context : LatticeContext.EMPTY;
			this.primary = primary;
			if (workingCursor != null) workingCursor.setContext(this.mergeContext);
		}
	}

	// ========== Configuration ==========

	/**
	 * Enables or disables root publication. Announcement still runs when disabled
	 * because it provides delta tracking and store-backed references.
	 *
	 * @param enabled true to publish roots
	 */
	public void setPersistenceEnabled(boolean enabled) {
		this.persistenceEnabled = enabled;
	}

	/** Configures the encoded body limit for outbound delta chunks. */
	public void setMaxDeltaMessageSize(int limit) {
		if (limit<1 || limit>CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("Delta message limit must be between 1 and "
				+CPoSConstants.MAX_MESSAGE_LENGTH+": "+limit);
		}
		this.maxDeltaMessageSize=limit;
		if (maxDeltaBroadcastSize<limit) maxDeltaBroadcastSize=limit;
	}

	public int getMaxDeltaMessageSize() {
		return maxDeltaMessageSize;
	}

	/** Configures the total encoded working-set limit for one eager delta. */
	public void setMaxDeltaBroadcastSize(int limit) {
		if (limit<maxDeltaMessageSize || limit>CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("Delta broadcast limit must be between "
				+maxDeltaMessageSize+" and "+CPoSConstants.MAX_MESSAGE_LENGTH+": "+limit);
		}
		this.maxDeltaBroadcastSize=limit;
	}

	public int getMaxDeltaBroadcastSize() {
		return maxDeltaBroadcastSize;
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
	 * The restored value also becomes this propagator's working view, ready for
	 * directional reconciliation with the next primary projection.
	 *
	 * @return The restored value, or null if no persisted value exists
	 *         or the store is not persistent
	 */
	public ACell restore() {
		if (!store.isPersistent()) return null;
		try {
			ACell restored = store.getRootData();
			if (restored != null) {
				synchronized (writeLock) {
					workingCursor = Cursors.createLattice(lattice, restored, mergeContext);
				}
			}
			return restored;
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

		// Drain any values the loop did not consume. The loop's exit check
		// (running || !queue.isEmpty()) can observe running==false before the
		// final value above lands in the queue, and exit without processing it —
		// which would silently lose the last writes on a clean shutdown.
		// processSnapshot is callable from any thread (serialised by writeLock),
		// so this drain is safe even if the thread had to be abandoned after the
		// join timeout.
		ACell remaining;
		while ((remaining = triggerQueue.poll()) != null) {
			processSnapshotSafe(remaining);
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
		triggerQueue.offer(value);
	}

	/**
	 * Stages an accepted inbound value in this propagator's own working view before
	 * the authoritative root is fanned back out. Current view state is the
	 * directional merge's {@code own} value. Inbound state remains complete here;
	 * this propagator's filter applies only when the reconciled view is published.
	 *
	 * <p>No store publication occurs here. NodeServer publishes the authoritative
	 * root synchronously, then its normal fan-out causes this propagator to reconcile
	 * and announce the resulting subset.</p>
	 */
	ACell mergeInbound(ACell[] path, ACell value) {
		synchronized (writeLock) {
			if (lattice == null) return workingCursor == null ? null : workingCursor.get();
			if (workingCursor == null) {
				ACell zero = lattice.zero();
				workingCursor = Cursors.createLattice(lattice, zero, mergeContext);
			}

			// Work on a fork so a rejecting lattice leaves the working view unchanged.
			ALatticeCursor<ACell> staged = workingCursor.fork();
			staged.path(path).merge(value);
			ACell merged = staged.get();
			workingCursor.set(merged);
			return merged;
		}
	}

	// ========== Propagation Loop ==========

	/**
	 * Main propagation loop. Processes values from the trigger queue through
	 * the full output pipeline: announce, setRootData, broadcast.
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
					maybePerformRootSync(Utils.getCurrentTimestamp());
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
	 * is logged rather than propagated — the background path is best-effort.
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
	 *   <li>Publish root data — anchor for restore (if persist enabled)</li>
	 *   <li>Broadcast delta to peers (if peers exist)</li>
	 * </ol>
	 *
	 * <p>Announce always runs (for delta tracking and store-backed refs).
	 * setRootData is gated by the persistence setting. Broadcast is gated by
	 * peer existence and minimum delay. The returned value is the sole handoff
	 * back to a synchronous caller; the pull merge callback is not invoked.
	 *
	 * <p>Callable from any thread. The background propagation loop calls this
	 * for queued triggers; for synchronous publication, NodeServer's sync callback
	 * calls this directly on the caller's thread for the primary propagator.
	 * Pipelines are serialised by {@link #writeLock} — see field javadoc for
	 * the sole-writer invariant.
	 *
	 * @param value Snapshot to process (must not be null)
	 * @return The announced (store-backed) value
	 * @throws IOException If announce or root publication fails
	 */
	public ACell processSnapshot(ACell value) throws IOException {
		CompletableFuture<ACell> announceFuture;
		synchronized (writeLock) {
			// Primary input is already authoritative. A secondary instead keeps its
			// established view as own, preserving local refs and pending inbound values.
			if ((workingCursor != null) && !primary && (lattice != null)) {
				value = lattice.merge(mergeContext, workingCursor.get(), value);
			}

			// Filtering is outbound-only: pending inbound state participates in the
			// reconciliation above, then projection precedes every outbound operation.
			value = filter.filter(value);
			if (value == null) throw new IllegalArgumentException("Lattice filter returned null");

			// 1. Announce to store (writes cells, collects novelty for delta)
			boolean hasPeers=!connectionManager.getPeers().isEmpty();
			Cells.NoveltyCollector noveltyCollector=hasPeers
				?new Cells.NoveltyCollector(maxDeltaBroadcastSize):null;
			value = Cells.announce(value, noveltyCollector, store);
			if (workingCursor == null) {
				workingCursor = Cursors.createLattice(lattice, value, mergeContext);
			} else {
				workingCursor.set(value);
			}

			// 2. Set root data for restore (if persist enabled)
			if (persistenceEnabled) {
				store.setRootData(value);
				dirty = true;
			}

			// The store-backed root becomes externally visible before best-effort network
			// encoding. A failed delta must therefore still be eligible for periodic
			// root sync and must still complete the caller's publication future.
			announcedCursor.set(value);
			announceFuture = nextAnnounceFuture;
			nextAnnounceFuture = new CompletableFuture<>();

			// 3. Broadcast to peers. Background triggers are already coalesced by
			// LatestUpdateQueue; an explicitly processed snapshot must not be dropped.
			if (hasPeers) {
				try {
					broadcastDelta(value,noveltyCollector.getCells());
				} catch (RuntimeException e) {
					log.warn("Unable to encode or queue lattice delta; periodic root sync will retry",e);
				}
			}
		}
		announceFuture.complete(value);
		return value;
	}

	/** Sends one bounded delta, or DATA-ahead chunks followed by a root announcement. */
	private void broadcastDelta(ACell value, ArrayList<ACell> novelty) {
		// Embedded cells already travel inside their nearest non-embedded parent and
		// are invalid as trailing multi-cell children.
		novelty.removeIf(ACell::isEmbedded);
		if (!value.isEmbedded()
				&& (novelty.isEmpty() || !novelty.get(novelty.size() - 1).equals(value))) {
			novelty.add(value);
		}

		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE,emptyPath,value);
		Message rootMessage = Message.create(MessageType.LATTICE_VALUE, payload, payload.getEncoding());
		if (rootMessage.getMessageData().count()>maxDeltaMessageSize) {
			log.warn("Lattice root announcement exceeds delta message limit of {} bytes; root sync will recover",
				maxDeltaMessageSize);
			return;
		}
		ArrayList<ACell> delta = new ArrayList<>(novelty.size()+1);
		delta.addAll(novelty);
		delta.add(payload);

		List<Message> messages;
		if (Format.getDeltaEncodingLength(delta)<=maxDeltaMessageSize) {
			Blob deltaData=Format.encodeDelta(delta,maxDeltaMessageSize);
			messages=List.of(Message.create(MessageType.LATTICE_VALUE,payload,deltaData));
		} else {
			try {
				long dataBudget=maxDeltaBroadcastSize-rootMessage.getMessageData().count();
				messages=(dataBudget>0)
					?new ArrayList<>(Message.createDataMessages(
						novelty,maxDeltaMessageSize,dataBudget))
					:new ArrayList<>();
				messages.add(rootMessage);
			} catch (IllegalArgumentException e) {
				// A single non-embedded cell may exceed an application-selected chunk
				// limit. Announce the root only and let the receiver pull that branch.
				messages=List.of(rootMessage);
			}
		}

		var result=connectionManager.broadcastSequence(messages,rootMessage);
		if (result.dropped()>0) {
			log.debug("Dropped lattice delta for {} peer(s); root sync will recover",result.dropped());
		}
		lastBroadcastTime = Utils.getCurrentTimestamp();
		broadcastCount.incrementAndGet();
	}

	// ========== Root Sync ==========

	/**
	 * Performs periodic root-only sync broadcast for divergence detection.
	 */
	private void maybePerformRootSync(long currentTime) {
		if (currentTime < lastRootSyncTime + ROOT_SYNC_INTERVAL) return;
		if (!connectionManager.hasPropagationRoutes()) return;

		try {
			Message message = createRootSyncMessage();
			if (message == null) return;
			connectionManager.broadcast(message);
			lastRootSyncTime = currentTime;
			rootSyncCount++;
			log.debug("Sent root sync ({} bytes)", message.getMessageData().count());
		} catch (Exception e) {
			log.warn("Error during root sync broadcast", e);
		}
	}

	/**
	 * Creates a root sync for the last value successfully announced to this
	 * propagator's serving store. A triggered value is deliberately not externally
	 * visible until {@link Cells#announce(ACell, Consumer, AStore)} has completed.
	 */
	Message createRootSyncMessage() {
		ACell value = announcedCursor.get();
		if (value == null) return null;
		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE,emptyPath,value);
		// The value may be encoded as an indirect ref; DATA_REQUEST resolution is
		// safe because announcedCursor advances only after the store is populated.
		return Message.create(MessageType.LATTICE_VALUE, payload, payload.getEncoding());
	}

	// ========== Explicit Persistence ==========

	/**
	 * Explicitly persists a value to the store. Used for forced persistence
	 * (e.g. {@link NodeServer#persistSnapshot}) regardless of the automatic
	 * root-publication setting.
	 *
	 * @param value The value to persist
	 * @throws IOException If persistence or its durability barrier fails
	 */
	void persist(ACell value) throws IOException {
		if (value == null) return;
		if (!store.isPersistent()) return;
		synchronized (writeLock) {
			value = Cells.announce(value, r -> {}, store);
			store.setRootData(value);
			dirty = true;
			store.flush();
			dirty = false;
			log.debug("Persisted lattice snapshot to store");
		}
	}

	/**
	 * Completes a durability barrier when this propagator has unpublished
	 * persistent changes.
	 *
	 * @return true if a barrier was completed
	 * @throws IOException If the barrier fails
	 */
	boolean checkpoint() throws IOException {
		if (!store.isPersistent()) return false;
		synchronized (writeLock) {
			if (!dirty) return false;
			store.flush();
			dirty = false;
			return true;
		}
	}

	// ========== Pull (Fetch from Peers) ==========

	/**
	 * Pulls the latest lattice value from a specific peer into this propagator's store.
	 *
	 * <p>Sends a LATTICE_QUERY to the peer, acquires the full value tree into
	 * this propagator's store via {@link Convex#acquire}, then returns that
	 * store-backed value to the caller.
	 *
	 * <p>This method deliberately performs no merge, root publication or broadcast.
	 * Only NodeServer knows the lattice and current root, so it must merge the acquired
	 * value and call {@code cursor.sync()} before anything is re-propagated. Persisting
	 * the raw peer value here could demote the root when local state already dominates it.
	 *
	 * @param peer Convex connection to the peer node
	 * @return CompletableFuture that completes with the acquired value
	 */
	public CompletableFuture<ACell> pull(Convex peer) {
		return pullPath(peer);
	}

	/**
	 * Pulls one path of a peer's latest lattice value into this propagator's
	 * store. Path selection scopes transfer and storage work; it is not an
	 * access-control boundary.
	 *
	 * <p>The caller must not mutate {@code path} while the returned operation is
	 * outstanding.</p>
	 *
	 * @param peer Convex connection to the peer node
	 * @param path path within the peer's announced lattice value
	 * @return future completing with the acquired value, or {@code null} when absent
	 */
	public CompletableFuture<ACell> pullPath(Convex peer, ACell... path) {
		if (peer == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Peer cannot be null"));
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				if (!peer.isConnected()) {
					throw new RuntimeException("Peer is not connected");
				}

				AVector<?> queryPayload = Vectors.create(
						MessageTag.LATTICE_QUERY, null, Vectors.create(path));
				Message queryMessage = Message.create(MessageType.LATTICE_QUERY, queryPayload);

				CompletableFuture<Result> resultFuture = peer.request(queryMessage);
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

				log.debug("Acquired pulled lattice path from peer: {}", peer.getHostAddress());
				return acquired;

			} catch (Exception e) {
				log.warn("Lattice pull failed from peer: {}", peer.getHostAddress(), e);
				throw new RuntimeException("Lattice pull failed from peer", e);
			}
		},ThreadUtils.getVirtualExecutor());
	}

	/**
	 * Pulls the latest lattice value from all connected peers.
	 *
	 * <p>Sends LATTICE_QUERY to each connected peer in parallel, acquires their
	 * values into this propagator's store. The returned values remain unmerged;
	 * NodeServer integrates them through its authoritative root cursor.
	 *
	 * @return future containing all acquired values when every pull is complete
	 */
	public CompletableFuture<List<ACell>> pullAll() {
		Set<Convex> peerSet = connectionManager.getPeers();
		if (peerSet.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}

		List<CompletableFuture<ACell>> futures = new ArrayList<>();
		for (Convex peer : peerSet) {
			if (peer != null && peer.isConnected()) {
				futures.add(pull(peer));
			}
		}

		if (futures.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
			.thenApply(v -> {
				List<ACell> acquired = new ArrayList<>(futures.size());
				for (CompletableFuture<ACell> future : futures) {
					acquired.add(future.join());
				}
				return acquired;
			});
	}
}
