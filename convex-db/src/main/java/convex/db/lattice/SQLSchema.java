package convex.db.lattice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexType;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;

/**
 * A SQL-like table store built on Convex lattice technology.
 *
 * <p>Provides basic table operations (CREATE TABLE, DROP TABLE, INSERT, SELECT, DELETE)
 * with lattice merge replication for conflict-free distributed operation.
 *
 * <p>Primary keys must be blob-like types (Blob, AString, AccountKey, etc.) as
 * required by the Index data structure for ordering.
 *
 * <p>Usage:
 * <pre>
 * SQLSchema schema = SQLSchema.create();
 *
 * // Create a table
 * schema.createTable("users", new String[]{"id", "name", "email"});
 *
 * // Insert rows (first column is primary key)
 * schema.insert("users", 1, "Alice", "alice@example.com");
 *
 * // Query rows
 * AVector&lt;ACell&gt; row = schema.selectByKey("users", CVMLong.create(1));
 *
 * // Delete rows
 * schema.deleteByKey("users", CVMLong.create(1));
 *
 * // Drop table
 * schema.dropTable("users");
 * </pre>
 */
public class SQLSchema extends ALatticeComponent<Index<AString, AVector<ACell>>> {

	/**
	 * JVM-global monotonic write-sequence counter. Initialised from
	 * {@code System.currentTimeMillis()} so that v4 sequence numbers always sort
	 * after v3 compact-timestamp rows (~1.7e12) in LWW comparisons.
	 * Being static means every write in any schema instance within this process gets
	 * a unique version — no ties are possible even when multiple forks write
	 * concurrently within the same millisecond.
	 */
	private static final AtomicLong WRITE_SEQ = new AtomicLong(System.currentTimeMillis());

	public SQLSchema(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		super(cursor);
	}

	/**
	 * Creates a new empty table store.
	 *
	 * @return New SQLSchema instance
	 */
	public static SQLSchema create() {
		ALatticeCursor<Index<AString, AVector<ACell>>> cursor =
			Cursors.createLattice(TableStoreLattice.INSTANCE);
		return new SQLSchema(cursor);
	}

	/**
	 * Connects to an existing cursor for cursor chain integration.
	 *
	 * @param cursor Lattice cursor (e.g. from a SignedCursor path)
	 * @return New SQLSchema instance connected to the cursor
	 */
	public static SQLSchema connect(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		return new SQLSchema(cursor);
	}

	/**
	 * Creates a forked copy of this store for independent operation.
	 *
	 * @return Forked SQLSchema instance
	 */
	public SQLSchema fork() {
		return new SQLSchema(cursor.fork());
	}

	// ========== Internal Helpers ==========

	/** Returns the next unique write-sequence number for LWW ordering. */
	private CVMLong now() {
		return CVMLong.create(WRITE_SEQ.incrementAndGet());
	}

	/**
	 * Gets a cursor-backed table by name, returning null if not found.
	 */
	public SQLTable getTable(AString name) {
		ALatticeCursor<AVector<ACell>> tableCursor = cursor.path(name);
		if (tableCursor.get() == null) return null;
		return new SQLTable(tableCursor);
	}

	/** Convenience overload. */
	public SQLTable getTable(String name) {
		return getTable(Strings.create(name));
	}

	/**
	 * Gets a live (non-tombstone) table, returning null if not found or dropped.
	 */
	public SQLTable getLiveTable(AString name) {
		SQLTable table = getTable(name);
		if (table == null || !table.isLive()) return null;
		return table;
	}

	/** Convenience overload. */
	public SQLTable getLiveTable(String name) {
		return getLiveTable(Strings.create(name));
	}

