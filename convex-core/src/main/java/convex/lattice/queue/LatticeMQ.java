package convex.lattice.queue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import convex.lattice.generic.MapLattice;

/**
 * A Kafka-style distributed message queue system built on Convex lattice technology.
 *
 * <p>Provides a two-level hierarchy of <b>topics</b> and <b>partitions</b>. Each topic
 * contains one or more partitions, and each partition is an independent append-only log
 * ({@link LatticeQueue}).</p>
 *
 * <p>In the full lattice hierarchy, the path from root is:
 * {@code [:queue, <owner>, :value, <topic-name>, <partition-id>]}</p>
 *
 * <h2>Lattice Hierarchy</h2>
 * <pre>
 * LatticeMQ (topics map)
 *   └─ LatticeTopic (partitions map)
 *        └─ LatticeQueue (single partition — append-only log)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * LatticeMQ mq = LatticeMQ.create();
 *
 * // Access a topic and partition
 * LatticeQueue q = mq.topic("user-events").partition(0);
 * q.offer(Strings.create("event data"));
 *
 * // Or directly
 * mq.partition("user-events", 0).offer(Strings.create("event data"));
 * }</pre>
 */
public class LatticeMQ extends ALatticeComponent<AHashMap<ACell, Index<Keyword, ACell>>> {

	/**
	 * The lattice definition for the topic map level:
	 * MapLattice(topic-name → TopicLattice(partitions + metadata))
	 */
	static final MapLattice<ACell, Index<Keyword, ACell>> TOPIC_MAP =
		MapLattice.create(TopicLattice.INSTANCE);


	/**
	 * Wraps an existing cursor at the topic-map level.
	 */
	public LatticeMQ(AForkableCursor<AHashMap<ACell, Index<Keyword, ACell>>> cursor) {
		super(cursor);
	}

	/**
	 * Creates a new standalone message queue system.
	 *
	 * <p>For use within the full lattice hierarchy, obtain a cursor descended to
	 * {@code [:queue, <owner>, :value]} and pass it to the constructor instead.</p>
	 */
	public static LatticeMQ create() {
		return new LatticeMQ(Cursors.root(TOPIC_MAP.zero()));
	}

	/**
	 * Returns a topic by name. The topic is created implicitly on first write.
	 *
	 * @param name Topic name
	 * @return Topic handle
	 */
	public LatticeTopic topic(String name) {
		return topic(Strings.create(name));
	}

	/**
	 * Returns a topic by CVM key. The topic is created implicitly on first write.
	 *
	 * @param name Topic key (typically AString)
	 * @return Topic handle
	 */
	public LatticeTopic topic(ACell name) {
		return configured(new LatticeTopic(cursor().withDefault(Maps.empty()).path(name)));
	}

	/**
	 * Convenience method to access a specific partition directly.
	 *
	 * @param topic Topic name
	 * @param partition Partition number
	 * @return Queue for the specified partition
	 */
	public LatticeQueue partition(String topic, long partition) {
		return topic(topic).partition(partition);
	}

	/**
	 * Creates a forked copy of this MQ system for independent operation.
	 * All topics and partitions are forked together.
	 */
	@SuppressWarnings("unchecked")
	public LatticeMQ fork() {
		return configured(new LatticeMQ(this));
	}

	private LatticeMQ(LatticeMQ source) {
		super(source);
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected AHashMap<ACell, Index<Keyword, ACell>> mergePublication(
			AHashMap<ACell, Index<Keyword, ACell>> own,
			AHashMap<ACell, Index<Keyword, ACell>> other) {
		return TOPIC_MAP.merge(getContext(),own,other);
	}
}
