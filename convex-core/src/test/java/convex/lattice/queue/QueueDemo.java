package convex.lattice.queue;

import java.io.IOException;

import convex.core.cvm.Keywords;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.Lattice;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;

/**
 * Runnable demonstration of the Lattice Queue system — Kafka-style message
 * queues built on Convex lattice technology.
 *
 * <p>Demonstrates the full hierarchy from lattice root to individual partitions:
 * {@code [:queue, <owner>, :value, <topic>, <partition>]}</p>
 *
 * <p>Shows topics, partitions, produce/consume, keyed records, offset-based
 * access, range queries, metadata, truncation, fork/sync replication,
 * benchmarks, Ed25519 signing, and Etch persistence.</p>
 *
 * Run with: mvn exec:java -pl convex-core -Dexec.mainClass=convex.lattice.queue.QueueDemo
 * Or simply run this class from your IDE.
 */
public class QueueDemo {

	private static AKeyPair keyPair;
	private static AccountKey owner;
	private static RootLatticeCursor<Index<Keyword, ACell>> root;
	private static EtchStore store;

	public static void main(String[] args) throws IOException, InterruptedException {
		System.out.println("=== Lattice Queue Demo ===\n");

		// -------------------------------------------------------
		// Setup: keypair, Etch store, lattice root
		// -------------------------------------------------------
		System.out.println("--- Setup ---");

		keyPair = AKeyPair.generate();
		owner = keyPair.getAccountKey();
		System.out.println("Generated keypair, owner = " + owner);

		store = EtchStore.createTemp("queue-demo");
		System.out.println("Created EtchStore at: " + store.getFileName());

		root = Cursors.createLattice(Lattice.ROOT);
		System.out.println("Created lattice root cursor (hierarchy: :queue -> owner -> :value -> topic -> partition)");

		// Create a standalone LatticeMQ for all demo operations
		// (no signing overhead during individual offers)
		LatticeMQ mq = LatticeMQ.create();

		// -------------------------------------------------------
		// 1. Topics and partitions
		// -------------------------------------------------------
		System.out.println("\n--- Topics and Partitions ---");

		LatticeTopic events = mq.topic("user-events");
		LatticeQueue p0 = events.partition(0);
		System.out.println("Created topic 'user-events', partition 0");

		long off0 = p0.offer(Strings.create("Hello, Convex!"));
		long off1 = p0.offer(Strings.create("Lattice queues are here"));
		long off2 = p0.offer(Strings.create("Third message"));

		System.out.println("Offered 3 records at offsets " + off0 + ", " + off1 + ", " + off2);
		System.out.println("Queue size   = " + p0.size());
		System.out.println("startOffset  = " + p0.startOffset());
		System.out.println("endOffset    = " + p0.endOffset());

		// Multiple partitions
		LatticeQueue p1 = events.partition(1);
		p1.offer(Strings.create("Partition 1 message"));
		System.out.println("Partition 0 size = " + p0.size() + ", Partition 1 size = " + p1.size());

		// Multiple topics
		mq.partition("system-logs", 0).offer(Strings.create("System started"));
		System.out.println("Also wrote to topic 'system-logs'");

		// -------------------------------------------------------
		// 2. Consume by offset
		// -------------------------------------------------------
		System.out.println("\n--- Consume by Offset ---");

		System.out.println("peek(0) = " + p0.peek(0));
		System.out.println("peek(1) = " + p0.peek(1));
		System.out.println("peek(2) = " + p0.peek(2));
		System.out.println("peek(9) = " + p0.peek(9) + "  (out of range)");

		System.out.println("peekFirst() = " + p0.peekFirst());
		System.out.println("peekLast()  = " + p0.peekLast());

		// -------------------------------------------------------
		// 3. Keyed records with headers
		// -------------------------------------------------------
		System.out.println("\n--- Keyed Records with Headers ---");

		long off3 = p0.offer(
			Strings.create("user-42"),
			Strings.create("{\"action\":\"login\",\"ip\":\"10.0.0.1\"}"),
			Maps.of(Keyword.create("content-type"), Strings.create("application/json"))
		);

		AVector<ACell> entry = p0.peekEntry(off3);
		System.out.println("Offered keyed record at offset " + off3);
		System.out.println("  key       = " + QueueEntry.getKey(entry));
		System.out.println("  value     = " + QueueEntry.getValue(entry));
		System.out.println("  timestamp = " + QueueEntry.getTimestamp(entry));
		System.out.println("  headers   = " + QueueEntry.getHeaders(entry));

		// -------------------------------------------------------
		// 4. Auto-partitioning by key hash
		// -------------------------------------------------------
		System.out.println("\n--- Auto-Partitioning ---");

		int NUM_PARTITIONS = 4;
		events.setNumPartitions(NUM_PARTITIONS);
		System.out.println("Configured topic with " + events.getNumPartitions() + " partitions");

		events.offer(Strings.create("user-alice"), Strings.create("login"));
		events.offer(Strings.create("user-bob"), Strings.create("purchase"));
		events.offer(Strings.create("user-alice"), Strings.create("logout"));

		System.out.println("Auto-partitioned 3 keyed records across " + NUM_PARTITIONS + " partitions");
		for (int i = 0; i < NUM_PARTITIONS; i++) {
			System.out.println("  partition " + i + " size = " + events.partition(i).size());
		}

		// -------------------------------------------------------
		// 5. Range queries
		// -------------------------------------------------------
		System.out.println("\n--- Range Query ---");

		AVector<ACell> values = p0.range(1, 3);
		System.out.println("range(1, 3) returned " + values.count() + " values:");
		for (long i = 0; i < values.count(); i++) {
			System.out.println("  [" + (1 + i) + "] " + values.get(i));
		}

		// -------------------------------------------------------
		// 6. Consumer loop
		// -------------------------------------------------------
		System.out.println("\n--- Consumer Loop ---");

		long consumerOffset = p0.startOffset();
		System.out.println("Consumer starts at offset " + consumerOffset);
		while (consumerOffset < p0.endOffset()) {
			ACell value = p0.peek(consumerOffset);
			System.out.println("  consumed offset " + consumerOffset + " : " + value);
			consumerOffset++;
		}
		System.out.println("Consumer caught up at offset " + consumerOffset);

		// -------------------------------------------------------
		// 7. Metadata
		// -------------------------------------------------------
		System.out.println("\n--- Metadata ---");

		p0.setMeta(Keyword.create("name"), Strings.create("events.user-activity"));
		p0.setMeta(Keyword.create("owner"), Strings.create("did:convex:venue-1"));

		System.out.println("getMeta(:name)  = " + p0.getMeta(Keyword.create("name")));
		System.out.println("getMeta(:owner) = " + p0.getMeta(Keyword.create("owner")));

		// -------------------------------------------------------
		// 8. Truncation
		// -------------------------------------------------------
		System.out.println("\n--- Truncation ---");

		System.out.println("Before: size=" + p0.size() + " startOffset=" + p0.startOffset());
		long removed = p0.truncate(2);
		System.out.println("truncate(2) removed " + removed + " records");
		System.out.println("After:  size=" + p0.size() + " startOffset=" + p0.startOffset());
		System.out.println("peek(0) = " + p0.peek(0) + "  (truncated)");
		System.out.println("peek(2) = " + p0.peek(2) + "  (still valid)");

		long off4 = p0.offer(Strings.create("Post-truncation message"));
		System.out.println("Next offer at offset " + off4);

		// -------------------------------------------------------
		// 9. Fork / Sync (replication)
		// -------------------------------------------------------
		System.out.println("\n--- Fork / Sync (Replication) ---");

		LatticeMQ primary = LatticeMQ.create();
		primary.partition("orders", 0).offer(Strings.create("record-A"));
		primary.partition("orders", 0).offer(Strings.create("record-B"));
		System.out.println("Primary has " + primary.partition("orders", 0).size() + " records");

		LatticeMQ replica = primary.fork();
		System.out.println("Forked replica from primary");

		replica.partition("orders", 0).offer(Strings.create("record-C"));
		replica.partition("orders", 0).offer(Strings.create("record-D"));
		System.out.println("Replica appended 2 more (size=" + replica.partition("orders", 0).size() + ")");
		System.out.println("Primary still has " + primary.partition("orders", 0).size() + " records");

		replica.sync();
		System.out.println("After sync, primary size = " + primary.partition("orders", 0).size());

		// -------------------------------------------------------
		// 10. Signing and Etch persistence
		// -------------------------------------------------------
		System.out.println("\n--- Signing + Etch Persistence ---");

		syncToStore(mq);
		System.out.println("Signed queue state with " + owner);
		System.out.println("Persisted to Etch: " + store.getFileName());
		System.out.println("Root hash: " + store.getRootHash());
		System.out.println("Root size: " + root.get().getMemorySize() + " bytes");

		// -------------------------------------------------------
		// 11. Benchmarks
		// -------------------------------------------------------
		System.out.println("\n--- Benchmarks ---");

		int N = 100_000;

		// Offer benchmark
		LatticeMQ bench = LatticeMQ.create();
		LatticeQueue benchQ = bench.partition("bench", 0);
		long t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			benchQ.offer(Strings.create("msg-" + i));
		}
		long offerNs = System.nanoTime() - t0;
		System.out.printf("OFFER x%,d : %,d ms  (%,.0f ops/sec)%n",
			N, offerNs / 1_000_000, N * 1e9 / offerNs);

