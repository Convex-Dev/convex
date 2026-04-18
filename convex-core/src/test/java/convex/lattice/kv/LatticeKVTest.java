package convex.lattice.kv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Tests for the LatticeKV key-value store.
 */
public class LatticeKVTest {

	// ===== KVEntry Tests =====

	@Test
	public void testEntryCreation() {
		CVMLong ts = CVMLong.create(1000);
		AVector<ACell> entry = KVEntry.createValue(Strings.create("hello"), ts);
		assertEquals(Strings.create("hello"), KVEntry.getValue(entry));
		assertEquals(KVEntry.TYPE_VALUE, KVEntry.getType(entry));
		assertEquals(ts, KVEntry.getUTime(entry));
		assertNull(KVEntry.getExpire(entry));
		assertTrue(KVEntry.isValid(entry));
		assertFalse(KVEntry.isTombstone(entry));
		assertEquals("value", KVEntry.typeName(entry));
	}

	@Test
	public void testTombstone() {
		CVMLong ts = CVMLong.create(2000);
		AVector<ACell> tomb = KVEntry.createTombstone(ts);
		assertTrue(KVEntry.isTombstone(tomb));
		assertNull(KVEntry.getValue(tomb));
		assertEquals(-1, KVEntry.getType(tomb));
		assertEquals(ts, KVEntry.getUTime(tomb));
		assertTrue(KVEntry.isValid(tomb));
		assertNull(KVEntry.typeName(tomb));
	}

	@Test
	public void testExpiry() {
		CVMLong ts = CVMLong.create(1000);
		CVMLong expire = CVMLong.create(5000);
		AVector<ACell> entry = KVEntry.createValue(Strings.create("val"), ts, expire);
		assertFalse(KVEntry.isExpired(entry, 4999));
		assertTrue(KVEntry.isExpired(entry, 5000));
		assertTrue(KVEntry.isExpired(entry, 6000));
		assertTrue(KVEntry.isLive(entry, 4999));
		assertFalse(KVEntry.isLive(entry, 5000));
	}

	@Test
	public void testNoExpiry() {
		AVector<ACell> entry = KVEntry.createValue(Strings.create("val"), CVMLong.create(1000));
		assertFalse(KVEntry.isExpired(entry, Long.MAX_VALUE));
		assertTrue(KVEntry.isLive(entry, Long.MAX_VALUE));
	}

	// ===== KVEntryLattice Merge Tests =====

	@Test
	public void testMergeIdempotence() {
		AVector<ACell> entry = KVEntry.createValue(Strings.create("a"), CVMLong.create(1000));
		AVector<ACell> merged = KVEntryLattice.INSTANCE.merge(entry, entry);
		assertEquals(entry, merged);
	}

	@Test
	public void testMergeCommutativity() {
		AVector<ACell> a = KVEntry.createValue(Strings.create("a"), CVMLong.create(1000));
		AVector<ACell> b = KVEntry.createValue(Strings.create("b"), CVMLong.create(2000));
		AVector<ACell> ab = KVEntryLattice.INSTANCE.merge(a, b);
		AVector<ACell> ba = KVEntryLattice.INSTANCE.merge(b, a);
		assertEquals(ab, ba);
	}

	@Test
	public void testMergeLWW() {
		AVector<ACell> older = KVEntry.createValue(Strings.create("old"), CVMLong.create(1000));
		AVector<ACell> newer = KVEntry.createValue(Strings.create("new"), CVMLong.create(2000));
		AVector<ACell> merged = KVEntryLattice.INSTANCE.merge(older, newer);
		assertEquals(Strings.create("new"), KVEntry.getValue(merged));
	}

	@Test
	public void testMergeTombstoneWins() {
		AVector<ACell> live = KVEntry.createValue(Strings.create("val"), CVMLong.create(1000));
		AVector<ACell> tomb = KVEntry.createTombstone(CVMLong.create(2000));
		AVector<ACell> merged = KVEntryLattice.INSTANCE.merge(live, tomb);
		assertTrue(KVEntry.isTombstone(merged));
	}

