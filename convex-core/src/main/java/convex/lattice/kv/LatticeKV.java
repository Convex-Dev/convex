package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;

/**
 * A Redis-like key-value store built on Convex lattice technology.
 *
 * Provides core KV operations (GET, SET, DEL, EXISTS, KEYS, TTL/EXPIRE) plus
 * Redis-compatible data structures (hashes, sets, sorted sets, lists, counters).
 *
 * Built with lattice merge replication: use {@link #fork()} and {@link #sync()}
 * for conflict-free distributed operation.
 */
public class LatticeKV {

	private static final AString DEFAULT_REPLICA = Strings.create("default");

	private final ALatticeCursor<Index<AString, AVector<ACell>>> cursor;
	private final AString replicaID;

	public LatticeKV(ALatticeCursor<Index<AString, AVector<ACell>>> cursor, AString replicaID) {
		this.cursor = cursor;
		this.replicaID = replicaID;
	}

	/**
	 * Creates a new empty KV store
	 */
	public static LatticeKV create() {
		return create(DEFAULT_REPLICA);
	}

	/**
	 * Creates a new empty KV store with a specific replica ID for PN-counter operations
	 */
	public static LatticeKV create(AString replicaID) {
		ALatticeCursor<Index<AString, AVector<ACell>>> cursor =
			Cursors.createLattice(KVStoreLattice.INSTANCE);
		return new LatticeKV(cursor, replicaID);
	}

	/**
	 * Connects to an existing cursor for cursor chain integration.
	 *
	 * @param cursor Lattice cursor (e.g. from a SignedCursor path)
	 * @return New LatticeKV instance connected to the cursor
	 */
	public static LatticeKV connect(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		return new LatticeKV(cursor, DEFAULT_REPLICA);
	}

	/**
	 * Connects to an existing cursor for cursor chain integration with a replica ID.
	 *
	 * @param cursor Lattice cursor (e.g. from a SignedCursor path)
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New LatticeKV instance connected to the cursor
	 */
	public static LatticeKV connect(ALatticeCursor<Index<AString, AVector<ACell>>> cursor, AString replicaID) {
		return new LatticeKV(cursor, replicaID);
	}

	/**
	 * Returns the underlying lattice cursor for direct lattice operations
	 */
	public ALatticeCursor<Index<AString, AVector<ACell>>> cursor() {
		return cursor;
	}

	/**
	 * Creates a forked copy of this store for independent operation
	 */
	public LatticeKV fork() {
		return new LatticeKV(cursor.fork(), replicaID);
	}

	/**
	 * Syncs this forked store back to its parent, merging changes
	 */
	public void sync() {
		cursor.sync();
	}

	// ========== Internal Helpers ==========

	private CVMLong now() {
		return CVMLong.create(System.currentTimeMillis());
	}

	private AString key(String key) {
		return Strings.create(key);
	}

	private AVector<ACell> getEntry(String key) {
		Index<AString, AVector<ACell>> store = cursor.get();
		if (store == null) return null;
		return store.get(key(key));
	}

	private AVector<ACell> getLiveEntry(String key) {
		AVector<ACell> entry = getEntry(key);
		if (entry == null) return null;
		if (!KVEntry.isLive(entry, System.currentTimeMillis())) return null;
		return entry;
	}

	private void putEntry(String key, AVector<ACell> entry) {
		AString k = key(key);
		cursor.updateAndGet(store -> {
			if (store == null) store = KVStoreLattice.INSTANCE.zero();
			return store.assoc(k, entry);
		});
	}

	private void checkType(AVector<ACell> entry, long expectedType, String operation) {
		if (entry == null) return;
		long type = KVEntry.getType(entry);
		if (type >= 0 && type != expectedType) {
			throw new IllegalStateException(
				"WRONGTYPE Operation " + operation + " against a key holding " + KVEntry.typeName(entry));
		}
	}

	// ========== Core KV Operations ==========

	/**
	 * Gets the value for a key, or null if not found or expired
	 */
	public ACell get(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return null;
		return KVEntry.getValue(entry);
	}

