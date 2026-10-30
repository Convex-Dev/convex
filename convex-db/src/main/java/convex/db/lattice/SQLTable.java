package convex.db.lattice;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexColumnType;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * A single SQL table within the lattice table store.
 *
 * <p>Wraps a lattice cursor pointing at the table's state vector: [schema, rows, ctime, liveCount, version]
 * <ul>
 *   <li>schema (AVector) - Column definitions: [[name, type, precision, scale], ...]</li>
 *   <li>rows (Index) - Row data: primary-key (ABlob) → row entry</li>
 *   <li>ctime (CVMLong) - When the table was created, or dropped for a tombstone</li>
 *   <li>liveCount (CVMLong) - Number of live (non-tombstone) rows</li>
 *   <li>version (CVMLong) - Format version (CAD039); absent in version 0 entries</li>
 * </ul>
 *
 * <p>This implementation reads and writes version 0. Entries without a version field
 * keep exactly their four fields; merges and row updates carry a version field when
 * there is one, and tables of a higher version are refused rather than misread.
 *
 * <p>Schema is immutable after creation (for now).
 *
 * <p>Obtained from {@link SQLSchema#getTable(String)} as a cursor-backed component
 * in the hierarchy: ConvexDB → SQLDatabase → SQLSchema → SQLTable. It is a live view
 * for table operations and defines no merge: the containing {@link SQLDatabase}
 * merges with the policy its wrapper supplies.
 *
 * <p>Row writes are stamped with the clock the containing schema supplies, read once
 * per write.
 */
public class SQLTable extends ALatticeComponent<AVector<ACell>> {

	/** Position of schema in table vector */
	static final int POS_SCHEMA = 0;
	/** Position of rows in table vector */
	static final int POS_ROWS = 1;
	/** Position of the creation timestamp */
	static final int POS_CTIME = 2;
	/** Position of live row count */
	static final int POS_LIVE_COUNT = 3;
	/** Position of the format version, absent in version 0 entries */
	static final int POS_VERSION = 4;

	/** Highest table format version this implementation reads and writes */
	public static final long SUPPORTED_VERSION = 0;

	private final Supplier<CVMLong> clock;

	/**
	 * Binds a table to the cursor of its entry. Construction reads and writes nothing.
	 *
	 * @param cursor Cursor of the table's entry, which may be absent
	 * @param clock Clock that stamps row writes
	 */
	protected SQLTable(AForkableCursor<AVector<ACell>> cursor, Supplier<CVMLong> clock) {
		super(cursor);
		this.clock = Objects.requireNonNull(clock, "Clock must not be null");
	}

	/** Returns the clock that stamps this table's row writes. */
	public Supplier<CVMLong> getClock() {
		return clock;
	}

	// ========== Static State Factories ==========

	/**
	 * Creates the initial state vector for a new live table.
	 *
	 * @param schema Column definitions
	 * @param timestamp Creation timestamp
	 * @return State vector [schema, empty-rows, timestamp, liveCount=0]
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> createState(AVector<AVector<ACell>> schema, CVMLong timestamp) {
		return Vectors.of(schema, (Index<ABlob, AVector<ACell>>) Index.EMPTY, timestamp, CVMLong.ZERO);
	}

	/**
	 * Creates a tombstone state vector for a dropped table.
	 *
	 * @param timestamp Deletion timestamp
	 * @return Tombstone state vector [null, null, timestamp]
	 */
	static AVector<ACell> createTombstoneState(CVMLong timestamp) {
		return Vectors.of(null, null, timestamp, CVMLong.ZERO);
	}

	// ========== Static Helpers (for raw state access) ==========

	/**
	 * Checks if a raw table state represents a live (non-tombstone) table.
	 */
	static boolean isLiveState(AVector<ACell> state) {
		return state != null && state.get(POS_SCHEMA) != null;
	}

	/**
	 * Gets the format version of a raw table state: 0 when it has no version field,
	 * and -1, which no implementation supports, when the field is not an integer.
	 */
	static long getVersion(AVector<ACell> state) {
		if (state == null || state.count() <= POS_VERSION) return 0;
		ACell version = state.get(POS_VERSION);
		if (version == null) return 0;
		return (version instanceof CVMLong v) ? v.longValue() : -1;
	}

	/** Whether this implementation can read and write tables of the given version. */
	static boolean isSupportedVersion(long version) {
		return version >= 0 && version <= SUPPORTED_VERSION;
	}

	// ========== Cursor-backed Instance Methods ==========

	/**
	 * Returns the underlying state vector.
	 */
	public AVector<ACell> getState() {
		return cursor().get();
	}

	/**
	 * Gets the schema from this table.
	 *
	 * @return Schema vector, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public AVector<AVector<ACell>> getSchema() {
		AVector<ACell> state = cursor().get();
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
		AVector<ACell> state = cursor().get();
		if (state == null) return null;
		return (Index<ABlob, AVector<ACell>>) state.get(POS_ROWS);
	}

	/**
	 * Gets the table's ctime: when it was created, or dropped for a tombstone.
	 */
	public CVMLong getTimestamp() {
		AVector<ACell> state = cursor().get();
		if (state == null) return null;
		return (CVMLong) state.get(POS_CTIME);
	}

	/**
	 * Gets the table's format version: 0 for an entry without a version field.
	 */
	public long getVersion() {
		return getVersion(cursor().get());
	}

	/**
	 * The schema and format version of a live table, which together determine how its rows
	 * are read (CAD039). Row writes leave them unchanged. A reader built for one binding,
	 * such as a query plan, must not read rows under another.
	 *
	 * @param schema Column definitions
	 * @param version Format version
	 */
	public record Binding(AVector<AVector<ACell>> schema, long version) {

		/** The column names, in column order. */
		public String[] columnNames() {
			String[] result = new String[(int) schema.count()];
			for (int i = 0; i < result.length; i++) {
				result[i] = schema.get(i).get(0).toString();
			}
			return result;
		}

		/** The column types, with precision and scale, in column order. */
		public ConvexColumnType[] columnTypes() {
			ConvexColumnType[] result = new ConvexColumnType[(int) schema.count()];
			for (int i = 0; i < result.length; i++) {
				result[i] = ConvexColumnType.fromSchema(schema.get(i), (int) version);
			}
			return result;
		}
	}

	/**
	 * Gets the table's binding, read from one state of its entry.
	 *
	 * @return Schema and version, or null if the table is absent or dropped
	 */
	@SuppressWarnings("unchecked")
	public Binding getBinding() {
		AVector<ACell> state = cursor().get();
		if (!isLiveState(state)) return null;
		return new Binding((AVector<AVector<ACell>>) state.get(POS_SCHEMA), getVersion(state));
	}

	/**
	 * Checks if this table is a tombstone (dropped).
	 */
	public boolean isTombstone() {
		AVector<ACell> state = cursor().get();
		return state != null && state.get(POS_SCHEMA) == null;
	}

	/**
	 * Checks if this table is live (not dropped).
	 */
	public boolean isLive() {
		return isLiveState(cursor().get());
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
	 * Gets the number of live (non-tombstone) rows. O(1).
	 */
	public long getRowCount() {
		AVector<ACell> state = cursor().get();
		if (state == null) return 0;
		if (state.count() <= POS_LIVE_COUNT) {
			// Legacy state vector without liveCount — fall back to Index.count()
			Index<ABlob, AVector<ACell>> rows = getRows();
			return (rows != null) ? rows.count() : 0;
		}
		CVMLong lc = (CVMLong) state.get(POS_LIVE_COUNT);
		return (lc != null) ? lc.longValue() : 0;
	}

	// ========== Mutation Methods ==========

	/**
	 * Inserts a row into this table, stamped with this table's clock.
	 *
	 * @param pk Primary key (blob-encoded)
	 * @param values Full row values (including PK as first element)
	 * @return true if inserted successfully
	 */
	public boolean insertRow(ABlob pk, AVector<ACell> values) {
		return insertRow(pk, values, clock.get());
	}

	/**
	 * Deletes a row by primary key, stamped with this table's clock.
	 *
	 * @param key Primary key (blob-encoded)
	 * @return true if a live row was deleted
	 */
	public boolean deleteRow(ABlob key) {
		return deleteRow(key, clock.get());
	}

	/**
	 * Inserts a row into this table with an explicit timestamp.
	 *
	 * @param pk Primary key (blob-encoded)
	 * @param values Full row values (including PK as first element)
	 * @param timestamp Insert timestamp
	 * @return true if inserted successfully
	 */
	public boolean insertRow(ABlob pk, AVector<ACell> values, CVMLong timestamp) {
		return insertRows(List.of(new Row(pk, values)), timestamp) == 1;
	}

	/** A row to write: its primary key, blob-encoded, and its values with the key column first. */
	public record Row(ABlob key, AVector<ACell> values) {}

	/**
	 * Inserts rows into this table in one write, all stamped with one reading of this
	 * table's clock. A row replaces any row of its key, a later row in the batch an earlier.
	 *
	 * @param rows The rows
	 * @return The number of rows written, 0 if the table is not live
	 */
	public long insertRows(List<Row> rows) {
		return insertRows(rows, clock.get());
	}

	/**
	 * Inserts rows into this table in one write, with an explicit timestamp. The write is
	 * atomic: a reader sees all of the rows or none. A caller with more rows than one write
	 * should hold may write them as consecutive batches.
	 *
	 * @param rows The rows; a list, since the write may be retried and read it again
	 * @param timestamp Insert timestamp
	 * @return The number of rows written, 0 if the table is not live
	 */
	@SuppressWarnings("unchecked")
	public long insertRows(List<Row> rows, CVMLong timestamp) {
		long[] written = new long[1];
		cursor().updateAndGet(state -> {
			written[0] = 0;
			if (state == null || state.get(POS_SCHEMA) == null) return state;
			requireSupportedVersion(state);
			Index<ABlob, AVector<ACell>> index = (Index<ABlob, AVector<ACell>>) state.get(POS_ROWS);
			if (index == null) index = Index.none();
			long liveCount = getLiveCount(state);
			for (Row row : rows) {
				// Count the rows this write makes live
				AVector<ACell> existing = index.get(row.key());
				if (existing == null || !SQLRow.isLive(existing)) liveCount++;
				index = index.assoc(row.key(), SQLRow.create(row.values(), timestamp));
			}
			written[0] = rows.size();
			// ctime is the creation time: row writes leave it unchanged (CAD039)
			return entry(state.get(POS_SCHEMA), index, state.get(POS_CTIME), liveCount, versionField(state));
		});
		return written[0];
	}

	/**
	 * Deletes a row by primary key with an explicit timestamp.
	 *
	 * @param key Primary key (blob-encoded)
	 * @param timestamp Deletion timestamp
	 * @return true if a live row was deleted
	 */
	@SuppressWarnings("unchecked")
	public boolean deleteRow(ABlob key, CVMLong timestamp) {
		boolean[] result = new boolean[1];
		cursor().updateAndGet(state -> {
			if (state == null || state.get(POS_SCHEMA) == null) return state;
			requireSupportedVersion(state);
			Index<ABlob, AVector<ACell>> rows = (Index<ABlob, AVector<ACell>>) state.get(POS_ROWS);
			if (rows == null) return state;
			AVector<ACell> row = rows.get(key);
			if (row == null || !SQLRow.isLive(row)) return state;
			rows = rows.assoc(key, SQLRow.createTombstone(timestamp));
			long liveCount = getLiveCount(state) - 1;
			result[0] = true;
			return entry(state.get(POS_SCHEMA), rows, state.get(POS_CTIME), liveCount, versionField(state));
		});
		return result[0];
	}

	// ========== Helpers ==========

	/**
	 * Builds a live table state, with a version field only when there is one to carry,
	 * so that version 0 entries keep exactly their four fields.
	 */
	static AVector<ACell> entry(ACell schema, ACell rows, ACell ctime, long liveCount, ACell version) {
		CVMLong count = CVMLong.create(liveCount);
		if (version == null) return Vectors.of(schema, rows, ctime, count);
		return Vectors.of(schema, rows, ctime, count, version);
	}

	/** The version field of a state, or null when it has none. */
	static ACell versionField(AVector<ACell> state) {
		return (state.count() > POS_VERSION) ? state.get(POS_VERSION) : null;
	}

	private static void requireSupportedVersion(AVector<ACell> state) {
		long version = getVersion(state);
		if (!isSupportedVersion(version)) {
			throw new IllegalStateException("Table uses format version " + version
				+ ", which this version of Convex DB cannot read or write");
		}
	}

	/**
	 * Extracts the live count from a state vector, handling legacy format.
	 */
	static long getLiveCount(AVector<ACell> state) {
		if (state == null || state.count() <= POS_LIVE_COUNT) return 0;
		CVMLong lc = (CVMLong) state.get(POS_LIVE_COUNT);
		return (lc != null) ? lc.longValue() : 0;
	}

	/**
	 * Computes live row count by scanning an Index.
	 */
	static long computeLiveCount(Index<ABlob, AVector<ACell>> rows) {
		if (rows == null) return 0;
		long[] count = new long[1];
		rows.forEach((k, v) -> {
			if (SQLRow.isLive(v)) count[0]++;
		});
		return count[0];
	}
}
