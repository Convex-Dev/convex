package convex.node;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
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
import convex.core.store.Stores;
import convex.core.util.LatestUpdateQueue;
import convex.core.util.Utils;

/**
 * Component class to handle automatic propagation of lattice value updates to peer nodes.
 *
 * This class implements an event-driven background thread that:
 * 1. Waits for local merge notifications via a blocking queue
 * 2. Immediately sends delta broadcasts when merges occur
 * 3. After sending, checks if more merges occurred and sends again
 * 4. Detects novel data using store announcement mechanism
 * 5. Creates efficient delta-encoded broadcast messages
 * 6. Broadcasts updates to all connected peer nodes
 *
 * The propagator uses the same delta encoding strategy as BeliefPropagator,
 * ensuring only new data (not already announced to the store) is transmitted
 * to peers, minimizing network bandwidth.
 *
 * Flow:
 * - Local merge happens in NodeServer
 * - NodeServer calls triggerBroadcast()
 * - If not already broadcasting, immediately sends delta
 * - After send completes, checks if more merges occurred
 * - If yes, sends another delta; if no, waits for next trigger
 *
 * @param <V> The type of lattice values being propagated
 */
public class LatticePropagator<V extends ACell> implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LatticePropagator.class.getName());

	/**
	 * Minimum delay between successive broadcasts to avoid flooding (milliseconds)
	 */
	public static final long MIN_BROADCAST_DELAY = 50L;

	/**
	 * Interval between root-only sync broadcasts (milliseconds)
	 * This provides a lightweight periodic sync mechanism for divergence detection
	 */
	public static final long ROOT_SYNC_INTERVAL = 30_000L;

	/**
	 * Sentinel value used to signal broadcast trigger in the queue
	 */
	private static final Object BROADCAST_TRIGGER = new Object();

	/**
	 * The NodeServer instance this propagator is associated with
	 */
	private final NodeServer<V> nodeServer;

	/**
	 * Store instance for detecting novelty
	 */
	private final AStore store;

	/**
	 * Background propagation thread
	 */
	private Thread propagationThread;

	/**
	 * Flag indicating if the propagator is running
	 */
	private volatile boolean running = false;

	/**
	 * Queue for receiving broadcast trigger notifications.
	 * Uses LatestUpdateQueue which only stores the most recent trigger,
	 * avoiding redundant broadcasts when multiple merges occur rapidly.
	 */
	private final BlockingQueue<Object> triggerQueue = new LatestUpdateQueue<>();

	/**
	 * Last value that was announced to the store
	 */
	private V lastAnnouncedValue;

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
	 * Creates a new LatticePropagator for the given NodeServer.
	 *
	 * @param nodeServer The NodeServer to propagate values for
	 * @param store The store for detecting novelty
	 */
	public LatticePropagator(NodeServer<V> nodeServer, AStore store) {
		if (nodeServer == null) {
			throw new IllegalArgumentException("NodeServer cannot be null");
		}
		if (store == null) {
			throw new IllegalArgumentException("Store cannot be null");
		}

		this.nodeServer = nodeServer;
		this.store = store;
		this.lastAnnouncedValue = null;
	}

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
		lastBroadcastTime = 0L;
		lastRootSyncTime = 0L;

		propagationThread = new Thread(this::propagationLoop,
			"Lattice propagator thread for " + nodeServer.getHostAddress());
		propagationThread.setDaemon(true);
		propagationThread.start();

		log.debug("LatticePropagator started for NodeServer on port {}", nodeServer.getPort());
	}

	/**
	 * Main propagation loop that runs in the background thread.
	 *
	 * This implements an event-driven model:
	 * 1. Wait for broadcast trigger (blocking)
	 * 2. When triggered, immediately send delta
	 * 3. After send completes, check if another trigger arrived
	 * 4. If yes, send again; if no, wait for next trigger
	 * 5. Periodically check for root sync
	 */
	private void propagationLoop() {
		AStore savedStore = Stores.current();
		try {
			Stores.setCurrent(store);

			while (running && !Thread.currentThread().isInterrupted()) {
				try {
					// Wait for broadcast trigger (with timeout for root sync checks)
					Object trigger = triggerQueue.poll(ROOT_SYNC_INTERVAL, TimeUnit.MILLISECONDS);

					// Get current lattice value
					V currentValue = nodeServer.getLocalValue();
					long currentTime = Utils.getCurrentTimestamp();

					// If triggered or value changed, send delta
					if (trigger != null || hasValueChanged(currentValue)) {
						sendDeltaIfReady(currentValue, currentTime);
					}

					// Periodic root-only sync for divergence detection
					maybePerformRootSync(currentValue, currentTime);

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.debug("LatticePropagator interrupted");
					break;
				} catch (Exception e) {
					log.warn("Unexpected error in propagation loop", e);
					// Continue running despite error
				}
			}
		} finally {
			Stores.setCurrent(savedStore);
			log.debug("LatticePropagator stopped");
		}
	}

	/**
	 * Checks if the current value has changed from the last announced value.
	 *
	 * @param currentValue Current lattice value
	 * @return true if value changed, false otherwise
	 */
	private boolean hasValueChanged(V currentValue) {
		return currentValue != lastAnnouncedValue && currentValue != null;
	}

	/**
	 * Sends a delta broadcast if ready (respects minimum delay).
	 *
	 * This method:
	 * 1. Checks minimum delay constraint
	 * 2. Sets broadcasting flag
	 * 3. Sends delta
	 * 4. After send, checks if more triggers arrived and loops if needed
	 * 5. Clears broadcasting flag
	 *
	 * @param value Current lattice value
	 * @param currentTime Current timestamp
	 */
	private void sendDeltaIfReady(V value, long currentTime) {
		if (value == null) {
			return;
		}

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

				// After broadcast, check if value changed again
				value = nodeServer.getLocalValue();
				currentTime = Utils.getCurrentTimestamp();

				// Poll queue to consume any trigger (LatestUpdateQueue only stores 1 item)
				triggerQueue.poll();

				// If value changed, continue broadcasting
				if (!hasValueChanged(value)) {
					break; // No more changes, exit loop
				}

				log.debug("Value changed during broadcast, sending another delta");

			} catch (IOException e) {
				log.warn("Error during lattice broadcast", e);
				break;
			}
		} while (running && !Thread.currentThread().isInterrupted());

	}

	/**
	 * Broadcasts the given lattice value to all connected peers using delta encoding.
	 *
	 * This method:
	 * 1. Collects novel cells using store announcement
	 * 2. Encodes the delta (new cells only)
	 * 3. Creates a LATTICE_VALUE message
	 * 4. Sends to all connected peers
	 *
	 * @param value The lattice value to broadcast
	 * @throws IOException If an IO error occurs during announcement or encoding
	 */
	private void broadcast(V value) throws IOException {
		Set<Convex> peers = nodeServer.getPeerNodes();
		if (peers.isEmpty()) {
			log.trace("No peers to broadcast to");
			return;
		}

		// Create delta-encoded message
		Message message = createLatticeUpdateMessage(value);

		// Broadcast to all connected peers
		int sentCount = 0;
		for (Convex peer : peers) {
			if (peer != null && peer.isConnected()) {
				try {
					peer.message(message);
					sentCount++;
				} catch (Exception e) {
					log.debug("Failed to broadcast to peer {}: {}",
						peer.getHostAddress(), e.getMessage());
				}
			}
		}

		if (sentCount > 0) {
			log.debug("Broadcasted lattice update to {} peers (message size: {} bytes)",
				sentCount, message.getMessageData().count());
		}
	}

	/**
	 * Creates a delta-encoded LATTICE_VALUE message for the given value.
	 *
	 * Uses the same strategy as BeliefPropagator.createFullUpdateMessage():
	 * 1. Announce the value to the store, collecting novel refs
	 * 2. Encode the novel cells as a delta
	 * 3. Create a LATTICE_VALUE message with the delta data
	 *
	 * @param value The lattice value to encode
	 * @return Message ready for broadcast
	 * @throws IOException If an IO error occurs during announcement
	 */
	private Message createLatticeUpdateMessage(V value) throws IOException {
		ArrayList<ACell> novelty = new ArrayList<>();

		// Consumer to collect novel cells
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell cell = r.getValue();
			novelty.add(cell);
		};

		// Announce to store, marking as announced and collecting novelty
		value = Cells.announce(value, noveltyHandler);

		// Ensure value is in the novelty list
		if (novelty.isEmpty() || !novelty.get(novelty.size() - 1).equals(value)) {
			novelty.add(value);
		}

		// Encode as delta
		Blob deltaData = Format.encodeDelta(novelty);

		// Create LATTICE_VALUE message payload: [:LV [] value]
		// Empty path means root
		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, value);

		// Create message with delta-encoded data
		Message message = Message.create(MessageType.LATTICE_VALUE, payload, deltaData);

		log.trace("Created lattice update message with {} novel cells, {} bytes",
			novelty.size(), deltaData.count());

		return message;
	}

	/**
	 * Performs periodic root-only sync broadcast if sufficient time has elapsed.
	 *
	 * This sends only the top cell (root) of the lattice value, allowing receivers
	 * to detect divergence and trigger pull-based acquisition of missing data via
	 * the speculative fork + acquire pattern.
	 *
	 * @param value Current lattice value
	 * @param currentTime Current timestamp
	 */
	private void maybePerformRootSync(V value, long currentTime) {
		if (value == null) {
			return;
		}

		// Check if it's time for a root sync
		if (currentTime < lastRootSyncTime + ROOT_SYNC_INTERVAL) {
			return;
		}

		Set<Convex> peers = nodeServer.getPeerNodes();
		if (peers.isEmpty()) {
			return;
		}

		try {
			// Create root-only message
			Message message = createRootOnlyMessage(value);

			// Broadcast to all connected peers
			int sentCount = 0;
			for (Convex peer : peers) {
				if (peer != null && peer.isConnected()) {
					try {
						peer.message(message);
						sentCount++;
					} catch (Exception e) {
						log.debug("Failed to send root sync to peer {}: {}",
							peer.getHostAddress(), e.getMessage());
					}
				}
			}

			if (sentCount > 0) {
				lastRootSyncTime = currentTime;
				rootSyncCount++;
				log.debug("Sent root sync to {} peers (message size: {} bytes)",
					sentCount, message.getMessageData().count());
			}
		} catch (Exception e) {
			log.warn("Error during root sync broadcast", e);
		}
	}

	/**
	 * Creates a root-only LATTICE_VALUE message containing only the top cell.
	 *
	 * This message is extremely lightweight (typically 50-200 bytes) and allows
	 * receivers to detect if they have diverged from this node's lattice state.
	 * If the receiver's merge attempt triggers MissingDataException, it will
	 * automatically acquire the missing data via the fork + acquire pattern.
	 *
	 * @param value The lattice value to encode (only root cell will be included)
	 * @return Message containing only the root cell encoding
	 */
	private Message createRootOnlyMessage(V value) {
		// Encode only the root cell itself (no children)
		Blob rootData = value.getEncoding();

		// Create LATTICE_VALUE message payload: [:LV [] value]
		AVector<ACell> emptyPath = Vectors.empty();
		AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, value);

		// Create message with root-only data
		Message message = Message.create(MessageType.LATTICE_VALUE, payload, rootData);

		log.trace("Created root-only sync message: {} bytes", rootData.count());

		return message;
	}

	/**
	 * Triggers an immediate broadcast if not already broadcasting.
	 *
	 * This method is called by NodeServer after a local merge to notify
	 * the propagator that new data is available for broadcast.
	 *
	 * Uses LatestUpdateQueue which automatically overwrites any previous
	 * trigger, ensuring only the most recent update is queued. Multiple
	 * rapid calls will result in a single broadcast of the latest state.
	 */
	public void triggerBroadcast() {
		if (!running) {
			return;
		}

		// Offer trigger to queue (always succeeds, overwrites previous)
		triggerQueue.offer(BROADCAST_TRIGGER);
	}

	/**
	 * Checks if the propagator is currently running.
	 *
	 * @return true if running, false otherwise
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * Gets the number of broadcasts sent by this propagator.
	 *
	 * @return Broadcast count
	 */
	public long getBroadcastCount() {
		return broadcastCount;
	}

	/**
	 * Gets the last value that was announced to the store.
	 *
	 * @return Last announced value, or null if none
	 */
	public V getLastAnnouncedValue() {
		return lastAnnouncedValue;
	}

	/**
	 * Gets the timestamp of the last broadcast.
	 *
	 * @return Last broadcast timestamp
	 */
	public long getLastBroadcastTime() {
		return lastBroadcastTime;
	}

	/**
	 * Gets the timestamp of the last root sync broadcast.
	 *
	 * @return Last root sync timestamp
	 */
	public long getLastRootSyncTime() {
		return lastRootSyncTime;
	}

	/**
	 * Gets the number of root sync broadcasts sent by this propagator.
	 *
	 * @return Root sync count
	 */
	public long getRootSyncCount() {
		return rootSyncCount;
	}

	/**
	 * Stops the propagator gracefully.
	 *
	 * This method:
	 * 1. Sets the running flag to false
	 * 2. Interrupts the propagation thread
	 * 3. Waits for the thread to terminate (up to 5 seconds)
	 */
	@Override
	public void close() {
		if (!running) {
			return;
		}

		log.debug("Stopping LatticePropagator");
		running = false;

		if (propagationThread != null) {
			propagationThread.interrupt();
			try {
				propagationThread.join(5000); // Wait up to 5 seconds
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
}