	/**
	 * Sets a string value for a key
	 */
	public void set(String key, ACell value) {
		putEntry(key, KVEntry.createValue(value, now()));
	}

	/**
	 * Sets a string value for a key with TTL in milliseconds
	 */
	public void set(String key, ACell value, long ttlMillis) {
		long nowMs = System.currentTimeMillis();
		CVMLong timestamp = CVMLong.create(nowMs);
		CVMLong expire = CVMLong.create(nowMs + ttlMillis);
		putEntry(key, KVEntry.createValue(value, timestamp, expire));
	}

	/**
	 * Deletes a key by creating a tombstone
	 */
	public boolean del(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return false;
		putEntry(key, KVEntry.createTombstone(now()));
		return true;
	}

	/**
	 * Checks if a key exists and is live
	 */
	public boolean exists(String key) {
		return getLiveEntry(key) != null;
	}

	/**
	 * Returns all live keys
	 */
	public ASet<AString> keys() {
		Index<AString, AVector<ACell>> store = cursor.get();
		if (store == null) return Sets.empty();
		long now = System.currentTimeMillis();
		ASet<AString> result = Sets.empty();
		for (var e : store.entrySet()) {
			AVector<ACell> entry = e.getValue();
			if (KVEntry.isLive(entry, now)) {
				result = result.include(e.getKey());
			}
		}
		return result;
	}