	@Test
	public void testMergeWriteResurrects() {
		AVector<ACell> tomb = KVEntry.createTombstone(CVMLong.create(1000));
		AVector<ACell> live = KVEntry.createValue(Strings.create("back"), CVMLong.create(2000));
		AVector<ACell> merged = KVEntryLattice.INSTANCE.merge(tomb, live);
		assertFalse(KVEntry.isTombstone(merged));
		assertEquals(Strings.create("back"), KVEntry.getValue(merged));
	}

	@Test
	public void testMergeNull() {
		AVector<ACell> entry = KVEntry.createValue(Strings.create("a"), CVMLong.create(1000));
		assertEquals(entry, KVEntryLattice.INSTANCE.merge(entry, null));
		assertEquals(entry, KVEntryLattice.INSTANCE.merge(null, entry));
	}

	// ===== Core KV Operations =====

	@Test
	public void testGetSet() {
		LatticeKV kv = LatticeKV.create();
		assertNull(kv.get("foo"));
		kv.set("foo", Strings.create("bar"));
		assertEquals(Strings.create("bar"), kv.get("foo"));
	}

	@Test
	public void testDel() {
		LatticeKV kv = LatticeKV.create();
		kv.set("foo", Strings.create("bar"));
		assertTrue(kv.del("foo"));
		assertNull(kv.get("foo"));
		assertFalse(kv.del("foo"));
	}

	@Test
	public void testExists() {
		LatticeKV kv = LatticeKV.create();
		assertFalse(kv.exists("foo"));
		kv.set("foo", Strings.create("bar"));
		assertTrue(kv.exists("foo"));
		kv.del("foo");
		assertFalse(kv.exists("foo"));
	}

	@Test
	public void testKeys() {
		LatticeKV kv = LatticeKV.create();
		kv.set("a", Strings.create("1"));
		kv.set("b", Strings.create("2"));
		kv.set("c", Strings.create("3"));
		ASet<AString> keys = kv.keys();
		assertEquals(3, keys.count());
		assertTrue(keys.contains(Strings.create("a")));
		assertTrue(keys.contains(Strings.create("b")));
		assertTrue(keys.contains(Strings.create("c")));
	}

	@Test
	public void testType() {
		LatticeKV kv = LatticeKV.create();
		kv.set("str", Strings.create("val"));
		assertEquals("value", kv.type("str"));
		assertNull(kv.type("nonexistent"));
	}

	@Test
	public void testOverwrite() {
		LatticeKV kv = LatticeKV.create();
		kv.set("foo", Strings.create("first"));
		kv.set("foo", Strings.create("second"));
		assertEquals(Strings.create("second"), kv.get("foo"));
	}

	// ===== Hash Operations =====

	@Test
	public void testHashOperations() {
		LatticeKV kv = LatticeKV.create();
		kv.hset("h", "field1", Strings.create("val1"));
		kv.hset("h", "field2", Strings.create("val2"));

		assertEquals(Strings.create("val1"), kv.hget("h", "field1"));
		assertEquals(Strings.create("val2"), kv.hget("h", "field2"));
		assertNull(kv.hget("h", "missing"));
		assertTrue(kv.hexists("h", "field1"));
		assertFalse(kv.hexists("h", "missing"));
		assertEquals(2, kv.hlen("h"));
		assertEquals("hash", kv.type("h"));
	}

	@Test
	public void testHashDelete() {
		LatticeKV kv = LatticeKV.create();
		kv.hset("h", "f1", Strings.create("v1"));
		kv.hset("h", "f2", Strings.create("v2"));
		assertTrue(kv.hdel("h", "f1"));
		assertFalse(kv.hexists("h", "f1"));
		assertEquals(1, kv.hlen("h"));
		assertFalse(kv.hdel("h", "f1"));
	}

	@Test
	public void testHashGetAll() {
		LatticeKV kv = LatticeKV.create();
		kv.hset("h", "a", Strings.create("1"));
		kv.hset("h", "b", Strings.create("2"));
		Index<AString, ACell> all = kv.hgetall("h");
		assertEquals(2, all.count());
		assertEquals(Strings.create("1"), all.get(Strings.create("a")));
	}

