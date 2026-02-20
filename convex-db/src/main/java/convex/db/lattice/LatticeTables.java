package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexType;
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
 * LatticeTables tables = LatticeTables.create();
 *
 * // Create a table
 * tables.createTable("users", new String[]{"id", "name", "email"});
 *
 * // Insert rows (primary key must be blob-like)
 * tables.insert("users", Blob.fromHex("01"), Vectors.of(Strings.create("Alice"), Strings.create("alice@example.com")));
 *
 * // Query rows
 * AVector&lt;ACell&gt; row = tables.selectByKey("users", Blob.fromHex("01"));
 *
 * // Delete rows
 * tables.deleteByKey("users", Blob.fromHex("01"));
 *
 * // Drop table
 * tables.dropTable("users");
 * </pre>
 */
public class LatticeTables {

	private final ALatticeCursor<Index<AString, AVector<ACell>>> cursor;

	public LatticeTables(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		this.cursor = cursor;
	}

	/**
	 * Creates a new empty table store.
	 *
	 * @return New LatticeTables instance
	 */
	public static LatticeTables create() {
		ALatticeCursor<Index<AString, AVector<ACell>>> cursor =
			Cursors.createLattice(TableStoreLattice.INSTANCE);
		return new LatticeTables(cursor);
	}

	/**
	 * Connects to an existing cursor for cursor chain integration.
	 *
	 * @param cursor Lattice cursor (e.g. from a SignedCursor path)
	 * @return New LatticeTables instance connected to the cursor
	 */
	public static LatticeTables connect(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		return new LatticeTables(cursor);
	}

	/**
	 * Returns the underlying lattice cursor for direct lattice operations.
	 *
	 * @return Lattice cursor
	 */
	public ALatticeCursor<Index<AString, AVector<ACell>>> cursor() {
		return cursor;
	}

	/**
	 * Creates a forked copy of this store for independent operation.
	 *
	 * @return Forked LatticeTables instance
	 */
	public LatticeTables fork() {
		return new LatticeTables(cursor.fork());
	}

	/**
	 * Syncs this forked store back to its parent, merging changes.
	 */
	public void sync() {
		cursor.sync();
	}

	// ========== Internal Helpers ==========

	private CVMLong now() {
		return CVMLong.create(System.currentTimeMillis());
	}

	private AString tableName(String name) {
		return Strings.create(name);
	}

	private AVector<ACell> getTable(String name) {
		Index<AString, AVector<ACell>> store = cursor.get();
		if (store == null) return null;
		return store.get(tableName(name));
	}

	private AVector<ACell> getLiveTable(String name) {
		AVector<ACell> table = getTable(name);
		if (table == null) return null;
		if (!SQLTable.isLive(table)) return null;
		return table;
	}

	private void putTable(String name, AVector<ACell> table) {
		AString key = tableName(name);
		cursor.updateAndGet(store -> {
			if (store == null) store = TableStoreLattice.INSTANCE.zero();
			return store.assoc(key, table);
		});
	}