	/**
	 * Sets expiry on an existing key. Returns 1 if set, 0 if key not found.
	 */
	public long expire(String key, long ttlMillis) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return 0;
		CVMLong expire = CVMLong.create(System.currentTimeMillis() + ttlMillis);
		putEntry(key, KVEntry.withExpiry(entry, expire));
		return 1;
	}

	/**
	 * Returns remaining TTL in millis, -1 if no expiry, -2 if key not found
	 */
	public long ttl(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return -2;
		CVMLong expire = KVEntry.getExpire(entry);
		if (expire == null) return -1;
		long remaining = expire.longValue() - System.currentTimeMillis();
		return Math.max(0, remaining);
	}

	/**
	 * Returns the type name of the value at key, or null if not found
	 */
	public String type(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return null;
		return KVEntry.typeName(entry);
	}

	// ========== Hash Operations ==========

	public void hset(String key, String field, ACell value) {
		AVector<ACell> entry = getLiveEntry(key);
		checkType(entry, KVEntry.TYPE_HASH, "HSET");
		ACell hashValue = (entry != null) ? KVEntry.getValue(entry) : null;
		ACell newHash = KVHash.setField(hashValue, field, value, now());
		putEntry(key, KVEntry.create(newHash, KVEntry.TYPE_HASH, now()));
	}

	public ACell hget(String key, String field) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return null;
		checkType(entry, KVEntry.TYPE_HASH, "HGET");
		return KVHash.getField(KVEntry.getValue(entry), field);
	}

	public boolean hdel(String key, String field) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return false;
		checkType(entry, KVEntry.TYPE_HASH, "HDEL");
		if (!KVHash.fieldExists(KVEntry.getValue(entry), field)) return false;
		ACell newHash = KVHash.deleteField(KVEntry.getValue(entry), field, now());
		putEntry(key, KVEntry.create(newHash, KVEntry.TYPE_HASH, now()));
		return true;
	}

	public boolean hexists(String key, String field) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return false;
		checkType(entry, KVEntry.TYPE_HASH, "HEXISTS");
		return KVHash.fieldExists(KVEntry.getValue(entry), field);
	}

	@SuppressWarnings("unchecked")
	public Index<AString, ACell> hgetall(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return (Index<AString, ACell>) Index.EMPTY;
		checkType(entry, KVEntry.TYPE_HASH, "HGETALL");
		ACell hashValue = KVEntry.getValue(entry);
		if (hashValue == null) return (Index<AString, ACell>) Index.EMPTY;
		// Filter out tombstoned fields and extract values
		Index<AString, AVector<ACell>> index = (Index<AString, AVector<ACell>>) hashValue;
		Index<AString, ACell> result = (Index<AString, ACell>) Index.EMPTY;
		for (var e : index.entrySet()) {
			ACell fieldValue = e.getValue().get(KVHash.FIELD_VALUE);
			if (fieldValue != null) {
				result = result.assoc(e.getKey(), fieldValue);
			}
		}
		return result;
	}

	public long hlen(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return 0;
		checkType(entry, KVEntry.TYPE_HASH, "HLEN");
		return KVHash.fieldCount(KVEntry.getValue(entry));
	}

	// ========== Set Operations ==========

	public long sadd(String key, ACell... members) {
		AVector<ACell> entry = getLiveEntry(key);
		checkType(entry, KVEntry.TYPE_SET, "SADD");
		ACell setValue = (entry != null) ? KVEntry.getValue(entry) : null;
		CVMLong ts = now();
		long added = 0;
		for (ACell member : members) {
			if (!KVSet.isMember(setValue, member)) added++;
			setValue = KVSet.addMember(setValue, member, ts);
		}
		putEntry(key, KVEntry.create(setValue, KVEntry.TYPE_SET, ts));
		return added;
	}

	public long srem(String key, ACell... members) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return 0;
		checkType(entry, KVEntry.TYPE_SET, "SREM");
		ACell setValue = KVEntry.getValue(entry);
		CVMLong ts = now();
		long removed = 0;
		for (ACell member : members) {
			if (KVSet.isMember(setValue, member)) removed++;
			setValue = KVSet.removeMember(setValue, member, ts);
		}
		putEntry(key, KVEntry.create(setValue, KVEntry.TYPE_SET, ts));
		return removed;
	}

	public boolean sismember(String key, ACell member) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return false;
		checkType(entry, KVEntry.TYPE_SET, "SISMEMBER");
		return KVSet.isMember(KVEntry.getValue(entry), member);
	}

	@SuppressWarnings("unchecked")
	public ASet<ACell> smembers(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return Sets.empty();
		checkType(entry, KVEntry.TYPE_SET, "SMEMBERS");
		ACell setValue = KVEntry.getValue(entry);
		if (setValue == null) return Sets.empty();
		Index<?, AVector<ACell>> index = (Index<?, AVector<ACell>>) setValue;
		ASet<ACell> result = Sets.empty();
		for (var e : index.entrySet()) {
			AVector<ACell> me = e.getValue();
			long addTime = ((CVMLong) me.get(KVSet.POS_ADD_TIME)).longValue();
			long removeTime = ((CVMLong) me.get(KVSet.POS_REMOVE_TIME)).longValue();
			if (addTime > removeTime) {
				result = result.include(me.get(KVSet.POS_MEMBER));
			}
		}
		return result;
	}

	public long scard(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return 0;
		checkType(entry, KVEntry.TYPE_SET, "SCARD");
		return KVSet.memberCount(KVEntry.getValue(entry));
	}

	// ========== Sorted Set Operations ==========

	public long zadd(String key, double score, ACell member) {
		AVector<ACell> entry = getLiveEntry(key);
		checkType(entry, KVEntry.TYPE_SORTED_SET, "ZADD");
		ACell zsetValue = (entry != null) ? KVEntry.getValue(entry) : null;
		CVMLong ts = now();
		long added = (KVSortedSet.getScore(zsetValue, member) == null) ? 1 : 0;
		zsetValue = KVSortedSet.addMember(zsetValue, member, CVMDouble.create(score), ts);
		putEntry(key, KVEntry.create(zsetValue, KVEntry.TYPE_SORTED_SET, ts));
		return added;
	}

	public long zrem(String key, ACell... members) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return 0;
		checkType(entry, KVEntry.TYPE_SORTED_SET, "ZREM");
		ACell zsetValue = KVEntry.getValue(entry);
		CVMLong ts = now();
		long removed = 0;
		for (ACell member : members) {
			if (KVSortedSet.getScore(zsetValue, member) != null) removed++;
			zsetValue = KVSortedSet.removeMember(zsetValue, member, ts);
		}
		putEntry(key, KVEntry.create(zsetValue, KVEntry.TYPE_SORTED_SET, ts));
		return removed;
	}

	public CVMDouble zscore(String key, ACell member) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return null;
		checkType(entry, KVEntry.TYPE_SORTED_SET, "ZSCORE");
		return KVSortedSet.getScore(KVEntry.getValue(entry), member);
	}

	public AVector<ACell> zrange(String key, long start, long stop) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return Vectors.empty();
		checkType(entry, KVEntry.TYPE_SORTED_SET, "ZRANGE");
		return KVSortedSet.range(KVEntry.getValue(entry), start, stop);
	}

	public long zcard(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return 0;
		checkType(entry, KVEntry.TYPE_SORTED_SET, "ZCARD");
		return KVSortedSet.memberCount(KVEntry.getValue(entry));
	}

	// ========== List Operations ==========

	public long lpush(String key, ACell... values) {
		AVector<ACell> entry = getLiveEntry(key);
		checkType(entry, KVEntry.TYPE_LIST, "LPUSH");
		ACell listValue = (entry != null) ? KVEntry.getValue(entry) : null;
		listValue = KVList.lpush(listValue, values);
		putEntry(key, KVEntry.create(listValue, KVEntry.TYPE_LIST, now()));
		return KVList.length(listValue);
	}

	public long rpush(String key, ACell... values) {
		AVector<ACell> entry = getLiveEntry(key);
		checkType(entry, KVEntry.TYPE_LIST, "RPUSH");
		ACell listValue = (entry != null) ? KVEntry.getValue(entry) : null;
		listValue = KVList.rpush(listValue, values);
		putEntry(key, KVEntry.create(listValue, KVEntry.TYPE_LIST, now()));
		return KVList.length(listValue);
	}

	public ACell lpop(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return null;
		checkType(entry, KVEntry.TYPE_LIST, "LPOP");
		ACell listValue = KVEntry.getValue(entry);
		ACell popped = KVList.lpop(listValue);
		ACell newList = KVList.afterLpop(listValue);
		putEntry(key, KVEntry.create(newList, KVEntry.TYPE_LIST, now()));
		return popped;
	}

	public ACell rpop(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return null;
		checkType(entry, KVEntry.TYPE_LIST, "RPOP");
		ACell listValue = KVEntry.getValue(entry);
		ACell popped = KVList.rpop(listValue);
		ACell newList = KVList.afterRpop(listValue);
		putEntry(key, KVEntry.create(newList, KVEntry.TYPE_LIST, now()));
		return popped;
	}

	public AVector<ACell> lrange(String key, long start, long stop) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return Vectors.empty();
		checkType(entry, KVEntry.TYPE_LIST, "LRANGE");
		return KVList.lrange(KVEntry.getValue(entry), start, stop);
	}

	public long llen(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return 0;
		checkType(entry, KVEntry.TYPE_LIST, "LLEN");
		return KVList.length(KVEntry.getValue(entry));
	}

	// ========== Counter Operations ==========

	public long incr(String key) {
		return incrby(key, 1);
	}

	public long incrby(String key, long amount) {
		AVector<ACell> entry = getLiveEntry(key);
		checkType(entry, KVEntry.TYPE_COUNTER, "INCRBY");
		ACell counterValue = (entry != null) ? KVEntry.getValue(entry) : null;
		counterValue = KVCounter.increment(counterValue, replicaID, amount);
		putEntry(key, KVEntry.create(counterValue, KVEntry.TYPE_COUNTER, now()));
		return KVCounter.getValue(counterValue);
	}

	public long decr(String key) {
		return decrby(key, 1);
	}

	public long decrby(String key, long amount) {
		return incrby(key, -amount);
	}

	// ========== Maintenance ==========

	/**
	 * Removes expired entries and old tombstones. Returns count of entries removed.
	 */
	public long gc() {
		long now = System.currentTimeMillis();
		long[] removed = {0};
		cursor.updateAndGet(store -> {
			if (store == null) return store;
			Index<AString, AVector<ACell>> result = store;
			for (var e : store.entrySet()) {
				AVector<ACell> entry = e.getValue();
				if (KVEntry.isTombstone(entry) || KVEntry.isExpired(entry, now)) {
					result = result.dissoc(e.getKey());
					removed[0]++;
				}
			}
			return result;
		});
		return removed[0];
	}
}