	@Test
	public void testHashWrongType() {
		LatticeKV kv = LatticeKV.create();
		kv.set("str", Strings.create("val"));
		assertThrows(IllegalStateException.class, () -> kv.hset("str", "f", Strings.create("v")));
	}

	// ===== Set Operations =====

	@Test
	public void testSetOperations() {
		LatticeKV kv = LatticeKV.create();
		assertEquals(2, kv.sadd("s", Strings.create("a"), Strings.create("b")));
		assertEquals(1, kv.sadd("s", Strings.create("c"), Strings.create("a")));
		assertTrue(kv.sismember("s", Strings.create("a")));
		assertFalse(kv.sismember("s", Strings.create("x")));
		assertEquals(3, kv.scard("s"));
		assertEquals("set", kv.type("s"));
	}

	@Test
	public void testSetRemove() {
		LatticeKV kv = LatticeKV.create();
		kv.sadd("s", Strings.create("a"), Strings.create("b"));
		assertEquals(1, kv.srem("s", Strings.create("a")));
		assertFalse(kv.sismember("s", Strings.create("a")));
		assertEquals(1, kv.scard("s"));
	}

	@Test
	public void testSmembers() {
		LatticeKV kv = LatticeKV.create();
		kv.sadd("s", Strings.create("x"), Strings.create("y"));
		ASet<ACell> members = kv.smembers("s");
		assertEquals(2, members.count());
		assertTrue(members.contains(Strings.create("x")));
		assertTrue(members.contains(Strings.create("y")));
	}

	// ===== Sorted Set Operations =====

	@Test
	public void testSortedSetOperations() {
		LatticeKV kv = LatticeKV.create();
		assertEquals(1, kv.zadd("z", 1.0, Strings.create("a")));
		assertEquals(1, kv.zadd("z", 3.0, Strings.create("c")));
		assertEquals(1, kv.zadd("z", 2.0, Strings.create("b")));
		assertEquals(0, kv.zadd("z", 1.5, Strings.create("a")));

		assertEquals(3, kv.zcard("z"));
		assertEquals(CVMDouble.create(1.5), kv.zscore("z", Strings.create("a")));
		assertEquals("zset", kv.type("z"));
	}

	@Test
	public void testZrange() {
		LatticeKV kv = LatticeKV.create();
		kv.zadd("z", 3.0, Strings.create("c"));
		kv.zadd("z", 1.0, Strings.create("a"));
		kv.zadd("z", 2.0, Strings.create("b"));

		AVector<ACell> range = kv.zrange("z", 0, 2);
		assertEquals(3, range.count());
		assertEquals(Strings.create("a"), range.get(0));
		assertEquals(Strings.create("b"), range.get(1));
		assertEquals(Strings.create("c"), range.get(2));

		AVector<ACell> partial = kv.zrange("z", 0, 1);
		assertEquals(2, partial.count());
	}

	@Test
	public void testZrem() {
		LatticeKV kv = LatticeKV.create();
		kv.zadd("z", 1.0, Strings.create("a"));
		kv.zadd("z", 2.0, Strings.create("b"));
		assertEquals(1, kv.zrem("z", Strings.create("a")));
		assertNull(kv.zscore("z", Strings.create("a")));
		assertEquals(1, kv.zcard("z"));
	}

	// ===== List Operations =====

	@Test
	public void testListRpush() {
		LatticeKV kv = LatticeKV.create();
		assertEquals(1, kv.rpush("l", Strings.create("a")));
		assertEquals(2, kv.rpush("l", Strings.create("b")));
		assertEquals(3, kv.rpush("l", Strings.create("c")));

		AVector<ACell> range = kv.lrange("l", 0, -1);
		assertEquals(3, range.count());
		assertEquals(Strings.create("a"), range.get(0));
		assertEquals(Strings.create("c"), range.get(2));
		assertEquals("list", kv.type("l"));
	}