		// Sync to Etch (includes signing)
		t0 = System.nanoTime();
		syncToStore(bench);
		long syncNs = System.nanoTime() - t0;
		System.out.printf("SIGN+PERSIST %,d records : %,d ms%n",
			benchQ.size(), syncNs / 1_000_000);

		// Peek benchmark (sequential read)
		t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			benchQ.peek(i);
		}
		long peekNs = System.nanoTime() - t0;
		System.out.printf("PEEK  x%,d : %,d ms  (%,.0f ops/sec)%n",
			N, peekNs / 1_000_000, N * 1e9 / peekNs);

		// Fork+Sync benchmark
		int FORKS = 10_000;
		LatticeMQ forkBench = LatticeMQ.create();
		forkBench.partition("forks", 0).offer(Strings.create("base"));
		t0 = System.nanoTime();
		for (int i = 0; i < FORKS; i++) {
			LatticeMQ f = forkBench.fork();
			f.partition("forks", 0).offer(Strings.create("f" + i));
			f.sync();
		}
		long forkNs = System.nanoTime() - t0;
		System.out.printf("FORK+SYNC x%,d : %,d ms  (%,.0f ops/sec)%n",
			FORKS, forkNs / 1_000_000, FORKS * 1e9 / forkNs);

		// Final sync to Etch
		syncToStore(forkBench);

		System.out.printf("%nFinal bench queue: %,d records%n", benchQ.size());
		System.out.println("Final Etch root hash: " + store.getRootHash());

		// -------------------------------------------------------
		// 12. Concurrent Topic Benchmark
		// -------------------------------------------------------
		System.out.println("\n--- Concurrent Topic Benchmark ---");

		int CONCURRENT_N = 100_000;
		int NUM_CONCURRENT_PARTITIONS = 4;

		LatticeMQ concurrentMQ = LatticeMQ.create();
		LatticeTopic concurrentTopic = concurrentMQ.topic("concurrent-bench");

		try (ConcurrentTopic ct = ConcurrentTopic.create(
				concurrentTopic, NUM_CONCURRENT_PARTITIONS, 4096, 256)) {
			ct.start();

			t0 = System.nanoTime();
			for (int i = 0; i < CONCURRENT_N; i++) {
				ct.offer(Strings.create("key-" + (i % 1000)), Strings.create("msg-" + i));
			}
			ct.flush();
			long ctNs = System.nanoTime() - t0;

			System.out.printf("CONCURRENT OFFER+FLUSH x%,d (%d partitions) : %,d ms  (%,.0f ops/sec)%n",
				CONCURRENT_N, NUM_CONCURRENT_PARTITIONS, ctNs / 1_000_000, CONCURRENT_N * 1e9 / ctNs);

			long totalSize = 0;
			for (int i = 0; i < NUM_CONCURRENT_PARTITIONS; i++) {
				long pSize = concurrentTopic.partition(i).size();
				System.out.printf("  partition %d size = %,d%n", i, pSize);
				totalSize += pSize;
			}
			System.out.printf("  total records   = %,d%n", totalSize);
			System.out.printf("  totalSyncs()    = %,d%n", ct.totalSyncs());
		}

		store.close();
		System.out.println("\n=== Demo Complete ===");
	}

	/**
	 * Signs the MQ topic-map data and persists the full lattice root to Etch.
	 *
	 * <p>This is where signing happens — individual offer() calls are unsigned
	 * for maximum throughput. Signing occurs only at sync/persist boundaries.</p>
	 */
	@SuppressWarnings("unchecked")
	private static void syncToStore(LatticeMQ mq) throws IOException {
		// Get the unsigned topic-map data
		ACell topicMapData = ((convex.lattice.cursor.ALatticeCursor<ACell>) mq.cursor()).get();

		// Sign with the owner's keypair (Ed25519)
		SignedData<ACell> signed = keyPair.signData(topicMapData);

		// Build the OwnerLattice map: owner -> signed data
		ACell ownerMap = Maps.of(owner, signed);

		// Merge into the lattice root under :queue
		Index<Keyword, ACell> currentRoot = root.get();
		if (currentRoot == null) currentRoot = Lattice.ROOT.zero();
		root.set(currentRoot.assoc(Keywords.QUEUE, ownerMap));

		// Persist to Etch
		store.setRootData(root.get());
		store.flush();
	}
}
