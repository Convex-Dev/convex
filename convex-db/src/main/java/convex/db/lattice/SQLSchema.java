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
public class SQLSchema extends ALatticeComponent<Index<AString, AVector<ACell>>> {

	public SQLSchema(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		super(cursor);
	}

	/**
	 * Creates a new empty table store.
	 *
	 * @return New LatticeTables instance
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
	 * @return New LatticeTables instance connected to the cursor
	 */
	public static SQLSchema connect(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		return new SQLSchema(cursor);
	}

	/**
	 * Creates a forked copy of this store for independent operation.
	 *
	 * @return Forked LatticeTables instance
	 */
	public SQLSchema fork() {
		return new SQLSchema(cursor.fork());
	}

	// ========== Internal Helpers ==========

	private CVMLong now() {
		return CVMLong.create(System.currentTimeMillis());
	}

	/**
	 * Gets a table by name, returning null if not found.
	 */
	public SQLTable getTable(AString name) {
		Index<AString, AVector<ACell>> store = cursor.get();
		if (store == null) return null;
		return SQLTable.wrap(store.get(name));
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

	private void putTable(AString name, SQLTable table) {
		AVector<ACell> state = table.getState();
		cursor.updateAndGet(store -> {
			if (store == null) store = TableStoreLattice.INSTANCE.zero();
			return store.assoc(name, state);
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

		if (getLiveTable(name) != null) return false;

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

	/** Drops a table by creating a tombstone. */
	public boolean dropTable(String name) {
		return dropTable(Strings.create(name));
	}

	public boolean dropTable(AString name) {
		if (getLiveTable(name) == null) return false;
		putTable(name, SQLTable.createTombstone(now()));
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
		ABlob pk = toKey(row.get(0));
		CVMLong timestamp = now();

		// Single cursor operation: read table, insert row, write back
		boolean[] result = new boolean[1];
		cursor.updateAndGet(store -> {
			if (store == null) store = TableStoreLattice.INSTANCE.zero();
			SQLTable table = SQLTable.wrap(store.get(tableName));
			if (table == null || !table.isLive()) return store;

			Index<ABlob, AVector<ACell>> rows = table.getRows();
			if (rows == null) rows = TableLattice.INSTANCE.zero();
			rows = rows.assoc(pk, SQLRow.create(row, timestamp));
			result[0] = true;
			return store.assoc(tableName, table.withRows(rows, timestamp).getState());
		});
		return result[0];
	}

	/** Inserts a row with auto-conversion from Java types. First value is primary key. */
	public boolean insert(String tableName, Object... values) {
		return insert(Strings.create(tableName), Vectors.of(values));
	}

	/** Selects a row by primary key. */
	public AVector<ACell> selectByKey(String tableName, ACell primaryKey) {
		return selectByKey(Strings.create(tableName), primaryKey);
	}

	public AVector<ACell> selectByKey(AString tableName, ACell primaryKey) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return null;

		Index<ABlob, AVector<ACell>> rows = table.getRows();
		if (rows == null) return null;

		ABlob key = toKey(primaryKey);
		AVector<ACell> row = rows.get(key);
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

		Index<ABlob, AVector<ACell>> rows = table.getRows();
		if (rows == null) return false;

		ABlob key = toKey(primaryKey);
		AVector<ACell> row = rows.get(key);
		if (row == null || !SQLRow.isLive(row)) return false;

		CVMLong timestamp = now();
		rows = rows.assoc(key, SQLRow.createTombstone(timestamp));
		putTable(tableName, table.withRows(rows, timestamp));
		return true;
	}

	/** Returns all live rows in a table. */
	public Index<ABlob, AVector<ACell>> selectAll(String tableName) {
		return selectAll(Strings.create(tableName));
	}

	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> selectAll(AString tableName) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return Index.none();

		Index<ABlob, AVector<ACell>> rows = table.getRows();
		if (rows == null) return Index.none();

		// Filter to live rows and extract values
		Index<ABlob, AVector<ACell>> result = Index.none();
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
			SQLTable table = SQLTable.wrap(entry.getValue());
			if (table != null && table.isLive()) {
				names.add(entry.getKey().toString());
			}
		}
		return names.toArray(new String[0]);
	}
}