	@Test
	public void testListLpush() {
		LatticeKV kv = LatticeKV.create();
		kv.lpush("l", Strings.create("a"));
		kv.lpush("l", Strings.create("b"));
		// After LPUSH b, a: list is [b, a]
		assertEquals(Strings.create("b"), kv.lrange("l", 0, 0).get(0));
	}

	@Test
	public void testListPop() {
		LatticeKV kv = LatticeKV.create();
		kv.rpush("l", Strings.create("a"), Strings.create("b"), Strings.create("c"));
		assertEquals(Strings.create("a"), kv.lpop("l"));
		assertEquals(Strings.create("c"), kv.rpop("l"));
		assertEquals(1, kv.llen("l"));
	}

	// ===== Counter Operations =====

	@Test
	public void testCounter() {
		LatticeKV kv = LatticeKV.create();
		assertEquals(1, kv.incr("c"));
		assertEquals(2, kv.incr("c"));
		assertEquals(5, kv.incrby("c", 3));
		assertEquals(4, kv.decr("c"));
		assertEquals(1, kv.decrby("c", 3));
		assertEquals("counter", kv.type("c"));
	}

	// ===== Fork/Sync Replication =====

	@Test
	public void testForkSync() {
		LatticeKV root = LatticeKV.create();
		root.set("shared", Strings.create("original"));

		LatticeKV fork = root.fork();
		fork.set("new-key", Strings.create("from-fork"));
		// Root doesn't see fork changes yet
		assertNull(root.get("new-key"));

		fork.sync();
		// After sync, root sees fork changes
		assertEquals(Strings.create("from-fork"), root.get("new-key"));
		// Original key preserved
		assertEquals(Strings.create("original"), root.get("shared"));
	}

	@Test
	public void testConcurrentForksConverge() {
		LatticeKV root = LatticeKV.create();

		LatticeKV fork1 = root.fork();
		LatticeKV fork2 = root.fork();

		fork1.set("key1", Strings.create("from-1"));
		fork2.set("key2", Strings.create("from-2"));

		fork1.sync();
		fork2.sync();

		// Both keys visible in root
		assertEquals(Strings.create("from-1"), root.get("key1"));
		assertEquals(Strings.create("from-2"), root.get("key2"));
	}

	@Test
	public void testConcurrentSetMerge() {
		LatticeKV root = LatticeKV.create(Strings.create("root"));
		root.sadd("tags", Strings.create("initial"));

		LatticeKV fork1 = root.fork();
		LatticeKV fork2 = root.fork();

		fork1.sadd("tags", Strings.create("from-1"));
		fork2.sadd("tags", Strings.create("from-2"));

		fork1.sync();
		fork2.sync();

		// All members present via set merge
		assertTrue(root.sismember("tags", Strings.create("initial")));
		assertTrue(root.sismember("tags", Strings.create("from-1")));
		assertTrue(root.sismember("tags", Strings.create("from-2")));
		assertEquals(3, root.scard("tags"));
	}

	@Test
	public void testConcurrentCounterMerge() {
		LatticeKV root = LatticeKV.create(Strings.create("root"));

		// Create forks with different replica IDs for PN-counter
		LatticeKV fork1 = new LatticeKV(root.cursor().fork(), Strings.create("replica-1"));
		LatticeKV fork2 = new LatticeKV(root.cursor().fork(), Strings.create("replica-2"));

		// Both increment independently
		fork1.incrby("counter", 5);
		fork2.incrby("counter", 3);

		// Sync both back
		fork1.sync();
		fork2.sync();

		// PN-Counter merge: both replica contributions preserved
		assertEquals(8, root.incrby("counter", 0));
	}

	@Test
	public void testGc() {
		LatticeKV kv = LatticeKV.create();
		kv.set("a", Strings.create("1"));
		kv.set("b", Strings.create("2"));
		kv.del("a");
		long removed = kv.gc();
		assertEquals(1, removed);
		assertFalse(kv.exists("a"));
		assertTrue(kv.exists("b"));
	}
}
