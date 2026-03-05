package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * A single SQL table within the lattice table store.
 *
 * <p>Wraps the underlying state vector: [schema, rows, utime]
 * <ul>
 *   <li>schema (AVector) - Column definitions: [[name, type, precision, scale], ...]</li>
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

	private final AVector<ACell> state;

	private SQLTable(AVector<ACell> state) {
		this.state = state;
	}

	/**
	 * Wraps a table state vector. Returns null if the state is null.
	 */
	public static SQLTable wrap(AVector<ACell> state) {
		if (state == null) return null;
		return new SQLTable(state);
	}

	/**
	 * Returns the underlying state vector.
	 */
	public AVector<ACell> getState() {
		return state;
	}

	// ========== Factory Methods ==========

	/**
	 * Creates a new live table with the given schema.
	 */
	@SuppressWarnings("unchecked")
	public static SQLTable create(AVector<AVector<ACell>> schema, CVMLong timestamp) {
		return new SQLTable(Vectors.of(schema, (Index<ABlob, AVector<ACell>>) Index.EMPTY, timestamp));
	}

	/**
	 * Creates a tombstone entry for a dropped table.
	 */
	public static SQLTable createTombstone(CVMLong timestamp) {
		return new SQLTable(Vectors.of(null, null, timestamp));
	}

	// ========== Instance Methods ==========

	/**
	 * Gets the schema from this table.
	 *
	 * @return Schema vector, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public AVector<AVector<ACell>> getSchema() {
		return (AVector<AVector<ACell>>) state.get(POS_SCHEMA);
	}

	/**
	 * Gets the rows from this table.
	 *
	 * @return Row index, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> getRows() {
		return (Index<ABlob, AVector<ACell>>) state.get(POS_ROWS);
	}

	/**
	 * Gets the update timestamp.
	 */
	public CVMLong getTimestamp() {
		return (CVMLong) state.get(POS_UTIME);
	}

	/**
	 * Checks if this table is a tombstone (dropped).
	 */
	public boolean isTombstone() {
		return state.get(POS_SCHEMA) == null;
	}

	/**
	 * Checks if this table is live (not dropped).
	 */
	public boolean isLive() {
		return !isTombstone();
	}

	/**
	 * Returns a new table with updated rows.
	 */
	public SQLTable withRows(Index<ABlob, AVector<ACell>> rows, CVMLong timestamp) {
		return new SQLTable(Vectors.of(getSchema(), rows, timestamp));
	}

	/**
	 * Gets the column index for a column name.
	 *
	 * @return Column index, or -1 if not found
	 */
	public int getColumnIndex(AString columnName) {
		AVector<AVector<ACell>> schema = getSchema();
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
	 * Gets the number of columns.
	 */
	public long getColumnCount() {
		AVector<AVector<ACell>> schema = getSchema();
		if (schema == null) return 0;
		return schema.count();
	}

	/**
	 * Gets the number of live rows.
	 */
	public long getRowCount() {
		Index<ABlob, AVector<ACell>> rows = getRows();
		if (rows == null) return 0;
		long count = 0;
		for (var entry : rows.entrySet()) {
			if (SQLRow.isLive(entry.getValue())) {
				count++;
			}
		}
		return count;
	}

	// ========== Static Merge (used by lattice layer) ==========

	/**
	 * Merges two table state vectors.
	 * Schema uses LWW (latest timestamp wins).
	 * Rows merge using TableLattice.
	 */
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		if (a == null) return b;
		if (b == null) return a;

		SQLTable ta = new SQLTable(a);
		SQLTable tb = new SQLTable(b);

		CVMLong timeA = ta.getTimestamp();
		CVMLong timeB = tb.getTimestamp();

		// Determine schema winner (LWW)
		SQLTable schemaWinner;
		if (timeA == null && timeB == null) {
			schemaWinner = ta;
		} else if (timeA == null) {
			schemaWinner = tb;
		} else if (timeB == null) {
			schemaWinner = ta;
		} else if (timeB.longValue() > timeA.longValue()) {
			schemaWinner = tb;
		} else {
			schemaWinner = ta;
		}

		// If schema winner is tombstone, return tombstone
		if (schemaWinner.isTombstone()) {
			return schemaWinner.state;
		}

		// Merge rows
		Index<ABlob, AVector<ACell>> rowsA = ta.getRows();
		Index<ABlob, AVector<ACell>> rowsB = tb.getRows();
		Index<ABlob, AVector<ACell>> mergedRows = TableLattice.INSTANCE.merge(rowsA, rowsB);

		// Return merged table with schema winner's schema
		return Vectors.of(schemaWinner.getSchema(), mergedRows, schemaWinner.getTimestamp());
	}
}
