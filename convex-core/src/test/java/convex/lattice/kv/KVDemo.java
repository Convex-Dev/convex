package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMDouble;

/**
 * Runnable demonstration of the LatticeKV key-value store.
 *
 * Shows usage of all data types: strings, hashes, sets, sorted sets, lists,
 * and counters, plus fork/sync replication and TTL expiry.
 *
 * Run with: mvn exec:java -pl convex-core -Dexec.mainClass=convex.lattice.kv.KVDemo
 * Or simply run this class from your IDE.
 */
public class KVDemo {

	public static void main(String[] args) throws Exception {
		System.out.println("=== LatticeKV Demo ===\n");

		// -------------------------------------------------------
		// 1. Create a store
		// -------------------------------------------------------
		LatticeKV db = LatticeKV.create(Strings.create("node-1"));
		System.out.println("Created LatticeKV store with replica ID 'node-1'");

		// -------------------------------------------------------
		// 2. String values (GET / SET / DEL)
		// -------------------------------------------------------
		System.out.println("\n--- String Operations ---");

		db.set("greeting", Strings.create("Hello, Convex!"));
		db.set("name", Strings.create("Alice"));

		System.out.println("GET greeting = " + db.get("greeting"));
		System.out.println("GET name     = " + db.get("name"));
		System.out.println("EXISTS name  = " + db.exists("name"));
		System.out.println("TYPE name    = " + db.type("name"));

		db.del("name");
		System.out.println("After DEL name:");
		System.out.println("  GET name    = " + db.get("name"));
		System.out.println("  EXISTS name = " + db.exists("name"));

		// -------------------------------------------------------
		// 3. Hash (field-level operations)
		// -------------------------------------------------------
		System.out.println("\n--- Hash Operations ---");

		db.hset("user:1", "name", Strings.create("Bob"));
		db.hset("user:1", "email", Strings.create("bob@example.com"));
		db.hset("user:1", "role", Strings.create("admin"));

		System.out.println("HGET user:1 name  = " + db.hget("user:1", "name"));
		System.out.println("HGET user:1 email = " + db.hget("user:1", "email"));
		System.out.println("HLEN user:1       = " + db.hlen("user:1"));
		System.out.println("HEXISTS user:1 role   = " + db.hexists("user:1", "role"));
		System.out.println("HEXISTS user:1 phone  = " + db.hexists("user:1", "phone"));

		db.hdel("user:1", "role");
		System.out.println("After HDEL role:");
		System.out.println("  HLEN user:1 = " + db.hlen("user:1"));

		Index<AString, ACell> all = db.hgetall("user:1");
		System.out.println("HGETALL user:1 = " + all);

		// -------------------------------------------------------
		// 4. Set operations
		// -------------------------------------------------------
		System.out.println("\n--- Set Operations ---");

		db.sadd("tags", Strings.create("java"), Strings.create("lattice"), Strings.create("crdt"));
		System.out.println("SADD tags java, lattice, crdt");
		System.out.println("SCARD tags      = " + db.scard("tags"));
		System.out.println("SISMEMBER java  = " + db.sismember("tags", Strings.create("java")));
		System.out.println("SISMEMBER go    = " + db.sismember("tags", Strings.create("go")));
		System.out.println("SMEMBERS tags   = " + db.smembers("tags"));

		db.srem("tags", Strings.create("crdt"));
		System.out.println("After SREM crdt:");
		System.out.println("  SCARD tags    = " + db.scard("tags"));

		// -------------------------------------------------------
		// 5. Sorted set operations
		// -------------------------------------------------------
		System.out.println("\n--- Sorted Set Operations ---");

		db.zadd("leaderboard", 100.0, Strings.create("Alice"));
		db.zadd("leaderboard", 250.0, Strings.create("Bob"));
		db.zadd("leaderboard", 175.0, Strings.create("Charlie"));

		System.out.println("ZSCORE Alice   = " + db.zscore("leaderboard", Strings.create("Alice")));
		System.out.println("ZCARD          = " + db.zcard("leaderboard"));

		AVector<ACell> top = db.zrange("leaderboard", 0, -1);
		System.out.println("ZRANGE 0 -1 (by score ascending):");
		for (long i = 0; i < top.count(); i++) {
			ACell member = top.get(i);
			CVMDouble score = db.zscore("leaderboard", member);
			System.out.println("  " + (i + 1) + ". " + member + " (score: " + score + ")");
		}

		// -------------------------------------------------------
		// 6. List operations
		// -------------------------------------------------------
		System.out.println("\n--- List Operations ---");

		db.rpush("queue", Strings.create("task-1"), Strings.create("task-2"), Strings.create("task-3"));
		System.out.println("RPUSH queue task-1, task-2, task-3");
		System.out.println("LLEN queue  = " + db.llen("queue"));
		System.out.println("LRANGE 0 -1 = " + db.lrange("queue", 0, -1));

		ACell popped = db.lpop("queue");
		System.out.println("LPOP queue  = " + popped);
		System.out.println("LLEN queue  = " + db.llen("queue"));

		// -------------------------------------------------------
		// 7. Counter operations (PN-Counter)
		// -------------------------------------------------------
		System.out.println("\n--- Counter Operations ---");

		db.incr("visits");
		db.incr("visits");
		db.incrby("visits", 10);
		System.out.println("After INCR x2 + INCRBY 10:");
		System.out.println("  GET visits = " + db.incrby("visits", 0));

		db.decr("visits");
		db.decrby("visits", 3);
		System.out.println("After DECR + DECRBY 3:");
		System.out.println("  GET visits = " + db.incrby("visits", 0));

		// -------------------------------------------------------
		// 8. TTL / Expiry
		// -------------------------------------------------------
		System.out.println("\n--- TTL / Expiry ---");

		db.set("session", Strings.create("token-abc"), 500);
		System.out.println("SET session with 500ms TTL");
		System.out.println("TTL session = " + db.ttl("session") + " ms");
		System.out.println("EXISTS session = " + db.exists("session"));

		Thread.sleep(600);
		System.out.println("After 600ms sleep:");
		System.out.println("  EXISTS session = " + db.exists("session"));
		System.out.println("  GET session    = " + db.get("session"));

		// -------------------------------------------------------
		// 9. All keys
		// -------------------------------------------------------
		System.out.println("\n--- KEYS ---");
		ASet<AString> keys = db.keys();
		System.out.println("Live keys: " + keys);

		// -------------------------------------------------------
		// 10. Fork / Sync (replication)
		// -------------------------------------------------------
		System.out.println("\n--- Fork / Sync (Replication) ---");

		System.out.println("Root store keys: " + db.keys());

		// Fork simulates an independent replica
		LatticeKV replica = db.fork();
		System.out.println("Forked replica from root store");

		// Both sides make independent changes
		db.set("root-only", Strings.create("from-root"));
		replica.set("replica-only", Strings.create("from-replica"));
		replica.sadd("tags", Strings.create("distributed"));

		System.out.println("Root added 'root-only', replica added 'replica-only'");
		System.out.println("Root sees replica-only? " + db.exists("replica-only"));

		// Sync merges changes
		replica.sync();
		System.out.println("\nAfter sync:");
		System.out.println("  Root sees root-only?    " + db.exists("root-only"));
		System.out.println("  Root sees replica-only? " + db.exists("replica-only"));
		System.out.println("  tags after merge:       " + db.smembers("tags"));

		// -------------------------------------------------------
		// 11. Concurrent hash merge
		// -------------------------------------------------------
		System.out.println("\n--- Concurrent Hash Merge ---");

		db.hset("profile", "name", Strings.create("Alice"));

		LatticeKV fork1 = db.fork();
		LatticeKV fork2 = db.fork();

		fork1.hset("profile", "email", Strings.create("alice@work.com"));
		fork2.hset("profile", "phone", Strings.create("+1-555-0100"));

		fork1.sync();
		fork2.sync();

		System.out.println("Two forks added different fields concurrently:");
		System.out.println("  HGETALL profile = " + db.hgetall("profile"));

		// -------------------------------------------------------
		// 12. GC (garbage collection)
		// -------------------------------------------------------
		System.out.println("\n--- GC ---");
		long before = db.keys().count();
		long removed = db.gc();
		long after = db.keys().count();
		System.out.println("GC removed " + removed + " tombstones/expired entries");
		System.out.println("Keys before GC: " + before + ", after: " + after);

		// -------------------------------------------------------
		// 13. Benchmarks
		// -------------------------------------------------------
		System.out.println("\n--- Benchmarks ---");

		int N = 100_000;

		// SET benchmark
		LatticeKV bench = LatticeKV.create(Strings.create("bench"));
		long t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			bench.set("key:" + i, Strings.create("value-" + i));
		}
		long setNs = System.nanoTime() - t0;
		System.out.printf("SET   x%,d : %,d ms  (%,.0f ops/sec)%n", N, setNs / 1_000_000, N * 1e9 / setNs);

