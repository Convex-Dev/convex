package convex.db.lattice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.db.calcite.ConvexColumnType;
import convex.db.calcite.ConvexType;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.Cursors;

/**
 * System-versioned SQL schema — a {@link SQLSchema} that records every insert,
 * update, and deletion in a per-table history index.
 *
 * <p>Extends {@link SQLSchema} with:
 * <ul>
 *   <li>All table state vectors include a 5th slot: {@code history} (see {@link VersionedSQLTable#POS_HISTORY})</li>
 *   <li>{@link #insert} and {@link #deleteByKey} automatically append history entries</li>
 *   <li>Deduplication: writes with identical content are silently skipped</li>
 *   <li>{@link #getHistory} — all change events for a primary key, oldest first</li>
 *   <li>{@link #getAsOf} — row state at an arbitrary point in time</li>
 * </ul>
 *
 * <p>Lattice merge uses {@link VersionedTableStoreLattice}, which union-merges the
 * history slot across replicas — old versions survive replication and are never lost.
 *
 * <p>Timestamps:
 * <ul>
 *   <li>History keys use {@link System#nanoTime()} — monotonic within a JVM run, precise ordering</li>
 *   <li>LWW conflict-resolution uses {@link System#currentTimeMillis()} — comparable across nodes</li>
 * </ul>
 *
 * <p>SQL extension note: Calcite {@code AS OF SYSTEM TIME} is not yet wired up.
 * {@link #getHistory} and {@link #getAsOf} provide equivalent functionality via the lattice API.
 */
public class VersionedSQLSchema extends SQLSchema {

	VersionedSQLSchema(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		super(cursor);
	}

	/**
	 * Creates a new standalone versioned schema backed by an in-memory cursor.
	 *
	 * @return New VersionedSQLSchema instance
	 */
	public static VersionedSQLSchema create() {
		ALatticeCursor<Index<AString, AVector<ACell>>> cursor =
			Cursors.createLattice(VersionedTableStoreLattice.INSTANCE);
		return new VersionedSQLSchema(cursor);
	}

	/**
	 * Connects to an existing cursor (e.g. from a NodeServer chain) for persistence.
	 *
	 * @param cursor Lattice cursor pointing at the table store
	 * @return New VersionedSQLSchema instance
	 */
	public static VersionedSQLSchema connect(ALatticeCursor<Index<AString, AVector<ACell>>> cursor) {
		return new VersionedSQLSchema(cursor);
	}

	/**
	 * Wraps an existing {@link SQLSchema} with versioned table support, sharing the
	 * same underlying cursor. Useful when the schema was created via
	 * {@link SQLDatabase#tables()} and you want history tracking without changing
	 * the cursor chain setup.
	 *
	 * <p>Note: the underlying lattice for replication merges remains the original
	 * (non-versioned) one. History is stored correctly in the cursor state but may
	 * not survive cross-node merge until the NodeServer is configured with
	 * {@link VersionedTableStoreLattice}. For single-node use this is not an issue.
	 *
	 * @param schema Existing SQLSchema instance
	 * @return VersionedSQLSchema sharing the same cursor
	 */
	public static VersionedSQLSchema wrap(SQLSchema schema) {
		return new VersionedSQLSchema(schema.cursor());
	}

	// ── Table accessors (return VersionedSQLTable) ───────────────────────────

	@Override
	public VersionedSQLTable getTable(AString name) {
		ALatticeCursor<AVector<ACell>> tableCursor = cursor.path(name);
		if (tableCursor.get() == null) return null;
		return new VersionedSQLTable(tableCursor);
	}

	@Override
	public VersionedSQLTable getTable(String name) {
		return getTable(Strings.create(name));
	}

	@Override
	public VersionedSQLTable getLiveTable(AString name) {
		VersionedSQLTable table = getTable(name);
		if (table == null || !table.isLive()) return null;
		return table;
	}

	@Override
	public VersionedSQLTable getLiveTable(String name) {
		return getLiveTable(Strings.create(name));
	}

	// ── Table creation (5-slot state vector) ────────────────────────────────

	/**
	 * Creates a versioned table. The state vector includes an empty history slot.
	 * All other {@code createTable} overloads delegate here through the parent class.
	 */
	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public boolean createTable(AString name, String[] columns, ConvexColumnType[] types) {
		if (columns.length != types.length)
			throw new IllegalArgumentException("Columns and types must have same length");

		AVector newSchema = buildSchema(columns, types);

		VersionedSQLTable existing = getLiveTable(name);
		if (existing == null) {
			ALatticeCursor<AVector<ACell>> tableCursor = cursor.path(name);
			tableCursor.set(VersionedSQLTable.createState((AVector<AVector<ACell>>) newSchema, millis()));
			return true;
		}

		// Table already exists: append any new columns not present in stored schema
		AVector<AVector<ACell>> existingSchema = existing.getSchema();
		long existingCount = existingSchema != null ? existingSchema.count() : 0;
		if (newSchema.count() <= existingCount) return false;

		AVector combinedSchema = existingSchema;
		for (long i = existingCount; i < newSchema.count(); i++) {
			combinedSchema = combinedSchema.append(newSchema.get(i));
		}
		final AVector<AVector<ACell>> finalSchema = (AVector<AVector<ACell>>) combinedSchema;
		ALatticeCursor<AVector<ACell>> tableCursor = cursor.path(name);
		tableCursor.updateAndGet(state -> state.assoc(SQLTable.POS_SCHEMA, finalSchema));
		return true;
	}

