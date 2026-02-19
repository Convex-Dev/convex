package convex.lattice.queue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Represents a single topic in the lattice message queue system.
 *
 * <p>A topic contains one or more <b>partitions</b>, each an independent
 * {@link LatticeQueue} (append-only log), plus topic-level <b>metadata</b>
 * (e.g. partition count, retention policy, ownership).</p>
 *
 * <p>The topic state is a {@link TopicLattice} value: an {@code Index<Keyword, ACell>}
 * with {@code :partitions} (partition map) and {@code :meta} (metadata map).</p>
 *
 * <h2>Partitioning</h2>
 * <p>Producers can write to a specific partition or use {@link #offer(ACell, ACell)}
 * to auto-partition by key hash. The number of partitions is stored in topic
 * metadata under {@code :num-partitions} and must be set before auto-partitioning.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * LatticeTopic topic = mq.topic("user-events");
 *
 * // Configure partitions
 * topic.setNumPartitions(4);
 *
 * // Write to a specific partition
 * topic.partition(0).offer(Strings.create("event-1"));
 *
 * // Auto-partition by key hash
 * topic.offer(Strings.create("user-42"), Strings.create("login"));
 * }</pre>
 */
public class LatticeTopic {

	public static final Keyword KEY_NUM_PARTITIONS = Keyword.intern("num-partitions");

	private final ALatticeCursor<?> cursor;

	/**
	 * Wraps an existing cursor at the TopicLattice level.
	 */
	LatticeTopic(ALatticeCursor<?> cursor) {
		this.cursor = cursor;
	}

	/**
	 * Returns a partition by integer ID. The partition is created implicitly on first write.
	 *
	 * @param id Partition number (0-based, like Kafka)
	 * @return Queue for the specified partition
	 */
	public LatticeQueue partition(long id) {
		return partition(CVMLong.create(id));
	}

	/**
	 * Returns a partition by CVM key.
	 *
	 * <p>Ensures the topic state is initialised as an {@code Index} before
	 * descending, so that {@code RT.assocIn} preserves the correct type on
	 * writeback through the cursor hierarchy.</p>
	 *
	 * @param id Partition key (typically CVMLong)
	 * @return Queue for the specified partition
	 */
	@SuppressWarnings("unchecked")
	public LatticeQueue partition(ACell id) {
		// Ensure topic state is Index before descended cursors write through it.
		// RT.assocIn creates MapLeaf for null intermediaries; by initialising to
		// Index.EMPTY first, subsequent assoc() calls preserve the Index type.
		ensureInitialised();

		ALatticeCursor<AVector<ACell>> partCursor = cursor.path(TopicLattice.KEY_PARTITIONS, id);
		return new LatticeQueue(partCursor);
	}

	// ===== Metadata =====

	/**
	 * Gets a topic metadata value by key.
	 */
	@SuppressWarnings("unchecked")
	public ACell getMeta(ACell key) {
		ALatticeCursor<Index<Keyword, ACell>> c = (ALatticeCursor<Index<Keyword, ACell>>) cursor;
		Index<Keyword, ACell> state = c.get();
		AHashMap<ACell, ACell> meta = TopicLattice.getMeta(state);
		return meta.get(key);
	}

	/**
	 * Sets a topic metadata value.
	 */
	@SuppressWarnings("unchecked")
	public void setMeta(ACell key, ACell value) {
		ALatticeCursor<Index<Keyword, ACell>> c = (ALatticeCursor<Index<Keyword, ACell>>) cursor;
		c.updateAndGet(state -> {
			if (state == null) state = TopicLattice.INSTANCE.zero();
			AHashMap<ACell, ACell> meta = TopicLattice.getMeta(state);
			return state.assoc(TopicLattice.KEY_META, meta.assoc(key, value));
		});
	}

	/**
	 * Returns the configured number of partitions, or 0 if not set.
	 */
	public long getNumPartitions() {
		ACell val = getMeta(KEY_NUM_PARTITIONS);
		if (val instanceof CVMLong l) return l.longValue();
		return 0;
	}

	/**
	 * Configures the number of partitions for this topic.
	 */
	public void setNumPartitions(long n) {
		setMeta(KEY_NUM_PARTITIONS, CVMLong.create(n));
	}

	/**
	 * Produces a keyed record to an auto-selected partition.
	 *
	 * <p>The partition is chosen by hashing the key modulo the configured
	 * partition count. Records with the same key always go to the same
	 * partition, preserving per-key ordering (the same guarantee Kafka provides).</p>
	 *
	 * <p>Requires {@link #setNumPartitions(long)} to have been called first.</p>
	 *
	 * @param key Record key (used for partition selection and stored in the entry)
	 * @param value Record value
	 * @return Absolute offset assigned within the selected partition
	 * @throws IllegalStateException if partition count is not configured
	 */
	public long offer(ACell key, ACell value) {
		long numPartitions = getNumPartitions();
		if (numPartitions <= 0) {
			throw new IllegalStateException(
				"Topic has no partitions configured. Call setNumPartitions() first.");
		}
		long partId = Math.abs(key.getHash().longValue()) % numPartitions;
		return partition(partId).offer(key, value);
	}

	/**
	 * Creates a forked copy of this topic for independent operation.
	 * All partitions within this topic are forked together.
	 */
	@SuppressWarnings("unchecked")
	public LatticeTopic fork() {
		return new LatticeTopic(((ALatticeCursor<ACell>) cursor).fork());
	}

	/**
	 * Syncs this forked topic back to its parent, merging all partition changes.
	 */
	@SuppressWarnings("unchecked")
	public void sync() {
		((ALatticeCursor<ACell>) cursor).sync();
	}

	/**
	 * Returns the underlying lattice cursor.
	 */
	public ALatticeCursor<?> cursor() {
		return cursor;
	}

	/**
	 * Ensures the topic cursor is initialised to an Index so that
	 * descended cursors (which write back via RT.assocIn) preserve the type.
	 */
	@SuppressWarnings("unchecked")
	private void ensureInitialised() {
		ALatticeCursor<Index<Keyword, ACell>> c = (ALatticeCursor<Index<Keyword, ACell>>) cursor;
		c.updateAndGet(state -> {
			if (state == null) return TopicLattice.INSTANCE.zero();
			return state;
		});
	}
}
