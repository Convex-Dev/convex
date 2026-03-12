package convex.db.lattice;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Utility class for SQL row entries in the table lattice.
 *
 * <p>A row entry is a vector: [values, utime, deleted]
 * <ul>
 *   <li>values (AVector) - Column values for this row</li>
 *   <li>utime (CVMLong) - Update timestamp for LWW conflict resolution</li>
 *   <li>deleted (CVMLong) - Deletion timestamp, or null if live</li>
 * </ul>
 *
 * <p>Merge semantics: Latest timestamp wins. If timestamps equal, deletion wins.
 */
public class SQLRow {

	/** Position of values in the row vector */
	static final int POS_VALUES = 0;
	/** Position of update timestamp */
	static final int POS_UTIME = 1;
	/** Position of deletion timestamp */
	static final int POS_DELETED = 2;

	private SQLRow() {}

	/**
	 * Creates a new live row entry with the given values.
	 *
	 * @param values Column values for the row
	 * @param timestamp Update timestamp
	 * @return Row entry vector
	 */
	public static AVector<ACell> create(AVector<ACell> values, CVMLong timestamp) {
		return Vectors.of(values, timestamp, null);
	}

	/**
	 * Creates a tombstone entry for a deleted row.
	 *
	 * @param timestamp Deletion timestamp
	 * @return Tombstone row entry
	 */
	public static AVector<ACell> createTombstone(CVMLong timestamp) {
		return Vectors.of(null, timestamp, timestamp);
	}

	/**
	 * Gets the column values from a row entry.
	 *
	 * @param row Row entry vector
	 * @return Column values, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> getValues(AVector<ACell> row) {
		if (row == null) return null;
		return (AVector<ACell>) row.get(POS_VALUES);
	}

	/**
	 * Gets the update timestamp from a row entry.
	 *
	 * @param row Row entry vector
	 * @return Update timestamp
	 */
	public static CVMLong getTimestamp(AVector<ACell> row) {
		if (row == null) return null;
		return (CVMLong) row.get(POS_UTIME);
	}

	/**
	 * Gets the deletion timestamp from a row entry.
	 *
	 * @param row Row entry vector
	 * @return Deletion timestamp, or null if live
	 */
	public static CVMLong getDeleted(AVector<ACell> row) {
		if (row == null) return null;
		return (CVMLong) row.get(POS_DELETED);
	}

	/**
	 * Checks if a row entry is a tombstone (deleted).
	 *
	 * @param row Row entry vector
	 * @return true if tombstone
	 */
	public static boolean isTombstone(AVector<ACell> row) {
		if (row == null) return false;
		return row.get(POS_DELETED) != null;
	}

	/**
	 * Checks if a row entry is live (not deleted).
	 *
	 * @param row Row entry vector
	 * @return true if live
	 */
	public static boolean isLive(AVector<ACell> row) {
		return row != null && !isTombstone(row);
	}

	/**
	 * Updates a row entry with new values, preserving the timestamp.
	 *
	 * @param row Original row entry
	 * @param values New column values
	 * @param timestamp Update timestamp
	 * @return Updated row entry
	 */
	public static AVector<ACell> withValues(AVector<ACell> row, AVector<ACell> values, CVMLong timestamp) {
		return Vectors.of(values, timestamp, null);
	}

	/**
	 * Merges two row entries using LWW semantics.
	 * Latest timestamp wins. If equal, deletion wins.
	 *
	 * @param a First row entry
	 * @param b Second row entry
	 * @return Merged row entry
	 */
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		if (a == null) return b;
		if (b == null) return a;

		CVMLong timeA = getTimestamp(a);
		CVMLong timeB = getTimestamp(b);

		if (timeA == null && timeB == null) return a;
		if (timeA == null) return b;
		if (timeB == null) return a;

		long ta = timeA.longValue();
		long tb = timeB.longValue();

		if (ta > tb) return a;
		if (tb > ta) return b;

		// Equal timestamps: deletion wins
		if (isTombstone(b)) return b;
		return a;
	}
}
