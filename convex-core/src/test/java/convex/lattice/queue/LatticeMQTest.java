package convex.lattice.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Strings;

public class LatticeMQTest {

	// ===== Basic Navigation =====

	@Test
	public void testCreateAndAccess() {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");
		assertNotNull(topic);
		LatticeQueue q = topic.partition(0);
		assertNotNull(q);
		assertTrue(q.isEmpty());
	}

	@Test
	public void testDirectPartitionAccess() {
		LatticeMQ mq = LatticeMQ.create();
		LatticeQueue q = mq.partition("events", 0);
		assertNotNull(q);
		assertTrue(q.isEmpty());
	}

	@Test
	public void testOfferAndPeek() {
		LatticeMQ mq = LatticeMQ.create();
		LatticeQueue q = mq.partition("events", 0);

		long off = q.offer(Strings.create("hello"));
		assertEquals(0L, off);
		assertEquals(Strings.create("hello"), q.peek(0));
	}

	// ===== Multi-Topic =====

	@Test
	public void testMultipleTopicsIndependent() {
		LatticeMQ mq = LatticeMQ.create();

		LatticeQueue q1 = mq.partition("topic-A", 0);
		LatticeQueue q2 = mq.partition("topic-B", 0);

		q1.offer(Strings.create("msg-A"));
		q2.offer(Strings.create("msg-B1"));
		q2.offer(Strings.create("msg-B2"));

		assertEquals(1L, q1.size());
		assertEquals(2L, q2.size());

		// Re-access through the MQ and verify data persists
		assertEquals(Strings.create("msg-A"), mq.partition("topic-A", 0).peek(0));
		assertEquals(Strings.create("msg-B2"), mq.partition("topic-B", 0).peek(1));
	}

	// ===== Multi-Partition =====

	@Test
	public void testMultiplePartitionsIndependent() {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("orders");

		LatticeQueue p0 = topic.partition(0);
		LatticeQueue p1 = topic.partition(1);
		LatticeQueue p2 = topic.partition(2);

		p0.offer(Strings.create("order-p0"));
		p1.offer(Strings.create("order-p1a"));
		p1.offer(Strings.create("order-p1b"));
		p2.offer(Strings.create("order-p2"));

		assertEquals(1L, p0.size());
		assertEquals(2L, p1.size());
		assertEquals(1L, p2.size());

		// Re-access through the topic
		assertEquals(Strings.create("order-p1b"), mq.topic("orders").partition(1).peek(1));
	}

	// ===== Auto-Partitioning =====

	@Test
	public void testAutoPartitioning() {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		// Configure partition count via topic metadata
		int N = 4;
		topic.setNumPartitions(N);
		assertEquals(N, topic.getNumPartitions());

		// Offer with key-based auto-partitioning
		topic.offer(Strings.create("key-A"), Strings.create("val-A"));
		topic.offer(Strings.create("key-B"), Strings.create("val-B"));
		topic.offer(Strings.create("key-C"), Strings.create("val-C"));

		// Verify: same key always goes to same partition
		long partA1 = Math.abs(Strings.create("key-A").getHash().longValue()) % N;
		topic.offer(Strings.create("key-A"), Strings.create("val-A2"));

		LatticeQueue partQ = topic.partition(partA1);
		assertTrue(partQ.size() >= 2); // at least key-A and key-A2
	}

	@Test
	public void testAutoPartitioningRequiresConfig() {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		// Should throw when no partition count is configured
		assertThrows(IllegalStateException.class, () -> {
			topic.offer(Strings.create("key"), Strings.create("value"));
		});
	}

	@Test
	public void testTopicMetadata() {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		// Set and read topic metadata
		topic.setMeta(
			convex.core.data.Keyword.create("owner"),
			Strings.create("did:convex:venue-1")
		);
		assertEquals(
			Strings.create("did:convex:venue-1"),
			topic.getMeta(convex.core.data.Keyword.create("owner"))
		);

		// Metadata persists alongside partition data
		topic.partition(0).offer(Strings.create("msg"));
		assertEquals(1L, topic.partition(0).size());
		assertEquals(
			Strings.create("did:convex:venue-1"),
			topic.getMeta(convex.core.data.Keyword.create("owner"))
		);
	}

	// ===== Fork / Sync =====

	@Test
	public void testForkSyncAtMQLevel() {
		LatticeMQ primary = LatticeMQ.create();
		primary.partition("events", 0).offer(Strings.create("original"));

		LatticeMQ replica = primary.fork();
		replica.partition("events", 0).offer(Strings.create("from-replica"));
		replica.partition("events", 1).offer(Strings.create("new-partition"));

		// Primary doesn't see replica changes yet
		assertEquals(1L, primary.partition("events", 0).size());

		replica.sync();

		// After sync, primary sees all changes
		assertEquals(2L, primary.partition("events", 0).size());
		assertEquals(Strings.create("from-replica"), primary.partition("events", 0).peek(1));
		assertEquals(1L, primary.partition("events", 1).size());
	}

	@Test
	public void testForkSyncAtTopicLevel() {
		LatticeMQ mq = LatticeMQ.create();
		mq.partition("events", 0).offer(Strings.create("base"));

		LatticeTopic primary = mq.topic("events");
		LatticeTopic fork = primary.fork();

		fork.partition(0).offer(Strings.create("forked"));
		fork.partition(1).offer(Strings.create("new-part"));

		// Primary doesn't see changes yet
		assertEquals(1L, primary.partition(0).size());

		fork.sync();

		// After sync, primary sees changes
		assertEquals(2L, primary.partition(0).size());
		assertEquals(1L, primary.partition(1).size());
	}

	// ===== Cursor Access =====

	@Test
	public void testCursorAccess() {
		LatticeMQ mq = LatticeMQ.create();
		assertNotNull(mq.cursor());

		LatticeTopic topic = mq.topic("t");
		assertNotNull(topic.cursor());
	}
}
