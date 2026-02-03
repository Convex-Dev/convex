package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Static utility class for working with KV entry structures.
 *
 * Each entry is an AVector&lt;ACell&gt; with positional fields:
 * <ul>
 *   <li>POS_VALUE (0) - The stored value</li>
 *   <li>POS_TYPE (1) - CVMLong type tag</li>
 *   <li>POS_UTIME (2) - Last modification timestamp (epoch millis)</li>
 *   <li>POS_EXPIRE (3) - Expiry timestamp (0 = no expiry)</li>
 * </ul>
 *
 * A tombstone is an entry with null value and null type, preserving the timestamp.
 */
public class KVEntry {

	public static final long ENTRY_LENGTH = 4;

	public static final int POS_VALUE = 0;
	public static final int POS_TYPE = 1;
	public static final int POS_UTIME = 2;
	public static final int POS_EXPIRE = 3;

	// Type tags
	public static final long TYPE_STRING = 0;
	public static final long TYPE_HASH = 1;
	public static final long TYPE_SET = 2;
	public static final long TYPE_SORTED_SET = 3;
	public static final long TYPE_LIST = 4;
	public static final long TYPE_COUNTER = 5;

	private static final AVector<ACell> TOMBSTONE = Vectors.of(null, null, CVMLong.ZERO, CVMLong.ZERO);

	/**
	 * Creates a string-type entry
	 */
	public static AVector<ACell> createString(ACell value, CVMLong timestamp) {
		return Vectors.of(value, CVMLong.create(TYPE_STRING), timestamp, CVMLong.ZERO);
	}

	/**
	 * Creates a string-type entry with TTL
	 */
	public static AVector<ACell> createString(ACell value, CVMLong timestamp, CVMLong expire) {
		return Vectors.of(value, CVMLong.create(TYPE_STRING), timestamp, expire);
	}

	/**
	 * Creates an entry with a specific type tag
	 */
	public static AVector<ACell> create(ACell value, long type, CVMLong timestamp) {
		return Vectors.of(value, CVMLong.create(type), timestamp, CVMLong.ZERO);
	}

	/**
	 * Creates a tombstone entry marking a deleted key
	 */
	public static AVector<ACell> createTombstone(CVMLong timestamp) {
		return TOMBSTONE.assoc(POS_UTIME, timestamp);
	}

	/**
	 * Gets the stored value from an entry
	 */
	public static ACell getValue(AVector<ACell> entry) {
		if (entry == null) return null;
		return entry.get(POS_VALUE);
	}

	/**
	 * Gets the type tag from an entry, or -1 if null/tombstone
	 */
	public static long getType(AVector<ACell> entry) {
		if (entry == null) return -1;
		ACell type = entry.get(POS_TYPE);
		if (type == null) return -1;
		return ((CVMLong) type).longValue();
	}

	/**
	 * Gets the type tag as a CVMLong, or null for tombstones
	 */
	public static CVMLong getTypeCell(AVector<ACell> entry) {
		if (entry == null) return null;
		return (CVMLong) entry.get(POS_TYPE);
	}

	/**
	 * Gets the modification timestamp from an entry
	 */
	public static CVMLong getUTime(AVector<ACell> entry) {
		if (entry == null) return null;
		return (CVMLong) entry.get(POS_UTIME);
	}

	/**
	 * Gets the expiry timestamp from an entry (0 = no expiry)
	 */
	public static CVMLong getExpire(AVector<ACell> entry) {
		if (entry == null) return null;
		return (CVMLong) entry.get(POS_EXPIRE);
	}

	/**
	 * Returns true if the entry is a tombstone (deleted)
	 */
	public static boolean isTombstone(AVector<ACell> entry) {
		if (entry == null) return false;
		return entry.get(POS_TYPE) == null && entry.get(POS_VALUE) == null;
	}

	/**
	 * Returns true if the entry has expired at the given time
	 */
	public static boolean isExpired(AVector<ACell> entry, long currentTimeMillis) {
		if (entry == null) return false;
		CVMLong expire = getExpire(entry);
		if (expire == null) return false;
		long exp = expire.longValue();
		return exp > 0 && currentTimeMillis >= exp;
	}

	/**
	 * Returns true if the entry is live (not tombstone and not expired)
	 */
	public static boolean isLive(AVector<ACell> entry, long currentTimeMillis) {
		if (entry == null) return false;
		if (isTombstone(entry)) return false;
		return !isExpired(entry, currentTimeMillis);
	}

	/**
	 * Returns the type name as a string, or null for tombstones
	 */
	public static String typeName(AVector<ACell> entry) {
		long type = getType(entry);
		switch ((int) type) {
			case (int) TYPE_STRING: return "string";
			case (int) TYPE_HASH: return "hash";
			case (int) TYPE_SET: return "set";
			case (int) TYPE_SORTED_SET: return "zset";
			case (int) TYPE_LIST: return "list";
			case (int) TYPE_COUNTER: return "counter";
			default: return null;
		}
	}

	/**
	 * Checks if an entry is a valid KV entry structure
	 */
	public static boolean isValid(AVector<ACell> entry) {
		if (entry == null) return false;
		if (entry.count() < ENTRY_LENGTH) return false;
		ACell utime = entry.get(POS_UTIME);
		return (utime instanceof CVMLong);
	}

	/**
	 * Sets the expiry timestamp on an entry
	 */
	public static AVector<ACell> withExpiry(AVector<ACell> entry, CVMLong expire) {
		return entry.assoc(POS_EXPIRE, expire);
	}

}
