package convex.lattice;

import java.util.Objects;
import java.util.function.Supplier;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;

/**
 * A list of keys, such as a blocklist or an allowlist, over a cursor holding an
 * {@link Index} from each key to a small entry record. A key with an entry is
 * listed: the bare entry {@code {}} is enough. Every field is optional and unknown
 * fields are kept:
 *
 * <pre>
 * {&lt;key&gt; {:reason "...", :since t, :until t}}
 * </pre>
 *
 * <p>Only {@code :until} is read: an entry whose {@code :until} is at or before the
 * clock's time reads as absent, though it stays stored until it is removed or makes
 * room. An {@code :until} that is not an integer is ignored. At most
 * {@code maxExpiring} entries carry an integer {@code :until}; adding one more at
 * that bound first drops the entries that have expired, then the entry nearest
 * expiry. Entries without one are never counted or dropped. Keys are matched by
 * their bytes, so a string and a blob with the same bytes are one key; keys longer
 * than an {@link Index} matches exactly are refused.</p>
 *
 * <p>The list decides neither its placement nor its replication: it takes both from
 * the cursor it is given. Within a {@code :local} record it is private to its owner
 * and replaced whole with the record, so a removal carries over as well as an
 * addition. An allowlist and a blocklist are two instances of this class. What a
 * list refuses or admits, and whether an allowlist is enforced at all, is the
 * application's decision.</p>
 *
 * @param <K> Key type
 */
public class AccessList<K extends ABlobLike<?>> extends ALatticeComponent<Index<K,ACell>> {

	/** Optional entry field: why the key is listed. */
	public static final Keyword REASON=Keyword.intern("reason");

	/** Optional entry field: when the key was listed. */
	public static final Keyword SINCE=Keyword.intern("since");

	/** Optional entry field: the time from which the entry no longer applies. */
	public static final Keyword UNTIL=Keyword.intern("until");

	/** Default bound on entries with an expiry time. */
	public static final int DEFAULT_MAX_EXPIRING=1024;

	private final Supplier<CVMLong> clock;
	private final int maxExpiring;

	/**
	 * Binds a list over a cursor, with the default bound on expiring entries.
	 *
	 * @param cursor Cursor holding the list's index, absent until the first addition
	 * @param clock Supplies the time against which {@code :until} is read
	 */
	public AccessList(AForkableCursor<Index<K,ACell>> cursor,Supplier<CVMLong> clock) {
		this(cursor,clock,DEFAULT_MAX_EXPIRING);
	}

	/**
	 * Binds a list over a cursor.
	 *
	 * @param cursor Cursor holding the list's index, absent until the first addition
	 * @param clock Supplies the time against which {@code :until} is read
	 * @param maxExpiring Most entries with an expiry time, at least one
	 */
	public AccessList(AForkableCursor<Index<K,ACell>> cursor,Supplier<CVMLong> clock,int maxExpiring) {
		super(cursor);
		if (maxExpiring<1) throw new IllegalArgumentException("Bound on expiring entries must be positive");
		this.clock=Objects.requireNonNull(clock,"Clock must not be null");
		this.maxExpiring=maxExpiring;
	}

	@Override
	protected AccessList<K> rebind(AForkableCursor<Index<K,ACell>> cursor) {
		return new AccessList<>(cursor,clock,maxExpiring);
	}

	@Override
	public AccessList<K> fork() {
		return rebind(storage.fork());
	}

	// Inspect foreign storage as a cell before the Java generic cast to Index
	@SuppressWarnings("unchecked")
	private AForkableCursor<ACell> values() {
		return (AForkableCursor<ACell>)(AForkableCursor<?>)cursor();
	}

	private static <K extends ABlobLike<?>> Index<K,ACell> requireIndex(ACell value) {
		if (value==null) return null;
		Index<K,ACell> index=RT.ensureIndex(value);
		if (index==null) throw new IllegalStateException("Access list requires an index at this path");
		return index;
	}

