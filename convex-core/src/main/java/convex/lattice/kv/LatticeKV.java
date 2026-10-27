package convex.lattice.kv;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;
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
public class LatticeKV extends ALatticeComponent<Index<AString, AVector<ACell>>> {

	private static final AString DEFAULT_REPLICA = Strings.create("default");

	private final AString replicaID;
	private final Supplier<CVMLong> clock;

	public LatticeKV(AForkableCursor<Index<AString, AVector<ACell>>> cursor, AString replicaID) {
		this(cursor,replicaID,Utils::getCurrentTimestampCell);
	}

	/** Binds the clock used for writes and expiry checks; forks retain it. */
	public LatticeKV(AForkableCursor<Index<AString,AVector<ACell>>> cursor,AString replicaID,Supplier<CVMLong> clock) {
		super(cursor);
		this.replicaID=replicaID;
		this.clock=Objects.requireNonNull(clock);
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
		return create(replicaID,Utils::getCurrentTimestampCell);
	}

	/** Creates a store with an explicit clock, which may advance independently of state. */
	public static LatticeKV create(AString replicaID,Supplier<CVMLong> clock) {
		AForkableCursor<Index<AString, AVector<ACell>>> cursor =
			Cursors.root(KVStoreLattice.INSTANCE.zero());
		return new LatticeKV(cursor,replicaID,clock);
	}

	/**
	 * Connects to an existing cursor for cursor chain integration.
	 *
	 * @param cursor Lattice cursor (e.g. from a SignedCursor path)
	 * @return New LatticeKV instance connected to the cursor
	 */
	public static LatticeKV connect(AForkableCursor<Index<AString, AVector<ACell>>> cursor) {
		return new LatticeKV(cursor, DEFAULT_REPLICA);
	}

	/**
	 * Connects to an existing cursor for cursor chain integration with a replica ID.
	 *
	 * @param cursor Lattice cursor (e.g. from a SignedCursor path)
	 * @param replicaID Replica ID for PN-counter operations
	 * @return New LatticeKV instance connected to the cursor
	 */
	public static LatticeKV connect(AForkableCursor<Index<AString, AVector<ACell>>> cursor, AString replicaID) {
		return new LatticeKV(cursor,replicaID);
	}

	/**
	 * Creates a forked copy of this store for independent operation
	 */
	public LatticeKV fork() {
		return rebind(storage.fork());
	}

	/**
	 * Creates a forked copy acting as a different replica, so that PN-counter
	 * increments made on the fork are attributed to that replica when synced.
	 *
	 * @param replicaID Replica ID for PN-counter operations on the fork
	 * @return Forked store
	 */
	public LatticeKV fork(AString replicaID) {
		return new LatticeKV(storage.fork(),replicaID,clock);
	}

	@Override
	protected LatticeKV rebind(AForkableCursor<Index<AString,AVector<ACell>>> cursor) {
		return new LatticeKV(cursor,replicaID,clock);
	}

	// ========== Internal Helpers ==========

	// #561: resolve time through the component policy. The default policy uses runtime
	// time; applications and tests may supply a fixed or otherwise dynamic clock.
	private CVMLong now() {
		return clock.get();
	}

	private long nowMs() {
		return clock.get().longValue();
	}

	private AString key(String key) {
		return Strings.create(key);
	}

	private AVector<ACell> getEntry(String key) {
		Index<AString, AVector<ACell>> store = cursor().get();
		if (store == null) return null;
		return store.get(key(key));
	}

	private AVector<ACell> getLiveEntry(String key) {
		AVector<ACell> entry = getEntry(key);
		if (entry == null) return null;
		if (!KVEntry.isLive(entry, nowMs())) return null;
		return entry;
	}

	private void putEntry(String key, AVector<ACell> entry) {
		AString k = key(key);
		cursor().updateAndGet(store -> {
			if (store == null) store = KVStoreLattice.INSTANCE.zero();
			return store.assoc(k, entry);
		});
	}

	/** An absent replacement leaves storage unchanged, including expired entries and tombstones. */
	private record Edit<R>(AVector<ACell> entry,R result) {}

	/** Reads, checks and replaces one live entry atomically, using one timestamp across retries. */
	private <R> R edit(String key,long type,String operation,
			BiFunction<AVector<ACell>,CVMLong,Edit<R>> action) {
		AString k=key(key);
		CVMLong timestamp=now();
		class Update implements UnaryOperator<Index<AString,AVector<ACell>>> {
			R result;
			@Override public Index<AString,AVector<ACell>> apply(Index<AString,AVector<ACell>> store) {
				AVector<ACell> entry=store==null?null:store.get(k);
				if (entry!=null && !KVEntry.isLive(entry,timestamp.longValue())) entry=null;
				if (type>=0) checkType(entry,type,operation);
				Edit<R> change=action.apply(entry,timestamp);
				result=change.result();
				if (change.entry()==null) return store;
				return (store==null?KVStoreLattice.INSTANCE.zero():store).assoc(k,change.entry());
			}
		}
		Update update=new Update();
		cursor().updateAndGet(update);
		return update.result;
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
		long nowMs = nowMs();
		CVMLong timestamp = CVMLong.create(nowMs);
		CVMLong expire = CVMLong.create(nowMs + ttlMillis);
		putEntry(key, KVEntry.createValue(value, timestamp, expire));
	}