	/**
	 * Converts an ACell to ABlob for use as primary key.
	 * Supports: ABlob (direct), CVMLong (8-byte encoding), AString (UTF-8 bytes).
	 *
	 * @param key The key to convert
	 * @return ABlob representation
	 * @throws IllegalArgumentException if key type not supported
	 */
	protected ABlob toKey(ACell key) {
		if (key instanceof ABlob blob) return blob;
		if (key instanceof CVMLong n) {
			// Encode as 8-byte big-endian
			long v = n.longValue();
			byte[] bytes = new byte[8];
			for (int i = 7; i >= 0; i--) {
				bytes[i] = (byte) (v & 0xFF);
				v >>= 8;
			}
			return Blob.wrap(bytes);
		}
		if (key instanceof AString s) {
			return Blob.wrap(s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		throw new IllegalArgumentException("Unsupported primary key type: " + key.getClass().getSimpleName());
	}

	// ========== Table Operations ==========

	/**
	 * Creates a new table with the given column names.
	 * All columns use ANY type (dynamic typing).
	 */
	public boolean createTable(String name, String[] columns) {
		return createTable(Strings.create(name), columns);
	}

	/** Creates a new table with explicitly typed columns (no precision/scale). */
	public boolean createTable(String name, String[] columns, ConvexType[] types) {
		return createTable(Strings.create(name), columns, types);
	}

	/** Creates a new table with fully typed columns (including precision/scale). */
	public boolean createTable(String name, String[] columns, ConvexColumnType[] types) {
		return createTable(Strings.create(name), columns, types);
	}

	public boolean createTable(AString name, String[] columns) {
		ConvexColumnType[] types = new ConvexColumnType[columns.length];
		for (int i = 0; i < types.length; i++) {
			types[i] = ConvexColumnType.of(ConvexType.ANY);
		}
		return createTable(name, columns, types);
	}

	public boolean createTable(AString name, String[] columns, ConvexType[] types) {
		ConvexColumnType[] columnTypes = new ConvexColumnType[types.length];
		for (int i = 0; i < types.length; i++) {
			columnTypes[i] = ConvexColumnType.of(types[i]);
		}
		return createTable(name, columns, columnTypes);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public boolean createTable(AString name, String[] columns, ConvexColumnType[] types) {
		if (columns.length != types.length) {
			throw new IllegalArgumentException("Columns and types must have same length");
		}

		// Build full desired schema: [[name, typeName, precision, scale], ...]
		AVector newSchema = Vectors.empty();
		for (int i = 0; i < columns.length; i++) {
			ConvexColumnType ct = types[i];
			AString typeName = (ct.getBaseType() == ConvexType.ANY) ? null : Strings.create(ct.getBaseType().name());
			CVMLong precision = ct.hasPrecision() ? CVMLong.create(ct.getPrecision()) : null;
			CVMLong scale = ct.hasScale() ? CVMLong.create(ct.getScale()) : null;
			newSchema = newSchema.append(Vectors.of(Strings.create(columns[i]), typeName, precision, scale));
		}

		SQLTable existing = getLiveTable(name);
		if (existing == null) {
			// Table does not exist yet: create fresh
			ALatticeCursor<AVector<ACell>> tableCursor = cursor.path(name);
			tableCursor.set(SQLTable.createState((AVector<AVector<ACell>>) newSchema, now()));
			return true;
		}

		// Table already exists: append any new columns not present in stored schema
		AVector<AVector<ACell>> existingSchema = existing.getSchema();
		long existingCount = existingSchema != null ? existingSchema.count() : 0;
		if (newSchema.count() <= existingCount) return false; // no new columns to add

		AVector combinedSchema = existingSchema;
		for (long i = existingCount; i < newSchema.count(); i++) {
			combinedSchema = combinedSchema.append(newSchema.get(i));
		}
		final AVector<AVector<ACell>> finalSchema = (AVector<AVector<ACell>>) combinedSchema;
		ALatticeCursor<AVector<ACell>> tableCursor = cursor.path(name);
		tableCursor.updateAndGet(state -> state.assoc(SQLTable.POS_SCHEMA, finalSchema));
		return true;
	}

	/** Drops a table by creating a tombstone. */
	public boolean dropTable(String name) {
		return dropTable(Strings.create(name));
	}

	public boolean dropTable(AString name) {
		if (getLiveTable(name) == null) return false;
		ALatticeCursor<AVector<ACell>> tableCursor = cursor.path(name);
		tableCursor.set(SQLTable.createTombstoneState(now()));
		return true;
	}

	/** Checks if a table exists and is live. */
	public boolean tableExists(String name) {
		return tableExists(Strings.create(name));
	}

	public boolean tableExists(AString name) {
		return getLiveTable(name) != null;
	}

	/** Gets the schema (column definitions) for a table. */
	public AVector<AVector<ACell>> getSchema(String name) {
		return getSchema(Strings.create(name));
	}

	public AVector<AVector<ACell>> getSchema(AString name) {
		SQLTable table = getLiveTable(name);
		if (table == null) return null;
		return table.getSchema();
	}

	/** Gets the column names for a table. */
	public String[] getColumnNames(String name) {
		return getColumnNames(Strings.create(name));
	}

	public String[] getColumnNames(AString name) {
		AVector<AVector<ACell>> schema = getSchema(name);
		if (schema == null) return null;
		String[] result = new String[(int) schema.count()];
		for (int i = 0; i < result.length; i++) {
			result[i] = schema.get(i).get(0).toString();
		}
		return result;
	}

	/** Gets the column types for a table (with precision/scale). */
	public ConvexColumnType[] getColumnTypes(String name) {
		return getColumnTypes(Strings.create(name));
	}

	public ConvexColumnType[] getColumnTypes(AString name) {
		AVector<AVector<ACell>> schema = getSchema(name);
		if (schema == null) return null;
		ConvexColumnType[] result = new ConvexColumnType[(int) schema.count()];
		for (int i = 0; i < result.length; i++) {
			AVector<ACell> colDef = schema.get(i);
			ACell typeCell = colDef.get(1);

			ConvexType baseType;
			if (typeCell == null) {
				baseType = ConvexType.ANY;
			} else {
				baseType = ConvexType.fromName(typeCell.toString());
			}

			// Read precision and scale (may be null or missing for old schemas)
			int precision = -1;
			int scale = -1;
			if (colDef.count() > 2 && colDef.get(2) instanceof CVMLong p) {
				precision = (int) p.longValue();
			}
			if (colDef.count() > 3 && colDef.get(3) instanceof CVMLong s) {
				scale = (int) s.longValue();
			}

			if (scale >= 0) {
				result[i] = ConvexColumnType.withScale(baseType, precision, scale);
			} else if (precision >= 0) {
				result[i] = ConvexColumnType.withPrecision(baseType, precision);
			} else {
				result[i] = ConvexColumnType.of(baseType);
			}
		}
		return result;
	}

	/** Gets the row count for a table. */
	public long getRowCount(String name) {
		return getRowCount(Strings.create(name));
	}

	public long getRowCount(AString name) {
		SQLTable table = getLiveTable(name);
		if (table == null) return 0;
		return table.getRowCount();
	}

	// ========== Row Operations ==========

	/** Inserts a row into a table. First column is used as primary key. */
	public boolean insert(String tableName, AVector<ACell> row) {
		return insert(Strings.create(tableName), row);
	}

	public boolean insert(AString tableName, AVector<ACell> row) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return false;
		ABlob pk = toKey(row.get(0));
		return table.insertRow(pk, row, now());
	}

	/** Inserts a row with auto-conversion from Java types. First value is primary key. */
	public boolean insert(String tableName, Object... values) {
		return insert(Strings.create(tableName), Vectors.of(values));
	}

	/**
	 * Batch-inserts rows into a table in a single atomic lattice update.
	 *
	 * <p>Rows are sorted by primary key, grouped by block-key prefix, and written
	 * with one {@code Index.assoc()} per block rather than one per row. This
	 * reduces intermediate allocation from O(N × trie-depth) to O(blocks × trie-depth).
	 *
	 * @param tableName Target table (must exist and be live)
	 * @param rows      Rows to insert; first element of each row is the primary key
	 * @return number of newly-live rows added
	 */
	public int insertAll(String tableName, List<AVector<ACell>> rows) {
		return insertAll(Strings.create(tableName), rows);
	}

	public int insertAll(AString tableName, List<AVector<ACell>> rows) {
		if (rows == null || rows.isEmpty()) return 0;
		SQLTable table = getLiveTable(tableName);
		if (table == null) return 0;
		CVMLong ts = now();
		List<Map.Entry<ABlob, AVector<ACell>>> sorted = new ArrayList<>(rows.size());
		for (AVector<ACell> row : rows) {
			sorted.add(Map.entry(toKey(row.get(0)), row));
		}
		sorted.sort(Map.Entry.comparingByKey());
		return table.insertRows(sorted, ts);
	}

	/** Selects a row by primary key. */
	public AVector<ACell> selectByKey(String tableName, ACell primaryKey) {
		return selectByKey(Strings.create(tableName), primaryKey);
	}

	public AVector<ACell> selectByKey(AString tableName, ACell primaryKey) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return null;

		Index<ABlob, ACell> rows = table.getRows();
		if (rows == null) return null;

		ABlob pk = toKey(primaryKey);
		ABlob bk = RowBlock.blockKey(pk);
		ACell block = rows.get(bk);
		AVector<ACell> row = RowBlock.get(block, pk);
		if (row == null || !SQLRow.isLive(row)) return null;
		return SQLRow.getValues(row);
	}

	/** Deletes a row by primary key. */
	public boolean deleteByKey(String tableName, ACell primaryKey) {
		return deleteByKey(Strings.create(tableName), primaryKey);
	}

	public boolean deleteByKey(AString tableName, ACell primaryKey) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return false;
		ABlob key = toKey(primaryKey);
		return table.deleteRow(key, now());
	}

