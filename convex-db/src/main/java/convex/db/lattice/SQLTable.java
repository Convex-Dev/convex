package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * A single SQL table within the lattice table store.
 *
 * <p>Wraps a lattice cursor pointing at the table's state vector: [schema, rows, utime]
 * <ul>
 *   <li>schema (AVector) - Column definitions: [[name, type, precision, scale], ...]</li>
 *   <li>rows (Index) - Row data: primary-key (ABlob) → row entry</li>
 *   <li>utime (CVMLong) - Schema update timestamp for LWW</li>
 * </ul>
 *
 * <p>Schema is immutable after creation (for now). Row data merges independently.
 *
 * <p>Obtained from {@link SQLSchema#getTable(String)} as a cursor-backed component
 * in the hierarchy: ConvexDB → SQLDatabase → SQLSchema → SQLTable.
 */
public class SQLTable extends ALatticeComponent<AVector<ACell>> {

	/** Position of schema in table vector */
	static final int POS_SCHEMA = 0;
	/** Position of rows in table vector */
	static final int POS_ROWS = 1;
	/** Position of update timestamp */
	static final int POS_UTIME = 2;

	SQLTable(ALatticeCursor<AVector<ACell>> cursor) {
		super(cursor);
	}

	// ========== Static State Factories ==========

	/**
	 * Creates the initial state vector for a new live table.
	 *
	 * @param schema Column definitions
	 * @param timestamp Creation timestamp
	 * @return State vector [schema, empty-rows, timestamp]
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> createState(AVector<AVector<ACell>> schema, CVMLong timestamp) {
		return Vectors.of(schema, (Index<ABlob, AVector<ACell>>) Index.EMPTY, timestamp);
	}

	/**
	 * Creates a tombstone state vector for a dropped table.
	 *
	 * @param timestamp Deletion timestamp
	 * @return Tombstone state vector [null, null, timestamp]
	 */
	static AVector<ACell> createTombstoneState(CVMLong timestamp) {
		return Vectors.of(null, null, timestamp);
	}

	// ========== Static Helpers (for raw state access) ==========

	/**
	 * Checks if a raw table state represents a live (non-tombstone) table.
	 */
	static boolean isLiveState(AVector<ACell> state) {
		return state != null && state.get(POS_SCHEMA) != null;
	}

	// ========== Cursor-backed Instance Methods ==========

	/**
	 * Returns the underlying state vector.
	 */
	public AVector<ACell> getState() {
		return cursor.get();
	}

	/**
	 * Gets the schema from this table.
	 *
	 * @return Schema vector, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public AVector<AVector<ACell>> getSchema() {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;
		return (AVector<AVector<ACell>>) state.get(POS_SCHEMA);
	}

	/**
	 * Gets the rows from this table.
	 *
	 * @return Row index, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> getRows() {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;
		return (Index<ABlob, AVector<ACell>>) state.get(POS_ROWS);
	}

	/**
	 * Gets the update timestamp.
	 */
	public CVMLong getTimestamp() {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;
		return (CVMLong) state.get(POS_UTIME);
	}

	/**
	 * Checks if this table is a tombstone (dropped).
	 */
	public boolean isTombstone() {
		AVector<ACell> state = cursor.get();
		return state != null && state.get(POS_SCHEMA) == null;
	}

	/**
	 * Checks if this table is live (not dropped).
	 */
	public boolean isLive() {
		return isLiveState(cursor.get());
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

	// ========== Mutation Methods ==========

	/**
	 * Inserts a row into this table.
	 *
	 * @param pk Primary key (blob-encoded)
	 * @param values Full row values (including PK as first element)
	 * @param timestamp Insert timestamp
	 * @return true if inserted successfully
	 */
	@SuppressWarnings("unchecked")
	public boolean insertRow(ABlob pk, AVector<ACell> values, CVMLong timestamp) {
		boolean[] result = new boolean[1];
		cursor.updateAndGet(state -> {
			if (state == null || state.get(POS_SCHEMA) == null) return state;
			Index<ABlob, AVector<ACell>> rows = (Index<ABlob, AVector<ACell>>) state.get(POS_ROWS);
			if (rows == null) rows = TableLattice.INSTANCE.zero();
			rows = rows.assoc(pk, SQLRow.create(values, timestamp));
			result[0] = true;
			return Vectors.of(state.get(POS_SCHEMA), rows, timestamp);
		});
		return result[0];
	}

	/**
	 * Deletes a row by primary key.
	 *
	 * @param key Primary key (blob-encoded)
	 * @param timestamp Deletion timestamp
	 * @return true if a live row was deleted
	 */
	@SuppressWarnings("unchecked")
	public boolean deleteRow(ABlob key, CVMLong timestamp) {
		boolean[] result = new boolean[1];
		cursor.updateAndGet(state -> {
			if (state == null || state.get(POS_SCHEMA) == null) return state;
			Index<ABlob, AVector<ACell>> rows = (Index<ABlob, AVector<ACell>>) state.get(POS_ROWS);
			if (rows == null) return state;
			AVector<ACell> row = rows.get(key);
			if (row == null || !SQLRow.isLive(row)) return state;
			rows = rows.assoc(key, SQLRow.createTombstone(timestamp));
			result[0] = true;
			return Vectors.of(state.get(POS_SCHEMA), rows, timestamp);
		});
		return result[0];
	}

	// ========== Static Merge (used by lattice layer) ==========

	/**
	 * Merges two table state vectors.
	 * Schema uses LWW (latest timestamp wins).
	 * Rows merge using TableLattice.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		if (a == null) return b;
		if (b == null) return a;

		CVMLong timeA = (CVMLong) a.get(POS_UTIME);
		CVMLong timeB = (CVMLong) b.get(POS_UTIME);

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
		if (schemaWinner.get(POS_SCHEMA) == null) {
			return schemaWinner;
		}

		// Merge rows
		Index<ABlob, AVector<ACell>> rowsA = (Index<ABlob, AVector<ACell>>) a.get(POS_ROWS);
		Index<ABlob, AVector<ACell>> rowsB = (Index<ABlob, AVector<ACell>>) b.get(POS_ROWS);
		Index<ABlob, AVector<ACell>> mergedRows = TableLattice.INSTANCE.merge(rowsA, rowsB);

		// Return merged table with schema winner's schema
		return Vectors.of(schemaWinner.get(POS_SCHEMA), mergedRows, schemaWinner.get(POS_UTIME));
	}
}
