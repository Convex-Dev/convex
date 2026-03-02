package convex.lattice.queue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Static utility class for working with queue entry structures.
 *
 * Each entry is a Kafka-compatible record stored as an AVector&lt;ACell&gt; with positional fields:
 * <ul>
 *   <li>POS_KEY (0) - Record key (nullable, for partitioning/compaction)</li>
 *   <li>POS_VALUE (1) - Record value (the payload)</li>
 *   <li>POS_TIMESTAMP (2) - Producer timestamp (epoch millis)</li>
 *   <li>POS_HEADERS (3) - Record headers as keyword map (nullable)</li>
 * </ul>
 */
public class QueueEntry {

	public static final long ENTRY_LENGTH = 4;

	public static final int POS_KEY = 0;
	public static final int POS_VALUE = 1;
	public static final int POS_TIMESTAMP = 2;
	public static final int POS_HEADERS = 3;

	/**
	 * Creates a full queue entry with all fields.
	 *
	 * @param key Record key (may be null)
	 * @param value Record value
	 * @param timestamp Producer timestamp
	 * @param headers Record headers (may be null)
	 * @return New entry vector
	 */
	public static AVector<ACell> create(ACell key, ACell value, CVMLong timestamp, AHashMap<ACell, ACell> headers) {
		return Vectors.of(key, value, timestamp, headers);
	}

	/**
	 * Creates an entry with key, value and timestamp (no headers).
	 *
	 * @param key Record key (may be null)
	 * @param value Record value
	 * @param timestamp Producer timestamp
	 * @return New entry vector
	 */
	public static AVector<ACell> create(ACell key, ACell value, CVMLong timestamp) {
		return Vectors.of(key, value, timestamp, null);
	}

	/**
	 * Creates an entry with value and timestamp (no key or headers).
	 *
	 * @param value Record value
	 * @param timestamp Producer timestamp
	 * @return New entry vector
	 */
	public static AVector<ACell> create(ACell value, CVMLong timestamp) {
		return Vectors.of(null, value, timestamp, null);
	}

	/**
	 * Gets the record key from an entry.
	 */
	public static ACell getKey(AVector<ACell> entry) {
		if (entry == null) return null;
		return entry.get(POS_KEY);
	}

	/**
	 * Gets the record value from an entry.
	 */
	public static ACell getValue(AVector<ACell> entry) {
		if (entry == null) return null;
		return entry.get(POS_VALUE);
	}

	/**
	 * Gets the producer timestamp from an entry.
	 */
	public static CVMLong getTimestamp(AVector<ACell> entry) {
		if (entry == null) return null;
		return (CVMLong) entry.get(POS_TIMESTAMP);
	}

	/**
	 * Gets the record headers from an entry.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, ACell> getHeaders(AVector<ACell> entry) {
		if (entry == null) return null;
		return (AHashMap<ACell, ACell>) entry.get(POS_HEADERS);
	}

	/**
	 * Checks if an entry is a valid queue entry structure.
	 */
	public static boolean isValid(AVector<ACell> entry) {
		if (entry == null) return false;
		if (entry.count() < ENTRY_LENGTH) return false;
		ACell ts = entry.get(POS_TIMESTAMP);
		return (ts instanceof CVMLong);
	}
}