		// GET benchmark (existing keys)
		t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			bench.get("key:" + i);
		}
		long getNs = System.nanoTime() - t0;
		System.out.printf("GET   x%,d : %,d ms  (%,.0f ops/sec)%n", N, getNs / 1_000_000, N * 1e9 / getNs);

		// GET benchmark (missing keys)
		t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			bench.get("missing:" + i);
		}
		long missNs = System.nanoTime() - t0;
		System.out.printf("MISS  x%,d : %,d ms  (%,.0f ops/sec)%n", N, missNs / 1_000_000, N * 1e9 / missNs);

		// HSET benchmark (many fields on one key)
		t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			bench.hset("bighash", "field:" + i, Strings.create("v" + i));
		}
		long hsetNs = System.nanoTime() - t0;
		System.out.printf("HSET  x%,d : %,d ms  (%,.0f ops/sec)%n", N, hsetNs / 1_000_000, N * 1e9 / hsetNs);

		// HGET benchmark
		t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			bench.hget("bighash", "field:" + i);
		}
		long hgetNs = System.nanoTime() - t0;
		System.out.printf("HGET  x%,d : %,d ms  (%,.0f ops/sec)%n", N, hgetNs / 1_000_000, N * 1e9 / hgetNs);

		// SADD benchmark
		t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			bench.sadd("bigset", Strings.create("member-" + i));
		}
		long saddNs = System.nanoTime() - t0;
		System.out.printf("SADD  x%,d : %,d ms  (%,.0f ops/sec)%n", N, saddNs / 1_000_000, N * 1e9 / saddNs);

		// INCR benchmark
		LatticeKV counterBench = LatticeKV.create(Strings.create("counter-bench"));
		t0 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			counterBench.incr("counter");
		}
		long incrNs = System.nanoTime() - t0;
		System.out.printf("INCR  x%,d : %,d ms  (%,.0f ops/sec)%n", N, incrNs / 1_000_000, N * 1e9 / incrNs);

		// Fork/Sync benchmark
		int FORKS = 10_000;
		LatticeKV forkBench = LatticeKV.create(Strings.create("fork-bench"));
		forkBench.set("base", Strings.create("value"));
		t0 = System.nanoTime();
		for (int i = 0; i < FORKS; i++) {
			LatticeKV f = forkBench.fork();
			f.set("k" + i, Strings.create("v" + i));
			f.sync();
		}
		long forkNs = System.nanoTime() - t0;
		System.out.printf("FORK+SYNC x%,d : %,d ms  (%,.0f ops/sec)%n", FORKS, forkNs / 1_000_000, FORKS * 1e9 / forkNs);

		// Merge benchmark (two stores with disjoint keys)
		int MERGE_SIZE = 10_000;
		LatticeKV mergeA = LatticeKV.create(Strings.create("merge-a"));
		LatticeKV mergeB = LatticeKV.create(Strings.create("merge-b"));
		for (int i = 0; i < MERGE_SIZE; i++) {
			mergeA.set("a:" + i, Strings.create("va" + i));
			mergeB.set("b:" + i, Strings.create("vb" + i));
		}
		t0 = System.nanoTime();
		mergeA.cursor().merge(mergeB.cursor().get());
		long mergeNs = System.nanoTime() - t0;
		System.out.printf("MERGE %,d+%,d keys : %,d ms%n", MERGE_SIZE, MERGE_SIZE, mergeNs / 1_000_000);

		System.out.printf("%nStore size after benchmarks: %,d keys%n", bench.keys().count());

		System.out.println("\n=== Demo Complete ===");
	}
}
