package convex.lattice.queue;

import java.util.Objects;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

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
public class LatticeTopic extends ALatticeComponent<Index<Keyword, ACell>> {
	private final Supplier<CVMLong> clock;

	public static final Keyword KEY_NUM_PARTITIONS = Keyword.intern("num-partitions");


	/**
	 * Wraps an existing cursor at the TopicLattice level.
	 */
	LatticeTopic(AForkableCursor<Index<Keyword, ACell>> cursor) {
		this(cursor,Utils::getCurrentTimestampCell);
	}

	/** Binds the mutation clock retained by children and forks. */
	public LatticeTopic(AForkableCursor<Index<Keyword, ACell>> cursor,Supplier<CVMLong> clock) {
		super(cursor);
		this.clock=Objects.requireNonNull(clock);
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
	 * <p>The component write functions construct missing topic and partition
	 * containers atomically on the first edit. Obtaining a view changes no state.</p>
	 *
	 * @param id Partition key (typically CVMLong)
	 * @return Queue for the specified partition
	 */
	public LatticeQueue partition(ACell id) {
		AForkableCursor<AVector<ACell>> partCursor = cursor().withDefault(Index.none()).<AHashMap<ACell,AVector<ACell>>>path(TopicLattice.KEY_PARTITIONS)
			.withDefault(Maps.empty()).path(id);
		return new LatticeQueue(partCursor,clock);
	}

	// ===== Metadata =====

	/**
	 * Gets a topic metadata value by key.
	 */
	@SuppressWarnings("unchecked")
	public ACell getMeta(ACell key) {
		AForkableCursor<Index<Keyword, ACell>> c = cursor();
		Index<Keyword, ACell> state = c.get();
		AHashMap<ACell, ACell> meta = TopicLattice.getMeta(state);
		return meta.get(key);
	}

	/**
	 * Sets a topic metadata value.
	 */
	@SuppressWarnings("unchecked")
	public void setMeta(ACell key, ACell value) {
		AForkableCursor<Index<Keyword, ACell>> c = cursor();
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
		// #561: floorMod, not abs()%: Math.abs(Long.MIN_VALUE) is negative, which would yield
		// a negative partition id (out-of-range index). floorMod is always in [0, numPartitions).
		long partId = Math.floorMod(key.getHash().longValue(), numPartitions);
		return partition(partId).offer(key, value);
	}

	/**
	 * Creates a forked copy of this topic for independent operation.
	 * All partitions within this topic are forked together.
	 */
	@SuppressWarnings("unchecked")
	public LatticeTopic fork() {
		return rebind(storage.fork());
	}

	@Override
	protected LatticeTopic rebind(AForkableCursor<Index<Keyword, ACell>> cursor) {
		return new LatticeTopic(cursor,clock);
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected Index<Keyword, ACell> mergePublication(Index<Keyword, ACell> own,Index<Keyword, ACell> other) {
		return TopicLattice.INSTANCE.merge(own,other);
	}
}
