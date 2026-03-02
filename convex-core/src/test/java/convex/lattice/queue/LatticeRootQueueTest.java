package convex.lattice.queue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Tests that the queue system is correctly wired into the global Lattice.ROOT.
 *
 * <p>Full path: {@code [:queue, <owner>, :value, <topic>, :partitions, <partition-id>]}</p>
 */
public class LatticeRootQueueTest {

	@Test
	public void testQueueBranchExists() {
		ALattice<?> queueLattice = Lattice.ROOT.path(Keywords.QUEUE);
		assertNotNull(queueLattice, ":queue branch should exist in ROOT");
		assertInstanceOf(OwnerLattice.class, queueLattice);
	}

	@Test
	public void testPathToOwner() {
		// :queue → OwnerLattice → path(ownerKey) → SignedLattice
		ALattice<?> ownerLevel = Lattice.ROOT.path(Keywords.QUEUE);
		assertNotNull(ownerLevel);

		// Any owner key descends into SignedLattice
		ALattice<?> signedLevel = ownerLevel.path(Strings.create("any-owner"));
		assertNotNull(signedLevel);
		assertInstanceOf(SignedLattice.class, signedLevel);
	}

	@Test
	public void testPathToTopics() {
		// :queue → OwnerLattice → SignedLattice → path(:value) → MapLattice (topics)
		ALattice<?> signedLevel = Lattice.ROOT.path(Keywords.QUEUE, Strings.create("owner"));
		assertNotNull(signedLevel);

		Keyword VALUE = Keyword.create("value");
		ALattice<?> topicLevel = signedLevel.path(VALUE);
		assertNotNull(topicLevel);
		assertInstanceOf(MapLattice.class, topicLevel);
	}

	@Test
	public void testPathToTopicLattice() {
		// Full path through topics to TopicLattice level
		Keyword VALUE = Keyword.create("value");
		ALattice<?> topicLevel = Lattice.ROOT.path(
			Keywords.QUEUE,
			Strings.create("owner"),
			VALUE
		);
		assertNotNull(topicLevel);

		// Descend into a specific topic → TopicLattice
		ALattice<?> topicLattice = topicLevel.path(Strings.create("my-topic"));
		assertNotNull(topicLattice);
		assertInstanceOf(TopicLattice.class, topicLattice);
	}

	@Test
	public void testPathToPartitions() {
		// Full path through topic to partition map
		Keyword VALUE = Keyword.create("value");
		ALattice<?> topicLattice = Lattice.ROOT.path(
			Keywords.QUEUE,
			Strings.create("owner"),
			VALUE,
			Strings.create("my-topic")
		);
		assertNotNull(topicLattice);
		assertInstanceOf(TopicLattice.class, topicLattice);

		// Descend through :partitions → MapLattice (partitions)
		ALattice<?> partitionLevel = topicLattice.path(TopicLattice.KEY_PARTITIONS);
		assertNotNull(partitionLevel);
		assertInstanceOf(MapLattice.class, partitionLevel);
	}

	@Test
	public void testPathToQueueLattice() {
		// Full path down to the partition MapLattice
		Keyword VALUE = Keyword.create("value");
		ALattice<?> partitionLevel = Lattice.ROOT.path(
			Keywords.QUEUE,
			Strings.create("owner"),
			VALUE,
			Strings.create("my-topic"),
			TopicLattice.KEY_PARTITIONS
		);
		assertNotNull(partitionLevel);

		// Descend into a specific partition → QueueLattice
		ALattice<?> queueLevel = partitionLevel.path(Strings.create("0"));
		assertNotNull(queueLevel);
		assertInstanceOf(QueueLattice.class, queueLevel);
	}

	@Test
	public void testFullPathTraversal() {
		// Walk the full path using multi-key path()
		Keyword VALUE = Keyword.create("value");
		ALattice<?> leaf = Lattice.ROOT.path(
			Keywords.QUEUE,
			Strings.create("owner"),
			VALUE,
			Strings.create("events"),
			TopicLattice.KEY_PARTITIONS,
			Strings.create("0")
		);
		assertNotNull(leaf);
		assertInstanceOf(QueueLattice.class, leaf);
	}
}
