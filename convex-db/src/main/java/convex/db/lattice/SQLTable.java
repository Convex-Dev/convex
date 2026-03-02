package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Utility class for SQL table state in the table store lattice.
 *
 * <p>A table entry is a vector: [schema, rows, utime]
 * <ul>
 *   <li>schema (AVector) - Column definitions: [[name, type], ...]</li>
 *   <li>rows (Index) - Row data: primary-key (ABlob) → row entry</li>
 *   <li>utime (CVMLong) - Schema update timestamp for LWW</li>
 * </ul>
 *
 * <p>Schema is immutable after creation (for now). Row data merges independently.
 */
public class SQLTable {

	/** Position of schema in table vector */
	public static final int POS_SCHEMA = 0;
	/** Position of rows in table vector */
	public static final int POS_ROWS = 1;
	/** Position of update timestamp */
	public static final int POS_UTIME = 2;

	private SQLTable() {}

	/**
	 * Creates a new table with the given schema.
	 *
	 * @param schema Column definitions: [[name, type], ...]
	 * @param timestamp Creation timestamp
	 * @return Table state vector
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> create(AVector<AVector<ACell>> schema, CVMLong timestamp) {
		return Vectors.of(schema, (Index<ABlob, AVector<ACell>>) Index.EMPTY, timestamp);
	}

	/**
	 * Creates a tombstone entry for a dropped table.
	 *
	 * @param timestamp Drop timestamp
	 * @return Tombstone table entry
	 */
	public static AVector<ACell> createTombstone(CVMLong timestamp) {
		return Vectors.of(null, null, timestamp);
	}

	/**
	 * Gets the schema from a table entry.
	 *
	 * @param table Table state vector
	 * @return Schema vector, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public static AVector<AVector<ACell>> getSchema(AVector<ACell> table) {
		if (table == null) return null;
		return (AVector<AVector<ACell>>) table.get(POS_SCHEMA);
	}

	/**
	 * Gets the rows from a table entry.
	 *
	 * @param table Table state vector
	 * @return Row index, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public static Index<ABlob, AVector<ACell>> getRows(AVector<ACell> table) {
		if (table == null) return null;
		return (Index<ABlob, AVector<ACell>>) table.get(POS_ROWS);
	}

	/**
	 * Gets the update timestamp from a table entry.
	 *
	 * @param table Table state vector
	 * @return Update timestamp
	 */
	public static CVMLong getTimestamp(AVector<ACell> table) {
		if (table == null) return null;
		return (CVMLong) table.get(POS_UTIME);
	}

	/**
	 * Checks if a table entry is a tombstone (dropped).
	 *
	 * @param table Table state vector
	 * @return true if tombstone
	 */
	public static boolean isTombstone(AVector<ACell> table) {
		if (table == null) return false;
		return table.get(POS_SCHEMA) == null;
	}

	/**
	 * Checks if a table entry is live (not dropped).
	 *
	 * @param table Table state vector
	 * @return true if live
	 */
	public static boolean isLive(AVector<ACell> table) {
		return table != null && !isTombstone(table);
	}

	/**
	 * Returns a new table state with updated rows.
	 *
	 * @param table Original table state
	 * @param rows New row index
	 * @param timestamp Update timestamp
	 * @return Updated table state
	 */
	public static AVector<ACell> withRows(AVector<ACell> table, Index<ABlob, AVector<ACell>> rows, CVMLong timestamp) {
		AVector<AVector<ACell>> schema = getSchema(table);
		return Vectors.of(schema, rows, timestamp);
	}

	/**
	 * Gets the column index for a column name.
	 *
	 * @param table Table state vector
	 * @param columnName Column name to find
	 * @return Column index, or -1 if not found
	 */
	public static int getColumnIndex(AVector<ACell> table, AString columnName) {
		AVector<AVector<ACell>> schema = getSchema(table);
		if (schema == null) return -1;
		for (int i = 0; i < schema.count(); i++) {
			AVector<ACell> col = schema.get(i);
			if (columnName.equals(col.get(0))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Gets the number of columns in the table.
	 *
	 * @param table Table state vector
	 * @return Column count
	 */
	public static long getColumnCount(AVector<ACell> table) {
		AVector<AVector<ACell>> schema = getSchema(table);
		if (schema == null) return 0;
		return schema.count();
	}

	/**
	 * Gets the number of live rows in the table.
	 *
	 * @param table Table state vector
	 * @return Live row count
	 */
	public static long getRowCount(AVector<ACell> table) {
		Index<ABlob, AVector<ACell>> rows = getRows(table);
		if (rows == null) return 0;
		long count = 0;
		for (var entry : rows.entrySet()) {
			if (SQLRow.isLive(entry.getValue())) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Merges two table entries.
	 * Schema uses LWW (latest timestamp wins).
	 * Rows merge using TableLattice.
	 *
	 * @param a First table entry
	 * @param b Second table entry
	 * @return Merged table entry
	 */
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		if (a == null) return b;
		if (b == null) return a;

		CVMLong timeA = getTimestamp(a);
		CVMLong timeB = getTimestamp(b);

		// Determine schema winner (LWW)
		AVector<ACell> schemaWinner;
		if (timeA == null && timeB == null) {
			schemaWinner = a;
		} else if (timeA == null) {
			schemaWinner = b;
		} else if (timeB == null) {
			schemaWinner = a;
		} else if (timeB.longValue() > timeA.longValue()) {
			schemaWinner = b;
		} else {
			schemaWinner = a;
		}

		// If schema winner is tombstone, return tombstone
		if (isTombstone(schemaWinner)) {
			return schemaWinner;
		}

		// Merge rows
		Index<ABlob, AVector<ACell>> rowsA = getRows(a);
		Index<ABlob, AVector<ACell>> rowsB = getRows(b);
		Index<ABlob, AVector<ACell>> mergedRows = TableLattice.INSTANCE.merge(rowsA, rowsB);

		// Return merged table with schema winner's schema
		return Vectors.of(getSchema(schemaWinner), mergedRows, getTimestamp(schemaWinner));
	}
}
