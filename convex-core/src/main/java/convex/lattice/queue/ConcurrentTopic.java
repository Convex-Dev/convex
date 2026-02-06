package convex.lattice.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.data.ACell;
import convex.core.data.AHashMap;

/**
 * Concurrent wrapper for a {@link LatticeTopic} that provides per-partition
 * worker threads with bounded inbound queues for backpressure.
 *
 * <p>Each partition has:</p>
 * <ul>
 *   <li>A <b>forked</b> LatticeTopic — independent state, zero CAS contention</li>
 *   <li>A dedicated <b>worker thread</b> — single-writer, no locks</li>
 *   <li>A bounded <b>ArrayBlockingQueue</b> — backpressure when full</li>
 * </ul>
 *
 * <p>Workers drain their inbox in batches and sync back to the primary topic
 * once per batch. The lattice merge handles composition correctly since workers
 * modify disjoint partitions.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * LatticeTopic topic = mq.topic("events");
 * try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 4096)) {
 *     ct.start();
 *     ct.offer(key, value);       // hash-based dispatch
 *     ct.offer(value, 2);         // direct to partition 2
 *     ct.flush();                 // drain + sync all workers
 *     topic.partition(0).peek(0); // read from primary
 * }
 * }</pre>
 */
public class ConcurrentTopic implements AutoCloseable {

	public static final int DEFAULT_QUEUE_CAPACITY = 4096;
	public static final int DEFAULT_BATCH_SIZE = 1000;

	private static final long POLL_TIMEOUT_MS = 100L;
	private static final long JOIN_TIMEOUT_MS = 5000L;

	private final LatticeTopic primary;
	private final int numPartitions;
	private final int queueCapacity;
	private final int batchSize;

	@SuppressWarnings("unchecked")
	private final ArrayBlockingQueue<InboundRecord>[] inboxes;
	private Worker[] workers;
	private Thread[] threads;
	private volatile boolean running = false;

	// ===== Construction =====

	@SuppressWarnings("unchecked")
	private ConcurrentTopic(LatticeTopic primary, int numPartitions, int queueCapacity, int batchSize) {
		this.primary = primary;
		this.numPartitions = numPartitions;
		this.queueCapacity = queueCapacity;
		this.batchSize = batchSize;
		this.inboxes = new ArrayBlockingQueue[numPartitions];
		for (int i = 0; i < numPartitions; i++) {
			inboxes[i] = new ArrayBlockingQueue<>(queueCapacity);
		}
	}

	/**
	 * Creates a ConcurrentTopic wrapping the given primary topic.
	 * Sets the topic's partition count metadata automatically.
	 * Call {@link #start()} to begin processing.
	 */
	public static ConcurrentTopic create(LatticeTopic primary, int numPartitions, int queueCapacity, int batchSize) {
		primary.setNumPartitions(numPartitions);
		return new ConcurrentTopic(primary, numPartitions, queueCapacity, batchSize);
	}

	public static ConcurrentTopic create(LatticeTopic primary, int numPartitions, int queueCapacity) {
		return create(primary, numPartitions, queueCapacity, DEFAULT_BATCH_SIZE);
	}

	public static ConcurrentTopic create(LatticeTopic primary, int numPartitions) {
		return create(primary, numPartitions, DEFAULT_QUEUE_CAPACITY, DEFAULT_BATCH_SIZE);
	}

	// ===== Lifecycle =====

	/**
	 * Starts worker threads. Each worker gets a forked topic cursor.
	 */
	public synchronized void start() {
		if (running) return;
		running = true;
		workers = new Worker[numPartitions];
		threads = new Thread[numPartitions];

		for (int i = 0; i < numPartitions; i++) {
			LatticeTopic fork = primary.fork();
			workers[i] = new Worker(fork, i, inboxes[i], batchSize);
			threads[i] = new Thread(workers[i], "lattice-partition-" + i);
			threads[i].setDaemon(true);
			threads[i].start();
		}
	}