	/**
	 * Deletes a key by creating a tombstone. Tombstones persist, so a replica
	 * holding an older value cannot restore the key.
	 */
	public boolean del(String key) {
		return edit(key,-1,"DEL",(entry,ts) ->
			new Edit<>(entry==null?null:KVEntry.createTombstone(ts),entry!=null));
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
		Index<AString, AVector<ACell>> store = cursor().get();
		if (store == null) return Sets.empty();
		long now = nowMs();
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
		return edit(key,-1,"EXPIRE",(entry,ts) -> new Edit<>(entry==null?null:
			KVEntry.withExpiry(entry,CVMLong.create(ts.longValue()+ttlMillis)),entry==null?0L:1L));
	}

	/**
	 * Returns remaining TTL in millis, -1 if no expiry, -2 if key not found
	 */
	public long ttl(String key) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return -2;
		CVMLong expire = KVEntry.getExpire(entry);
		if (expire == null) return -1;
		long remaining = expire.longValue() - nowMs();
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
		edit(key,KVEntry.TYPE_HASH,"HSET",(entry,ts) -> new Edit<>(
			KVEntry.create(KVHash.setField(KVEntry.getValue(entry),field,value,ts),KVEntry.TYPE_HASH,ts),null));
	}

	public ACell hget(String key, String field) {
		AVector<ACell> entry = getLiveEntry(key);
		if (entry == null) return null;
		checkType(entry, KVEntry.TYPE_HASH, "HGET");
		return KVHash.getField(KVEntry.getValue(entry), field);
	}

	public boolean hdel(String key, String field) {
		return edit(key,KVEntry.TYPE_HASH,"HDEL",(entry,ts) -> {
			if (entry==null || !KVHash.fieldExists(KVEntry.getValue(entry),field)) return new Edit<>(null,false);
			ACell value=KVHash.deleteField(KVEntry.getValue(entry),field,ts);
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_HASH,ts),true);
		});
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
		return edit(key,KVEntry.TYPE_SET,"SADD",(entry,ts) -> {
			ACell value=KVEntry.getValue(entry);
			long added=0;
			for (ACell member:members) {
				if (!KVSet.isMember(value,member)) added++;
				value=KVSet.addMember(value,member,ts);
			}
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_SET,ts),added);
		});
	}

	public long srem(String key, ACell... members) {
		return edit(key,KVEntry.TYPE_SET,"SREM",(entry,ts) -> {
			if (entry==null) return new Edit<>(null,0L);
			ACell value=KVEntry.getValue(entry);
			long removed=0;
			for (ACell member:members) {
				if (KVSet.isMember(value,member)) removed++;
				value=KVSet.removeMember(value,member,ts);
			}
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_SET,ts),removed);
		});
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
		return edit(key,KVEntry.TYPE_SORTED_SET,"ZADD",(entry,ts) -> {
			ACell value=KVEntry.getValue(entry);
			long added=KVSortedSet.getScore(value,member)==null?1:0;
			value=KVSortedSet.addMember(value,member,CVMDouble.create(score),ts);
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_SORTED_SET,ts),added);
		});
	}

	public long zrem(String key, ACell... members) {
		return edit(key,KVEntry.TYPE_SORTED_SET,"ZREM",(entry,ts) -> {
			if (entry==null) return new Edit<>(null,0L);
			ACell value=KVEntry.getValue(entry);
			long removed=0;
			for (ACell member:members) {
				if (KVSortedSet.getScore(value,member)!=null) removed++;
				value=KVSortedSet.removeMember(value,member,ts);
			}
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_SORTED_SET,ts),removed);
		});
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
		return edit(key,KVEntry.TYPE_LIST,"LPUSH",(entry,ts) -> {
			ACell value=KVList.lpush(KVEntry.getValue(entry),values);
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_LIST,ts),KVList.length(value));
		});
	}

	public long rpush(String key, ACell... values) {
		return edit(key,KVEntry.TYPE_LIST,"RPUSH",(entry,ts) -> {
			ACell value=KVList.rpush(KVEntry.getValue(entry),values);
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_LIST,ts),KVList.length(value));
		});
	}

	public ACell lpop(String key) {
		return edit(key,KVEntry.TYPE_LIST,"LPOP",(entry,ts) -> {
			if (entry==null) return new Edit<>(null,null);
			ACell value=KVEntry.getValue(entry);
			return new Edit<>(KVEntry.create(KVList.afterLpop(value),KVEntry.TYPE_LIST,ts),KVList.lpop(value));
		});
	}

	public ACell rpop(String key) {
		return edit(key,KVEntry.TYPE_LIST,"RPOP",(entry,ts) -> {
			if (entry==null) return new Edit<>(null,null);
			ACell value=KVEntry.getValue(entry);
			return new Edit<>(KVEntry.create(KVList.afterRpop(value),KVEntry.TYPE_LIST,ts),KVList.rpop(value));
		});
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
		return edit(key,KVEntry.TYPE_COUNTER,"INCRBY",(entry,ts) -> {
			ACell value=KVCounter.increment(KVEntry.getValue(entry),replicaID,amount);
			return new Edit<>(KVEntry.create(value,KVEntry.TYPE_COUNTER,ts),KVCounter.getValue(value));
		});
	}

	public long decr(String key) {
		return decrby(key, 1);
	}

	public long decrby(String key, long amount) {
		return incrby(key, -amount);
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected Index<AString, AVector<ACell>> mergePublication(
			Index<AString, AVector<ACell>> own,
			Index<AString, AVector<ACell>> other) {
		return KVStoreLattice.INSTANCE.merge(own,other);
	}
}