	// ── Versioned mutations ──────────────────────────────────────────────────

	/**
	 * Inserts or updates a row, recording the change in the history index.
	 * Skips if values are identical to the current live row (deduplication).
	 */
	@Override
	public boolean insert(AString tableName, AVector<ACell> row) {
		VersionedSQLTable table = getLiveTable(tableName);
		if (table == null) return false;
		ABlob pk = toKey(row.get(0));
		return table.insertRowVersioned(pk, row, System.nanoTime(), millis());
	}

	/**
	 * Marks a row as deleted, recording the deletion in the history index.
	 */
	@Override
	public boolean deleteByKey(AString tableName, ACell primaryKey) {
		VersionedSQLTable table = getLiveTable(tableName);
		if (table == null) return false;
		ABlob pk = toKey(primaryKey);
		return table.deleteRowVersioned(pk, System.nanoTime(), millis());
	}

	// ── Temporal query API ───────────────────────────────────────────────────

	/**
	 * Returns all recorded change events for a primary key, oldest first.
	 * Each entry is {@code [values|null, CVMLong(nanotime), CVMLong(changeType)]}.
	 *
	 * @param tableName  Table name
	 * @param primaryKey Primary key value (same types as insert)
	 * @return Ordered list of history entries; empty if table not found or no history
	 */
	public List<AVector<ACell>> getHistory(String tableName, ACell primaryKey) {
		return getHistory(Strings.create(tableName), primaryKey);
	}

	public List<AVector<ACell>> getHistory(AString tableName, ACell primaryKey) {
		VersionedSQLTable table = getLiveTable(tableName);
		if (table == null) return List.of();
		return table.getHistory(toKey(primaryKey));
	}

	/**
	 * Returns the row as it existed at or before the given nanotime
	 * (equivalent to {@code SELECT ... AS OF SYSTEM TIME}).
	 *
	 * @param tableName  Table name
	 * @param primaryKey Primary key value
	 * @param nanotime   Upper-bound nanotime (from {@link System#nanoTime()} at the capture point)
	 * @return History entry at that point, or null if no version existed
	 */
	public AVector<ACell> getAsOf(String tableName, ACell primaryKey, long nanotime) {
		return getAsOf(Strings.create(tableName), primaryKey, nanotime);
	}

	public AVector<ACell> getAsOf(AString tableName, ACell primaryKey, long nanotime) {
		VersionedSQLTable table = getLiveTable(tableName);
		if (table == null) return null;
		return table.getAsOf(toKey(primaryKey), nanotime);
	}

	// ── Batch insert ────────────────────────────────────────────────────────

	/**
	 * Batch-inserts rows with history tracking in a single atomic update.
	 * Overrides {@link SQLSchema#insertAll} to include per-row history entries.
	 */
	@Override
	public int insertAll(AString tableName, List<AVector<ACell>> rows) {
		if (rows == null || rows.isEmpty()) return 0;
		VersionedSQLTable table = getLiveTable(tableName);
		if (table == null) return 0;
		long startNano = System.nanoTime();
		CVMLong millis = millis();
		List<Map.Entry<ABlob, AVector<ACell>>> sorted = new ArrayList<>(rows.size());
		for (AVector<ACell> row : rows) {
			sorted.add(Map.entry(toKey(row.get(0)), row));
		}
		sorted.sort(Map.Entry.comparingByKey());
		return table.insertRowsVersioned(sorted, startNano, millis);
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private CVMLong millis() {
		return CVMLong.create(System.currentTimeMillis());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static AVector buildSchema(String[] columns, ConvexColumnType[] types) {
		AVector schema = Vectors.empty();
		for (int i = 0; i < columns.length; i++) {
			ConvexColumnType ct = types[i];
			CVMLong precision = ct.hasPrecision() ? CVMLong.create(ct.getPrecision()) : null;
			CVMLong scale     = ct.hasScale()     ? CVMLong.create(ct.getScale())     : null;
			convex.core.data.AString typeName =
				(ct.getBaseType() == ConvexType.ANY) ? null : Strings.create(ct.getBaseType().name());
			schema = schema.append(Vectors.of(Strings.create(columns[i]), typeName, precision, scale));
		}
		return schema;
	}
}
