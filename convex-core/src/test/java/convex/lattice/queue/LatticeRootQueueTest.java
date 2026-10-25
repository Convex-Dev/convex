package convex.lattice.queue;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
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
 * <p>Path in the root: {@code [:queue, <owner>]} reaches the owner's signed slot, a
 * leaf of the lattice tree. The component owning that slot navigates the unsigned
 * value with the owner lattice's value lattice: {@code [<topic>, :partitions,
 * <partition-id>]}. See design log entry 2: the value key is not navigated.</p>
 */
public class LatticeRootQueueTest {

	private static final Keyword VALUE=Keyword.create("value");

	/** The lattice of an owner's unsigned queue value, as the owning component sees it. */
	private static ALattice<?> ownerValue() {
		ALattice<?> owners=Lattice.ROOT.path(Keywords.QUEUE);
		return ((OwnerLattice<?>)owners).getValueLattice();
	}

	@Test
	public void testQueueBranchExists() {
		ALattice<?> queueLattice = Lattice.ROOT.path(Keywords.QUEUE);
		assertNotNull(queueLattice, ":queue branch should exist in ROOT");
		assertInstanceOf(OwnerLattice.class, queueLattice);
	}

	@Test
	public void testPathToOwner() {
		// :queue → OwnerLattice → path(ownerKey) → SignedLattice, a leaf of the tree
		ALattice<?> ownerLevel = Lattice.ROOT.path(Keywords.QUEUE);
		assertNotNull(ownerLevel);

		// Any owner key descends into SignedLattice
		ALattice<?> signedLevel = ownerLevel.path(Strings.create("any-owner"));
		assertNotNull(signedLevel);
		assertInstanceOf(SignedLattice.class, signedLevel);

		// No path continues inside the envelope
		assertNull(signedLevel.path(VALUE));
		assertNull(signedLevel.path(Strings.create("my-topic")));
		assertNull(Lattice.ROOT.path(Keywords.QUEUE, Strings.create("any-owner"), VALUE));
	}

	@Test
	public void testPathToTopics() {
		// Owner value → MapLattice (topics)
		ALattice<?> topicLevel = ownerValue();
		assertNotNull(topicLevel);
		assertInstanceOf(MapLattice.class, topicLevel);
	}

	@Test
	public void testPathToTopicLattice() {
		// Descend into a specific topic → TopicLattice
		ALattice<?> topicLattice = ownerValue().path(Strings.create("my-topic"));
		assertNotNull(topicLattice);
		assertInstanceOf(TopicLattice.class, topicLattice);
	}

	@Test
	public void testPathToPartitions() {
		ALattice<?> topicLattice = ownerValue().path(Strings.create("my-topic"));
		assertNotNull(topicLattice);
		assertInstanceOf(TopicLattice.class, topicLattice);

		// Descend through :partitions → MapLattice (partitions)
		ALattice<?> partitionLevel = topicLattice.path(TopicLattice.KEY_PARTITIONS);
		assertNotNull(partitionLevel);
		assertInstanceOf(MapLattice.class, partitionLevel);
	}

	@Test
	public void testPathToQueueLattice() {
		ALattice<?> partitionLevel = ownerValue().path(
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
		// Walk the full path below the owner's value using multi-key path()
		ALattice<?> leaf = ownerValue().path(
			Strings.create("events"),
			TopicLattice.KEY_PARTITIONS,
			Strings.create("0")
		);
		assertNotNull(leaf);
		assertInstanceOf(QueueLattice.class, leaf);
	}
}
