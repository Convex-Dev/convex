package convex.db.lattice;

import java.util.Objects;
import java.util.function.Supplier;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexType;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;
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
 *
 * <p>{@link #table(AString)} supplies the component for each table name. Its rule
 * merges that table's entries within {@link #mergeValues}, and the same component
 * receives delivery to the table's path and serves table operations.
 *
 * <p>Writes are stamped with an explicit clock, read once per write and passed on to
 * the tables. Merges never read it. The application supplies the clock; how it orders
 * a writer's writes, for example strictly increasing stamps, is the application's policy.
 */
public class SQLSchema extends ALatticeComponent<Index<AString, AVector<ACell>>> {

	private final Supplier<CVMLong> clock;

	/** Binds a table store to its cursor, stamping writes with the runtime clock. */
	public SQLSchema(AForkableCursor<Index<AString, AVector<ACell>>> cursor) {
		this(cursor, Utils::getCurrentTimestampCell);
	}

	/**
	 * Binds a table store to its cursor. Construction reads and writes nothing.
	 *
	 * @param cursor Cursor of the table store, which may be absent
	 * @param clock Clock that stamps writes to this store and its tables
	 */
	public SQLSchema(AForkableCursor<Index<AString, AVector<ACell>>> cursor, Supplier<CVMLong> clock) {
		super(cursor);
		this.clock = Objects.requireNonNull(clock, "Clock must not be null");
	}

	/**
	 * Creates a new empty table store, stamping writes with the runtime clock.
	 *
	 * @return New SQLSchema instance
	 */
	public static SQLSchema create() {
		AForkableCursor<Index<AString, AVector<ACell>>> cursor = Cursors.root(Index.none());
		return new SQLSchema(cursor);
	}

	/**
	 * Connects to an existing cursor for cursor chain integration, stamping writes with
	 * the runtime clock.
	 *
	 * @param cursor Lattice cursor (e.g. from a SignedCursor path)
	 * @return New SQLSchema instance connected to the cursor
	 */
	public static SQLSchema connect(AForkableCursor<Index<AString, AVector<ACell>>> cursor) {
		return new SQLSchema(cursor);
	}

	/** Returns the clock that stamps writes to this store and its tables. */
	public Supplier<CVMLong> getClock() {
		return clock;
	}

	/**
	 * Creates a forked copy of this store for independent operation.
	 *
	 * @return Forked SQLSchema instance
	 */
	public SQLSchema fork() {
		return rebind(storage.fork());
	}

	/** Subclasses override this to return their own type, so forks keep their tables and clock. */
	@Override
	protected SQLSchema rebind(AForkableCursor<Index<AString, AVector<ACell>>> cursor) {
		return new SQLSchema(cursor, clock);
	}

	// ========== Tables and Merge ==========

	/**
	 * Returns the cursor of a named table's entry, for constructing table components.
	 * A missing table store is created only by a changed write.
	 *
	 * @param name Table name
	 * @return Cursor of the table's entry, which may be absent
	 */
	public AForkableCursor<AVector<ACell>> path(AString name) {
		return cursor().withDefault(Index.none()).path(name);
	}

	/**
	 * Returns the component for a named table, which may be absent. Construction reads
	 * and writes nothing. Merge, delivery and table operations all use this component,
	 * so an override supplying another {@link SQLTable} changes how that table merges.
	 *
	 * @param name Table name
	 * @return Table component bound to {@link #path(AString)}, with this store's clock
	 */
	protected SQLTable table(AString name) {
		return new SQLTable(path(name), clock);
	}

	/**
	 * This table store's merge rule: a union of table names, with each table's entries
	 * merged by the rule of its {@link #table(AString)} component. It is a pure function
	 * that reads and writes no state. Entries that are not table entries are ignored, and
	 * an absent store stays absent when nothing is accepted.
	 *
	 * @param own Established table store, kept on ties; null if absent
	 * @param other Table store to merge in; null if absent
	 * @return Merged table store
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Index<AString, AVector<ACell>> mergeValues(Index<AString, AVector<ACell>> own,
			Index<AString, AVector<ACell>> other) {
		ACell incoming = other;
		if (!(incoming instanceof Index)) return own;
		// Foreign entries stay untyped until they are checked as table entries
		Index<AString, ACell> base = (own == null) ? Index.none() : (Index) own;
		Index<AString, ACell> merged = base.mergeDifferences((Index<AString, ACell>) incoming, new MergeFunction<ACell>() {
			@Override
			public ACell merge(ACell a, ACell b) {
				throw new IllegalStateException("Table merge requires the table name");
			}

			@Override
			public ACell merge(Object key, ACell a, ACell b) {
				if (!(key instanceof AString name) || !(b instanceof AVector)) return a;
				if (a != null && !(a instanceof AVector)) return a;
				return table(name).mergeValues((AVector<ACell>) a, (AVector<ACell>) b);
			}
		});
		return (own == null && merged.isEmpty()) ? null : (Index) merged;
	}

	@Override
	public Index<AString, AVector<ACell>> merge(Index<AString, AVector<ACell>> incoming) {
		return cursor().updateAndGet(own -> mergeValues(own, incoming));
	}

	@Override
	protected Index<AString, AVector<ACell>> mergePublication(
			Index<AString, AVector<ACell>> own,
			Index<AString, AVector<ACell>> other) {
		return mergeValues(own, other);
	}

	/** Each table name resolves to its {@link #table(AString)} component. */
	@SuppressWarnings("unchecked")
	@Override
	protected <T extends ACell> ALatticeComponent<T> child(ACell key) {
		return (key instanceof AString name) ? (ALatticeComponent<T>) (ALatticeComponent<?>) table(name) : null;
	}

	// ========== Internal Helpers ==========

	/**
	 * Gets a cursor-backed table by name, returning null if not found.
	 */
	public SQLTable getTable(AString name) {
		SQLTable table = table(name);
		return (table.getState() == null) ? null : table;
	}

	/** Convenience overload. */
	public SQLTable getTable(String name) {
		return getTable(Strings.create(name));
	}

	/**
	 * Gets a live (non-tombstone) table, returning null if not found or dropped.
	 *
	 * @throws IllegalStateException if the table uses a format version this implementation
	 *         does not support
	 */
	public SQLTable getLiveTable(AString name) {
		SQLTable table = getTable(name);
		if (table == null || !table.isLive()) return null;
		// Never read or write a table of an unknown format version as if it were another
		long version = table.getVersion();
		if (!SQLTable.isSupportedVersion(version)) {
			throw new IllegalStateException("Table '" + name + "' uses format version " + version
				+ ", which this version of Convex DB cannot read or write");
		}
		return table;
	}

	/** Convenience overload. */
	public SQLTable getLiveTable(String name) {
		return getLiveTable(Strings.create(name));
	}

	/**
	 * Converts an ACell to ABlob for use as primary key.
	 * Supports: ABlob (direct), an integer in the 64-bit range (8-byte encoding of its
	 * value, whatever its representation), AString (UTF-8 bytes).
	 *
	 * @param key The key to convert
	 * @return ABlob representation, or null if the value has no key encoding
	 */
	private static ABlob toKey(ACell key) {
		if (key instanceof ABlob blob) return blob;
		if (key instanceof AInteger n && n.isLong()) {
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
		return null;
	}

	/**
	 * Converts a primary key value for writing a row.
	 *
	 * @throws IllegalArgumentException if the value has no key encoding
	 */
	private static ABlob toWriteKey(ACell key) {
		ABlob blob = toKey(key);
		if (blob == null) {
			String type = (key == null) ? "null" : (key instanceof AInteger) ? "integer beyond 64 bits" : key.getClass().getSimpleName();
			throw new IllegalArgumentException("Unsupported primary key type: " + type);
		}
		return blob;
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

		// Set state on the table's cursor path
		path(name).set(SQLTable.createState((AVector<AVector<ACell>>) schema, clock.get()));
		return true;
	}

	/** Drops a table by creating a tombstone. */
	public boolean dropTable(String name) {
		return dropTable(Strings.create(name));
	}

	public boolean dropTable(AString name) {
		if (getLiveTable(name) == null) return false;
		path(name).set(SQLTable.createTombstoneState(clock.get()));
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
		SQLTable table = getLiveTable(name);
		if (table == null) return null;
		AVector<AVector<ACell>> schema = table.getSchema();
		int version = (int) table.getVersion();
		ConvexColumnType[] result = new ConvexColumnType[(int) schema.count()];
		for (int i = 0; i < result.length; i++) {
			result[i] = ConvexColumnType.fromSchema(schema.get(i), version);
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
		ABlob pk = toWriteKey(row.get(0));
		return table.insertRow(pk, row);
	}

	/** Inserts a row with auto-conversion from Java types. First value is primary key. */
	public boolean insert(String tableName, Object... values) {
		return insert(Strings.create(tableName), Vectors.of(values));
	}

	/** Selects a row by primary key. */
	public AVector<ACell> selectByKey(String tableName, ACell primaryKey) {
		return selectByKey(Strings.create(tableName), primaryKey);
	}

	/** Selects a row by primary key. A value with no key encoding selects no row, since no row can have it as its key. */
	public AVector<ACell> selectByKey(AString tableName, ACell primaryKey) {
		SQLTable table = getLiveTable(tableName);
		if (table == null) return null;

		Index<ABlob, AVector<ACell>> rows = table.getRows();
		if (rows == null) return null;

		ABlob key = toKey(primaryKey);
		if (key == null) return null;
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
		ABlob key = toKey(primaryKey);
		if (key == null) return false;
		return table.deleteRow(key);
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

		// Single-pass tree traversal: filter tombstones, unwrap values
		Index<ABlob, AVector<ACell>>[] result = new Index[] { Index.none() };
		rows.forEach((k, v) -> {
			if (SQLRow.isLive(v)) {
				result[0] = result[0].assoc(k, SQLRow.getValues(v));
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
		Index<AString, AVector<ACell>> store = cursor().get();
		if (store == null) return new String[0];

		java.util.List<String> names = new java.util.ArrayList<>();
		for (var entry : store.entrySet()) {
			// Tables of an unsupported format version are left out, so listings and
			// catalog queries keep working; accessing one by name is an error
			AVector<ACell> state = entry.getValue();
			if (SQLTable.isLiveState(state) && SQLTable.isSupportedVersion(SQLTable.getVersion(state))) {
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
}