	/**
	 * Converts an ACell to ABlob for use as primary key.
	 * Supports: ABlob (direct), CVMLong (8-byte encoding), AString (UTF-8 bytes).
	 *
	 * @param key The key to convert
	 * @return ABlob representation
	 * @throws IllegalArgumentException if key type not supported
	 */
	private ABlob toKey(ACell key) {
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
	 *
	 * @param name Table name
	 * @param columns Column names
	 * @return true if table created, false if already exists
	 */
	public boolean createTable(String name, String[] columns) {
		ConvexColumnType[] types = new ConvexColumnType[columns.length];
		for (int i = 0; i < types.length; i++) {
			types[i] = ConvexColumnType.of(ConvexType.ANY);
		}
		return createTable(name, columns, types);
	}

	/**
	 * Creates a new table with explicitly typed columns (no precision/scale).
	 *
	 * @param name Table name
	 * @param columns Column names
	 * @param types Column base types (must match columns length)
	 * @return true if table created, false if already exists
	 * @throws IllegalArgumentException if columns and types have different lengths
	 */
	public boolean createTable(String name, String[] columns, ConvexType[] types) {
		ConvexColumnType[] columnTypes = new ConvexColumnType[types.length];
		for (int i = 0; i < types.length; i++) {
			columnTypes[i] = ConvexColumnType.of(types[i]);
		}
		return createTable(name, columns, columnTypes);
	}

	/**
	 * Creates a new table with fully typed columns (including precision/scale).
	 *
	 * @param name Table name
	 * @param columns Column names
	 * @param types Column types with precision/scale (must match columns length)
	 * @return true if table created, false if already exists
	 * @throws IllegalArgumentException if columns and types have different lengths
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public boolean createTable(String name, String[] columns, ConvexColumnType[] types) {
		if (columns.length != types.length) {
			throw new IllegalArgumentException("Columns and types must have same length");
		}

		AVector<ACell> existing = getLiveTable(name);
		if (existing != null) return false;

		// Build schema: [[name, typeName, precision, scale], ...]
		AVector schema = Vectors.empty();
		for (int i = 0; i < columns.length; i++) {
			ConvexColumnType ct = types[i];
			AString typeName = (ct.getBaseType() == ConvexType.ANY) ? null : Strings.create(ct.getBaseType().name());
			CVMLong precision = ct.hasPrecision() ? CVMLong.create(ct.getPrecision()) : null;
			CVMLong scale = ct.hasScale() ? CVMLong.create(ct.getScale()) : null;
			schema = schema.append(Vectors.of(Strings.create(columns[i]), typeName, precision, scale));
		}

		putTable(name, SQLTable.create((AVector<AVector<ACell>>) schema, now()));
		return true;
	}

	/**
	 * Drops a table by creating a tombstone.
	 *
	 * @param name Table name
	 * @return true if table dropped, false if not found
	 */
	public boolean dropTable(String name) {
		AVector<ACell> table = getLiveTable(name);
		if (table == null) return false;
		putTable(name, SQLTable.createTombstone(now()));
		return true;
	}

	/**
	 * Checks if a table exists and is live.
	 *
	 * @param name Table name
	 * @return true if table exists
	 */
	public boolean tableExists(String name) {
		return getLiveTable(name) != null;
	}

	/**
	 * Gets the schema (column definitions) for a table.
	 *
	 * @param name Table name
	 * @return Schema vector, or null if table not found
	 */
	public AVector<AVector<ACell>> getSchema(String name) {
		AVector<ACell> table = getLiveTable(name);
		if (table == null) return null;
		return SQLTable.getSchema(table);
	}

	/**
	 * Gets the column names for a table.
	 *
	 * @param name Table name
	 * @return Array of column names, or null if table not found
	 */
	public String[] getColumnNames(String name) {
		AVector<AVector<ACell>> schema = getSchema(name);
		if (schema == null) return null;
		String[] result = new String[(int) schema.count()];
		for (int i = 0; i < result.length; i++) {
			result[i] = schema.get(i).get(0).toString();
		}
		return result;
	}

	/**
	 * Gets the column types for a table (with precision/scale).
	 *
	 * @param name Table name
	 * @return Array of column types, or null if table not found
	 */
	public ConvexColumnType[] getColumnTypes(String name) {
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

	/**
	 * Gets the row count for a table.
	 *
	 * @param name Table name
	 * @return Row count, or 0 if table not found
	 */
	public long getRowCount(String name) {
		AVector<ACell> table = getLiveTable(name);
		if (table == null) return 0;
		return SQLTable.getRowCount(table);
	}

	// ========== Row Operations ==========

	/**
	 * Inserts a row into a table. First column is used as primary key.
	 *
	 * @param tableName Table name
	 * @param row Complete row (first column is primary key)
	 * @return true if inserted, false if table not found
	 */
	public boolean insert(String tableName, AVector<ACell> row) {
		AVector<ACell> table = getLiveTable(tableName);
		if (table == null) return false;

		Index<ABlob, AVector<ACell>> rows = SQLTable.getRows(table);
		if (rows == null) rows = TableLattice.INSTANCE.zero();

		CVMLong timestamp = now();
		ABlob key = toKey(row.get(0));
		rows = rows.assoc(key, SQLRow.create(row, timestamp));
		putTable(tableName, SQLTable.withRows(table, rows, timestamp));
		return true;
	}

	/**
	 * Inserts a row with auto-conversion from Java types. First value is primary key.
	 *
	 * @param tableName Table name
	 * @param values Column values (first is primary key)
	 * @return true if inserted, false if table not found
	 */
	public boolean insert(String tableName, Object... values) {
		return insert(tableName, Vectors.of(values));
	}

	/**
	 * Selects a row by primary key.
	 *
	 * @param tableName Table name
	 * @param primaryKey Primary key value
	 * @return Column values, or null if not found
	 */
	public AVector<ACell> selectByKey(String tableName, ACell primaryKey) {
		AVector<ACell> table = getLiveTable(tableName);
		if (table == null) return null;

		Index<ABlob, AVector<ACell>> rows = SQLTable.getRows(table);
		if (rows == null) return null;

		ABlob key = toKey(primaryKey);
		AVector<ACell> row = rows.get(key);
		if (row == null || !SQLRow.isLive(row)) return null;
		return SQLRow.getValues(row);
	}

	/**
	 * Deletes a row by primary key.
	 *
	 * @param tableName Table name
	 * @param primaryKey Primary key value
	 * @return true if deleted, false if not found
	 */
	public boolean deleteByKey(String tableName, ACell primaryKey) {
		AVector<ACell> table = getLiveTable(tableName);
		if (table == null) return false;

		Index<ABlob, AVector<ACell>> rows = SQLTable.getRows(table);
		if (rows == null) return false;

		ABlob key = toKey(primaryKey);
		AVector<ACell> row = rows.get(key);
		if (row == null || !SQLRow.isLive(row)) return false;

		CVMLong timestamp = now();
		rows = rows.assoc(key, SQLRow.createTombstone(timestamp));
		putTable(tableName, SQLTable.withRows(table, rows, timestamp));
		return true;
	}

	/**
	 * Returns all live rows in a table.
	 *
	 * @param tableName Table name
	 * @return Index of primary key (ABlob) to column values, or empty if table not found
	 */
	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> selectAll(String tableName) {
		AVector<ACell> table = getLiveTable(tableName);
		if (table == null) return (Index<ABlob, AVector<ACell>>) Index.EMPTY;

		Index<ABlob, AVector<ACell>> rows = SQLTable.getRows(table);
		if (rows == null) return (Index<ABlob, AVector<ACell>>) Index.EMPTY;

		// Filter to live rows and extract values
		Index<ABlob, AVector<ACell>> result = (Index<ABlob, AVector<ACell>>) Index.EMPTY;
		for (var entry : rows.entrySet()) {
			AVector<ACell> row = entry.getValue();
			if (SQLRow.isLive(row)) {
				result = result.assoc(entry.getKey(), SQLRow.getValues(row));
			}
		}
		return result;
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
			if (SQLTable.isLive(entry.getValue())) {
				names.add(entry.getKey().toString());
			}
		}
		return names.toArray(new String[0]);
	}
}