	/** Returns all live rows in a table. */
	public Index<ABlob, AVector<ACell>> selectAll(String tableName) {
		return selectAll(Strings.create(tableName));
	}

	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> selectAll(AString tableName) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return Index.none();

		Index<ABlob, ACell> rows = table.getRows();
		if (rows == null) return Index.none();

		// Iterate blocks, expand live rows keyed by full pk
		Index<ABlob, AVector<ACell>>[] result = new Index[] { Index.none() };
		rows.forEach((bk, block) -> {
			if (RowBlock.isBlock(block)) {
				RowBlock.forEach(block, (pk, row) -> {
					if (SQLRow.isLive(row)) result[0] = result[0].assoc(pk, SQLRow.getValues(row));
				});
			} else if (block instanceof AVector && SQLRow.isLive((AVector<ACell>)block)) {
				// Legacy single-row entry (backward compat)
				result[0] = result[0].assoc(bk, SQLRow.getValues((AVector<ACell>)block));
			}
		});
		return result[0];
	}

	/**
	 * Returns all table names.
	 *
	 * @return Array of table names
	 */
	public String[] getTableNames() {
		Index<AString, AVector<ACell>> store = cursor.get();
		if (store == null) return new String[0];

		java.util.List<String> names = new java.util.ArrayList<>();
		for (var entry : store.entrySet()) {
			if (SQLTable.isLiveState(entry.getValue())) {
				names.add(entry.getKey().toString());
			}
		}
		return names.toArray(new String[0]);
	}

	/** Gets the column count for a table. */
	public int getColumnCount(String name) {
		return getColumnCount(Strings.create(name));
	}

	public int getColumnCount(AString name) {
		SQLTable table = getLiveTable(name);
		if (table == null) return 0;
		return (int) table.getColumnCount();
	}

	// ========== Secondary Index Operations ==========

	/**
	 * Creates a secondary index on a column.
	 * Scans existing rows to build the initial index, then maintains it on
	 * subsequent inserts and deletes.
	 *
	 * @param tableName  Target table (must exist and be live)
	 * @param columnName Column to index (must exist in the table schema)
	 * @return true if the index was created; false if it already exists,
	 *         the table doesn't exist, or the column doesn't exist
	 */
	public boolean createIndex(String tableName, String columnName) {
		return createIndex(Strings.create(tableName), Strings.create(columnName));
	}

	public boolean createIndex(AString tableName, AString columnName) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return false;
		return table.createColumnIndex(columnName);
	}

	/**
	 * Returns true if a secondary index exists on the named column.
	 */
	public boolean hasIndex(String tableName, String columnName) {
		return hasIndex(Strings.create(tableName), Strings.create(columnName));
	}

	public boolean hasIndex(AString tableName, AString columnName) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return false;
		return table.hasColumnIndex(columnName);
	}

	/**
	 * Drops a secondary index on a column.
	 *
	 * @return true if the index was dropped; false if it didn't exist
	 */
	public boolean dropIndex(String tableName, String columnName) {
		return dropIndex(Strings.create(tableName), Strings.create(columnName));
	}

	public boolean dropIndex(AString tableName, AString columnName) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return false;
		return table.dropColumnIndex(columnName);
	}

	/**
	 * Returns all live rows where the named column equals {@code value}.
	 *
	 * <p>If a secondary index exists on the column, uses the index to avoid a
	 * full-table scan.  Otherwise falls back to scanning all rows.
	 *
	 * <p>Results are always re-validated against the row store, so stale index
	 * entries (possible after distributed merge) are silently filtered out.
	 *
	 * @param tableName  Target table
	 * @param columnName Column to filter on
	 * @param value      Value to match (must be the same ACell type as stored)
	 * @return Index of matching rows keyed by primary-key blob, or empty if none
	 */
	public Index<ABlob, AVector<ACell>> selectByColumn(
			String tableName, String columnName, ACell value) {
		return selectByColumn(Strings.create(tableName), Strings.create(columnName), value);
	}

	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> selectByColumn(
			AString tableName, AString columnName, ACell value) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return Index.none();

		// Try index-backed lookup first
		Index<AString, Index<ABlob, ABlob>> allIndices =
			SQLTable.getIndicesFromState(table.getState());
		if (allIndices != null) {
			ACell rawColIdx = allIndices.get(columnName);
			if (rawColIdx instanceof Index) {
				Index<ABlob, ABlob> colIdx = (Index<ABlob, ABlob>) rawColIdx;
				Index<ABlob, AVector<ACell>>[] result = new Index[]{Index.none()};
				colIdx.forEach((indexKey, pk) -> {
					if (!ColumnIndex.matchesValue(indexKey, value)) return;
					ABlob pkBlob = ColumnIndex.extractPk(indexKey);
					AVector<ACell> row = table.selectByKeyBlob(pkBlob);
					if (row == null) return; // tombstoned or missing
					// Re-validate column value (guards against stale index entries)
					AVector<AVector<ACell>> schema = table.getSchema();
					int ci = (schema != null)
						? SQLTable.findColIdxInSchema(schema, columnName) : -1;
					if (ci >= 0 && ci < (int) row.count() && value.equals(row.get(ci))) {
						result[0] = result[0].assoc(pkBlob, row);
					}
				});
				return result[0];
			}
		}

		// Fallback: full-scan with in-memory filter
		AVector<AVector<ACell>> schema = table.getSchema();
		int colIdx = (schema != null)
			? SQLTable.findColIdxInSchema(schema, columnName) : -1;
		if (colIdx < 0) return Index.none();
		Index<ABlob, AVector<ACell>> all = selectAll(tableName);
		Index<ABlob, AVector<ACell>>[] result = new Index[]{Index.none()};
		all.forEach((pk, row) -> {
			if (colIdx < (int) row.count() && value.equals(row.get(colIdx))) {
				result[0] = result[0].assoc(pk, row);
			}
		});
		return result[0];
	}

	/**
	 * Returns all live rows where the named column is in the inclusive range [from, to].
	 *
	 * <p>Uses the secondary index if present (iterates index entries filtering by range).
	 * Falls back to a full-table scan otherwise.
	 *
	 * <p>Range comparison uses sortable encoding: CVMLong values compare numerically;
	 * AString values compare lexicographically by UTF-8 bytes.
	 *
	 * @param tableName  Target table
	 * @param columnName Column to filter on
	 * @param from       Lower bound (inclusive)
	 * @param to         Upper bound (inclusive)
	 * @return Index of matching rows keyed by primary-key blob, or empty if none
	 */
	public Index<ABlob, AVector<ACell>> selectByColumnRange(
			String tableName, String columnName, ACell from, ACell to) {
		return selectByColumnRange(
			Strings.create(tableName), Strings.create(columnName), from, to);
	}

	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> selectByColumnRange(
			AString tableName, AString columnName, ACell from, ACell to) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return Index.none();

		// Try index-backed lookup
		Index<AString, Index<ABlob, ABlob>> allIndices =
			SQLTable.getIndicesFromState(table.getState());
		if (allIndices != null) {
			ACell rawColIdx = allIndices.get(columnName);
			if (rawColIdx instanceof Index) {
				Index<ABlob, ABlob> colIdx = (Index<ABlob, ABlob>) rawColIdx;
				Index<ABlob, AVector<ACell>>[] result = new Index[]{Index.none()};
				AVector<AVector<ACell>> schema = table.getSchema();
				int ci = (schema != null)
					? SQLTable.findColIdxInSchema(schema, columnName) : -1;
				colIdx.forEach((indexKey, pk) -> {
					if (!ColumnIndex.matchesRange(indexKey, from, to)) return;
					ABlob pkBlob = ColumnIndex.extractPk(indexKey);
					AVector<ACell> row = table.selectByKeyBlob(pkBlob);
					if (row == null) return;
					// Re-validate (guards against stale index entries)
					if (ci >= 0 && ci < (int) row.count()) {
						ACell colVal = row.get(ci);
						if (ColumnIndex.matchesRange(
								ColumnIndex.indexKey(colVal, pkBlob), from, to)) {
							result[0] = result[0].assoc(pkBlob, row);
						}
					}
				});
				return result[0];
			}
		}

		// Fallback: full-scan with in-memory filter
		AVector<AVector<ACell>> schema = table.getSchema();
		int colIdx = (schema != null)
			? SQLTable.findColIdxInSchema(schema, columnName) : -1;
		if (colIdx < 0) return Index.none();
		byte[] fromBytes = ColumnIndex.encodeValue(from);
		byte[] toBytes   = ColumnIndex.encodeValue(to);
		Index<ABlob, AVector<ACell>> all = selectAll(tableName);
		Index<ABlob, AVector<ACell>>[] result = new Index[]{Index.none()};
		all.forEach((pk, row) -> {
			if (colIdx >= (int) row.count()) return;
			ACell colVal = row.get(colIdx);
			if (colVal == null) return;
			byte[] valBytes = ColumnIndex.encodeValue(colVal);
			if (valBytes.length != fromBytes.length) return;
			// Lexicographic comparison
			int cmpFrom = 0, cmpTo = 0;
			for (int i = 0; i < valBytes.length; i++) {
				int b = valBytes[i] & 0xFF;
				if (cmpFrom == 0) {
					if      (b > (fromBytes[i] & 0xFF)) cmpFrom = 1;
					else if (b < (fromBytes[i] & 0xFF)) cmpFrom = -1;
				}
				if (cmpTo == 0) {
					if      (b < (toBytes[i] & 0xFF)) cmpTo = -1;
					else if (b > (toBytes[i] & 0xFF)) cmpTo = 1;
				}
			}
			if (cmpFrom >= 0 && cmpTo <= 0) result[0] = result[0].assoc(pk, row);
		});
		return result[0];
	}
}