	/** Whether a key is listed and its entry has not expired. */
	public boolean contains(K key) {
		return get(key)!=null;
	}

	/**
	 * Returns a key's entry, {@code {}} for a bare one or one that is not a record, or
	 * null if the key is not listed or its entry has expired.
	 */
	public AHashMap<Keyword,ACell> get(K key) {
		if (key==null || tooLong(key)) return null;
		return live(values().getOrDefault(null),key,now());
	}

	/** Lists a key with a bare entry, until it is removed. Returns whether it was not listed before. */
	public boolean add(K key) {
		return add(key,Maps.empty());
	}

	/**
	 * Lists a key with the given entry, replacing any entry it has. An entry with an
	 * integer {@code :until} may first make room by dropping another expiring entry.
	 *
	 * @param key Key to list
	 * @param entry Entry record, stored as given
	 * @return Whether the key was not listed before
	 */
	public boolean add(K key,AHashMap<Keyword,ACell> entry) {
		if (key==null) throw new IllegalArgumentException("Key must not be null");
		if (tooLong(key)) throw new IllegalArgumentException("Key is too long for an access list");
		if (entry==null) throw new IllegalArgumentException("Entry must not be null");
		long now=now();
		boolean expiring=until(entry)!=null;
		ACell before=values().getAndUpdate(stored -> {
			Index<K,ACell> index=requireIndex(stored);
			if (index==null) index=Index.none();
			if (entry.equals(index.get(key))) return index;
			if (expiring) index=makeRoom(index,key,now);
			return index.assoc(key,entry);
		});
		return live(before,key,now)==null;
	}

	/** Removes a key's entry. Returns whether the key was listed, an expired entry not counting. */
	public boolean remove(K key) {
		if (key==null || tooLong(key)) return false;
		long now=now();
		ACell before=values().getAndUpdate(stored -> {
			Index<K,ACell> index=requireIndex(stored);
			return (index!=null && index.containsKey(key))?index.dissoc(key):stored;
		});
		return live(before,key,now)!=null;
	}

	/**
	 * Drops expired entries, then the entry nearest expiry, until another expiring
	 * entry fits. The key about to be replaced is not counted.
	 */
	private Index<K,ACell> makeRoom(Index<K,ACell> index,K key,long now) {
		long expiring=0;
		K nearest=null;
		long nearestUntil=Long.MAX_VALUE;
		Index<K,ACell> kept=index;
		long n=index.count();
		for (long i=0; i<n; i++) {
			MapEntry<K,ACell> e=index.entryAt(i);
			CVMLong until=until(e.getValue());
			if (until==null || e.getKey().equals(key)) continue;
			long t=until.longValue();
			if (t<=now) {
				kept=kept.dissoc(e.getKey());
				continue;
			}
			expiring++;
			if (t<nearestUntil) {
				nearest=e.getKey();
				nearestUntil=t;
			}
		}
		if (expiring>=maxExpiring) kept=kept.dissoc(nearest);
		return kept;
	}

	/** The entry for a key in a stored index if listed and not expired, otherwise null. */
	private static <K extends ABlobLike<?>> AHashMap<Keyword,ACell> live(ACell stored,K key,long now) {
		Index<K,ACell> index=requireIndex(stored);
		if (index==null) return null;
		MapEntry<K,ACell> e=index.getEntry(key);
		if (e==null) return null;
		AHashMap<Keyword,ACell> entry=RT.ensureHashMap(e.getValue());
		if (entry==null) return Maps.empty();
		CVMLong until=until(entry);
		return (until!=null && until.longValue()<=now)?null:entry;
	}

	/** An entry's integer {@code :until}, or null if it has none. */
	private static CVMLong until(ACell entry) {
		return (entry instanceof AHashMap<?,?> map && map.get(UNTIL) instanceof CVMLong until)?until:null;
	}

	private static boolean tooLong(ABlobLike<?> key) {
		return key.hexLength()>Index.MAX_DEPTH;
	}

	private long now() {
		return clock.get().longValue();
	}
}
