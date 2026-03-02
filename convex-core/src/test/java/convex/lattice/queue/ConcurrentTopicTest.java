package convex.lattice.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import convex.core.data.Strings;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class ConcurrentTopicTest {

	@Test
	public void testOfferAndFlush() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 1024)) {
			ct.start();
			for (int i = 0; i < 100; i++) {
				ct.offer(Strings.create("key-" + i), Strings.create("val-" + i));
			}
			ct.flush();

			long total = 0;
			for (int p = 0; p < 4; p++) {
				total += topic.partition(p).size();
			}
			assertEquals(100L, total);
		}
	}

	@Test
	public void testDirectPartitionOffer() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 1024)) {
			ct.start();
			ct.offer(Strings.create("msg-0"), 0);
			ct.offer(Strings.create("msg-1"), 0);
			ct.offer(Strings.create("msg-2"), 2);
			ct.flush();

			assertEquals(2L, topic.partition(0).size());
			assertEquals(0L, topic.partition(1).size());
			assertEquals(1L, topic.partition(2).size());
		}
	}

	@Test
	public void testKeyAffinity() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 1024)) {
			ct.start();

			// Same key should always go to the same partition
			for (int i = 0; i < 10; i++) {
				ct.offer(Strings.create("same-key"), Strings.create("val-" + i));
			}
			ct.flush();

			// Exactly one partition should have all 10 records
			int nonEmpty = 0;
			for (int p = 0; p < 4; p++) {
				long size = topic.partition(p).size();
				if (size > 0) {
					assertEquals(10L, size);
					nonEmpty++;
				}
			}
			assertEquals(1, nonEmpty);
		}
	}

	@Test
	public void testConcurrentProducers() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 4096)) {
			ct.start();

			int THREADS = 4;
			int PER_THREAD = 1000;
			List<Thread> producers = new ArrayList<>();

			for (int t = 0; t < THREADS; t++) {
				final int threadId = t;
				Thread producer = new Thread(() -> {
					try {
						for (int i = 0; i < PER_THREAD; i++) {
							ct.offer(
								Strings.create("t" + threadId + "-k" + i),
								Strings.create("v" + i)
							);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				producers.add(producer);
				producer.start();
			}

			for (Thread p : producers) p.join();
			ct.flush();

			long total = 0;
			for (int p = 0; p < 4; p++) {
				total += topic.partition(p).size();
			}
			assertEquals((long) THREADS * PER_THREAD, total);
		}
	}

	@Test
	public void testFlushGuarantee() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 2, 1024)) {
			ct.start();

			ct.offer(Strings.create("k1"), Strings.create("v1"));
			ct.offer(Strings.create("k2"), Strings.create("v2"));
			ct.flush();

			// Records must be visible in primary immediately after flush
			long total = topic.partition(0).size() + topic.partition(1).size();
			assertEquals(2L, total);

			// Offer more and flush again
			ct.offer(Strings.create("k3"), Strings.create("v3"));
			ct.flush();
			total = topic.partition(0).size() + topic.partition(1).size();
			assertEquals(3L, total);
		}
	}

	@Test
	public void testEmptyFlush() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 1024)) {
			ct.start();
			ct.flush(); // Should return immediately, no error
		}
	}

	@Test
	public void testMultipleFlushCycles() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 2, 1024)) {
			ct.start();

			// Cycle 1
			for (int i = 0; i < 50; i++) {
				ct.offer(Strings.create("k" + i), Strings.create("v" + i));
			}
			ct.flush();
			assertEquals(50L, ct.totalRecords());

			// Cycle 2
			for (int i = 50; i < 100; i++) {
				ct.offer(Strings.create("k" + i), Strings.create("v" + i));
			}
			ct.flush();
			assertEquals(100L, ct.totalRecords());
			assertTrue(ct.totalSyncs() >= 2); // At least one sync per cycle
		}
	}

	@Test
	public void testCloseFlushes() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 1024);
		ct.start();
		for (int i = 0; i < 50; i++) {
			ct.offer(Strings.create("key-" + i), Strings.create("val-" + i));
		}
		ct.close(); // No explicit flush — close should handle it

		long total = 0;
		for (int p = 0; p < 4; p++) {
			total += topic.partition(p).size();
		}
		assertEquals(50L, total);
	}

	@Test
	public void testStats() throws Exception {
		LatticeMQ mq = LatticeMQ.create();
		LatticeTopic topic = mq.topic("events");

		try (ConcurrentTopic ct = ConcurrentTopic.create(topic, 4, 1024)) {
			ct.start();
			assertEquals(0L, ct.totalRecords());
			assertEquals(0L, ct.totalSyncs());

			for (int i = 0; i < 20; i++) {
				ct.offer(Strings.create("k" + i), Strings.create("v" + i));
			}
			ct.flush();

			assertEquals(20L, ct.totalRecords());
			assertTrue(ct.totalSyncs() >= 1);
			assertEquals(4, ct.getNumPartitions());
		}
	}
}