	/**
	 * Flushes all workers: drains all inboxes and syncs to primary.
	 * Returns when all previously offered records are committed.
	 */
	public void flush() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(numPartitions);
		for (int i = 0; i < numPartitions; i++) {
			inboxes[i].put(InboundRecord.flush(latch));
		}
		latch.await();
	}

	/**
	 * Flushes all pending records, stops workers, and joins threads.
	 */
	@Override
	public void close() {
		if (!running) return;
		try {
			flush();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		running = false;
		for (Thread t : threads) {
			if (t != null) {
				t.interrupt();
				try {
					t.join(JOIN_TIMEOUT_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	// ===== Produce =====

	/**
	 * Offers a keyed record, dispatched to a partition by key hash.
	 * The key is also stored in the queue entry.
	 * Blocks if the target partition's inbox is full (backpressure).
	 */
	public void offer(ACell key, ACell value) throws InterruptedException {
		int partition = (int)(Math.abs(key.getHash().longValue()) % numPartitions);
		inboxes[partition].put(InboundRecord.data(key, value, null));
	}

	/**
	 * Offers a keyed record with headers, dispatched by key hash.
	 */
	public void offer(ACell key, ACell value, AHashMap<ACell, ACell> headers) throws InterruptedException {
		int partition = (int)(Math.abs(key.getHash().longValue()) % numPartitions);
		inboxes[partition].put(InboundRecord.data(key, value, headers));
	}

	/**
	 * Offers a record directly to a specific partition.
	 */
	public void offer(ACell value, int partition) throws InterruptedException {
		inboxes[partition].put(InboundRecord.data(null, value, null));
	}

	// ===== Info =====

	public LatticeTopic primary() { return primary; }
	public int getNumPartitions() { return numPartitions; }

	public int queueDepth(int partition) {
		return inboxes[partition].size();
	}

	public long totalRecords() {
		long total = 0;
		if (workers != null) {
			for (Worker w : workers) total += w.totalRecords.get();
		}
		return total;
	}

	public long totalSyncs() {
		long total = 0;
		if (workers != null) {
			for (Worker w : workers) total += w.totalSyncs.get();
		}
		return total;
	}

	// ===== InboundRecord =====

	static final class InboundRecord {
		final ACell key;
		final ACell value;
		final AHashMap<ACell, ACell> headers;
		final CountDownLatch flushLatch;

		private InboundRecord(ACell key, ACell value, AHashMap<ACell, ACell> headers, CountDownLatch latch) {
			this.key = key;
			this.value = value;
			this.headers = headers;
			this.flushLatch = latch;
		}

		static InboundRecord data(ACell key, ACell value, AHashMap<ACell, ACell> headers) {
			return new InboundRecord(key, value, headers, null);
		}

		static InboundRecord flush(CountDownLatch latch) {
			return new InboundRecord(null, null, null, latch);
		}

		boolean isFlush() { return flushLatch != null; }
	}

	// ===== Worker =====

	static final class Worker implements Runnable {
		private final LatticeTopic fork;
		private final int partitionId;
		private final ArrayBlockingQueue<InboundRecord> inbox;
		private final int batchSize;
		volatile boolean running = true;

		final AtomicLong totalRecords = new AtomicLong();
		final AtomicLong totalSyncs = new AtomicLong();

		Worker(LatticeTopic fork, int partitionId, ArrayBlockingQueue<InboundRecord> inbox, int batchSize) {
			this.fork = fork;
			this.partitionId = partitionId;
			this.inbox = inbox;
			this.batchSize = batchSize;
		}

		@Override
		public void run() {
			LatticeQueue queue = fork.partition(partitionId);
			try {
				while (running) {
					InboundRecord first = inbox.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
					if (first == null) continue;

					List<InboundRecord> batch = new ArrayList<>(batchSize);
					batch.add(first);
					inbox.drainTo(batch, batchSize - 1);

					processBatch(queue, batch);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			// Final drain on shutdown
			drainRemaining(queue);
		}

		private void processBatch(LatticeQueue queue, List<InboundRecord> batch) {
			boolean dirty = false;
			for (InboundRecord r : batch) {
				if (r.isFlush()) {
					// Sync pending data, then signal flush complete
					fork.sync();
					totalSyncs.incrementAndGet();
					dirty = false;
					r.flushLatch.countDown();
				} else {
					offerRecord(queue, r);
					dirty = true;
				}
			}
			if (dirty) {
				fork.sync();
				totalSyncs.incrementAndGet();
			}
		}

		private void drainRemaining(LatticeQueue queue) {
			List<InboundRecord> remaining = new ArrayList<>();
			inbox.drainTo(remaining);
			if (!remaining.isEmpty()) {
				processBatch(queue, remaining);
			}
		}

		private void offerRecord(LatticeQueue queue, InboundRecord r) {
			if (r.key != null) {
				queue.offer(r.key, r.value, r.headers);
			} else {
				queue.offer(r.value);
			}
			totalRecords.incrementAndGet();
		}
	}
}
