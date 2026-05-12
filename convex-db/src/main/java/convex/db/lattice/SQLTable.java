package convex.db.lattice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * A single SQL table within the lattice table store.
 *
 * <p>Wraps a lattice cursor pointing at the table's state vector:
 * [schema, rows, utime, liveCount, blockVec, indices]
 * <ul>
 *   <li>schema (AVector) - Column definitions: [[name, type, precision, scale], ...]</li>
 *   <li>rows (Index) - Row data: primary-key prefix (ABlob) → RowBlock (flat Blob)</li>
 *   <li>utime (CVMLong) - Schema update timestamp for LWW</li>
 *   <li>liveCount (CVMLong) - Number of live (non-tombstone) rows</li>
 *   <li>blockVec (AVector|null) - Sequential block list for O(n)-total full scans</li>
 *   <li>indices (Index) - Column indices: colName (AString) → Index(indexKey → pk)</li>
 * </ul>
 *
 * <p>Schema is immutable after creation (for now). Row data merges independently.
 *
 * <p>Obtained from {@link SQLSchema#getTable(String)} as a cursor-backed component
 * in the hierarchy: ConvexDB → SQLDatabase → SQLSchema → SQLTable.
 */
public class SQLTable extends ALatticeComponent<AVector<ACell>> {

	/** Position of schema in table vector */
	static final int POS_SCHEMA    = 0;
	/** Position of rows in table vector */
	static final int POS_ROWS      = 1;
	/** Position of update timestamp */
	static final int POS_UTIME     = 2;
	/** Position of live row count */
	static final int POS_LIVE_COUNT = 3;
	/**
	 * Position of the sequential block vector (for O(1)-heap full scans).
	 *
	 * <p>Value is {@code AVector<ACell>} (the ordered list of live block blobs) or
	 * {@code null} when invalidated by a single-row write. Building it costs one
	 * Index traversal; reading it requires no Index traversal at all.
	 *
	 * <p>VersionedSQLTable stores its history {@code Index} at this same slot, so
	 * {@link #getBlockVec()} guards with {@code instanceof AVector} to distinguish.
	 */
	static final int POS_BLOCK_VEC = 4;
	/**
	 * Position of secondary column indices.
	 *
	 * <p>Value is {@code Index<AString, Index<ABlob, ABlob>>} mapping column name
	 * to a column index. The column index maps
	 * {@code encode(value) ++ pk_bytes} → pk_blob.
	 * May be {@code null} when no indices are defined.
	 */
	static final int POS_INDICES   = 5;

	SQLTable(ALatticeCursor<AVector<ACell>> cursor) {
		super(cursor);
	}

	// ========== Static State Factories ==========

	/**
	 * Creates the initial state vector for a new live table.
	 *
	 * @param schema Column definitions
	 * @param timestamp Creation timestamp
	 * @return State vector [schema, empty-rows, timestamp, liveCount=0, emptyBlockVec, emptyIndices]
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> createState(AVector<AVector<ACell>> schema, CVMLong timestamp) {
		return Vectors.of(schema, (Index<ABlob, ACell>) Index.EMPTY, timestamp, CVMLong.ZERO,
			Vectors.empty(), Index.EMPTY);
	}

	/**
	 * Creates a tombstone state vector for a dropped table.
	 *
	 * @param timestamp Deletion timestamp
	 * @return Tombstone state vector [null, null, timestamp, liveCount=0]
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

	// ========== Static Helpers (block vector) ==========

	/**
	 * Builds a sequential block vector from a rows Index.
	 * Used to populate slot 4 for O(1)-heap full scans.
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> buildBlockVec(Index<ABlob, ACell> rows) {
		if (rows == null || rows.count() == 0) return Vectors.empty();
		List<ACell> blocks = new ArrayList<>((int) Math.min(rows.count(), Integer.MAX_VALUE));
		rows.forEach((bk, block) -> {
			if (RowBlock.isBlock(block)) blocks.add(block);
		});
		return Vectors.create(blocks);
	}

	// ========== Static Helpers (column indices) ==========

	/**
	 * Extracts the secondary column indices map from a raw state vector.
	 * Returns null if not present or empty.
	 */
	@SuppressWarnings("unchecked")
	static Index<AString, Index<ABlob, ABlob>> getIndicesFromState(AVector<ACell> state) {
		if (state == null || state.count() <= POS_INDICES) return null;
		ACell slot = state.get(POS_INDICES);
		if (!(slot instanceof Index)) return null;
		Index<AString, Index<ABlob, ABlob>> idx = (Index<AString, Index<ABlob, ABlob>>) slot;
		return (idx.count() == 0) ? null : idx;
	}

	/**
	 * Finds the column position index within a schema vector by column name.
	 * Returns -1 if not found.
	 */
	static int findColIdxInSchema(AVector<AVector<ACell>> schema, AString colName) {
		if (schema == null) return -1;
		for (int i = 0; i < (int) schema.count(); i++) {
			if (colName.equals(schema.get(i).get(0))) return i;
		}
		return -1;
	}

	/**
	 * Builds a fresh column index from all live rows in the given state.
	 * Used when {@link #createColumnIndex} is called on a table with existing data.
	 */
	@SuppressWarnings("unchecked")
	static Index<ABlob, ABlob> buildColumnIndex(AVector<ACell> state, int colIdx) {
		Index<ABlob, ACell> rows = (Index<ABlob, ACell>) state.get(POS_ROWS);
		if (rows == null) return Index.none();

		@SuppressWarnings("rawtypes")
		Index[] result = {Index.none()};
		rows.forEach((bk, block) -> {
			if (RowBlock.isBlock(block)) {
				RowBlock.forEach(block, (pk, row) -> {
					if (SQLRow.isLive(row)) {
						AVector<ACell> values = SQLRow.getValues(row);
						if (colIdx < (int) values.count() && values.get(colIdx) != null) {
							ABlob iKey = ColumnIndex.indexKey(values.get(colIdx), pk);
							result[0] = result[0].assoc(iKey, pk);
						}
					}
				});
			}
		});
		return (Index<ABlob, ABlob>) result[0];
	}

	/**
	 * Returns a copy of the indices map with a new or updated entry for the given row insert.
	 * Handles both new rows and updates to existing rows (removes old index entry first).
	 *
	 * @param indices  Current indices map (may be null)
	 * @param schema   Table schema (for column position lookup)
	 * @param pk       Primary key of the row being inserted
	 * @param oldValues Old column values (null if this is a new row, not an update)
	 * @param newValues New column values
	 */
	@SuppressWarnings("unchecked")
	static Index<AString, Index<ABlob, ABlob>> indexAddRow(
			Index<AString, Index<ABlob, ABlob>> indices,
			AVector<AVector<ACell>> schema,
			ABlob pk, AVector<ACell> oldValues, AVector<ACell> newValues) {
		if (indices == null || indices.count() == 0) return indices;

		@SuppressWarnings("rawtypes")
		final Index[] result = {indices};
		indices.forEach((colName, colIndex) -> {
			int ci = findColIdxInSchema(schema, (AString) colName);
			if (ci < 0) return;

			Index<ABlob, ABlob> col = (Index<ABlob, ABlob>) colIndex;

			// Remove old entry (if updating an existing live row)
			if (oldValues != null && ci < (int) oldValues.count()) {
				ACell oldVal = oldValues.get(ci);
				if (oldVal != null) col = col.dissoc(ColumnIndex.indexKey(oldVal, pk));
			}
			// Add new entry
			if (ci < (int) newValues.count()) {
				ACell newVal = newValues.get(ci);
				if (newVal != null) col = col.assoc(ColumnIndex.indexKey(newVal, pk), pk);
			}

			result[0] = result[0].assoc(colName, col);
		});
		return (Index<AString, Index<ABlob, ABlob>>) result[0];
	}

	/**
	 * Returns a copy of the indices map with the given row removed (delete/tombstone).
	 */
	@SuppressWarnings("unchecked")
	static Index<AString, Index<ABlob, ABlob>> indexRemoveRow(
			Index<AString, Index<ABlob, ABlob>> indices,
			AVector<AVector<ACell>> schema,
			ABlob pk, AVector<ACell> values) {
		if (indices == null || indices.count() == 0) return indices;

		@SuppressWarnings("rawtypes")
		final Index[] result = {indices};
		indices.forEach((colName, colIndex) -> {
			int ci = findColIdxInSchema(schema, (AString) colName);
			if (ci < 0 || ci >= (int) values.count()) return;
			ACell val = values.get(ci);
			if (val == null) return;
			Index<ABlob, ABlob> col = (Index<ABlob, ABlob>) colIndex;
			col = col.dissoc(ColumnIndex.indexKey(val, pk));
			result[0] = result[0].assoc(colName, col);
		});
		return (Index<AString, Index<ABlob, ABlob>>) result[0];
	}

	/**
	 * Extends or updates the indices slot (slot 5) in a state vector.
	 */
	private static AVector<ACell> withIndices(AVector<ACell> state,
			ACell blockVec, CVMLong liveCount,
			Index<AString, Index<ABlob, ABlob>> indices,
			ACell rows, ACell timestamp) {
		return Vectors.of(state.get(POS_SCHEMA), rows, timestamp, liveCount, blockVec, indices);
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
	 * Gets the rows index from this table.
	 *
	 * @return Row block index (ABlob prefix → RowBlock), or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public Index<ABlob, ACell> getRows() {
		AVector<ACell> state = cursor.get();
		if (state == null) return null;
		return (Index<ABlob, ACell>) state.get(POS_ROWS);
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
	 * Gets the sequential block vector (slot 4) for fast full scans.
	 * Returns null if not present, invalidated (null slot), or if slot 4 holds
	 * a non-AVector value (e.g. VersionedSQLTable's history Index).
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getBlockVec() {
		AVector<ACell> state = cursor.get();
		if (state == null || state.count() <= POS_BLOCK_VEC) return null;
		ACell slot = state.get(POS_BLOCK_VEC);
		return (slot instanceof AVector) ? (AVector<ACell>) slot : null;
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
	 * Gets the number of live (non-tombstone) rows. O(1).
	 */
	@SuppressWarnings("unchecked")
	public long getRowCount() {
		AVector<ACell> state = cursor.get();
		if (state == null) return 0;
		if (state.count() <= POS_LIVE_COUNT) {
			// Legacy state vector without liveCount — fall back to Index.count()
			Index<ABlob, ACell> rows = getRows();
			return (rows != null) ? rows.count() : 0;
		}
		CVMLong lc = (CVMLong) state.get(POS_LIVE_COUNT);
		return (lc != null) ? lc.longValue() : 0;
	}

	// ========== Secondary Index Methods ==========

	/**
	 * Returns true if a secondary index exists on the named column.
	 */
	public boolean hasColumnIndex(AString colName) {
		Index<AString, Index<ABlob, ABlob>> indices = getIndicesFromState(cursor.get());
		return indices != null && indices.get(colName) != null;
	}

	/**
	 * Creates a secondary index on the named column.
	 * Builds the index from existing rows. Returns false if the column doesn't exist
	 * or the index already exists.
	 */
	@SuppressWarnings("unchecked")
	public boolean createColumnIndex(AString colName) {
		boolean[] created = {false};
		cursor.updateAndGet(state -> {
			if (state == null || state.get(POS_SCHEMA) == null) return state;
			AVector<AVector<ACell>> schema = (AVector<AVector<ACell>>) state.get(POS_SCHEMA);
			int colIdx = findColIdxInSchema(schema, colName);
			if (colIdx < 0) return state; // column not found

			ACell rawIndices = (state.count() > POS_INDICES) ? state.get(POS_INDICES) : null;
			Index<AString, Index<ABlob, ABlob>> indices =
				(rawIndices instanceof Index) ? (Index<AString, Index<ABlob, ABlob>>) rawIndices
				                              : Index.none();
			if (indices.get(colName) != null) return state; // already exists

			// Build index from existing rows
			Index<ABlob, ABlob> colIndex = buildColumnIndex(state, colIdx);
			indices = indices.assoc(colName, colIndex);
			created[0] = true;

			// Extend state to 6 slots if needed
			if (state.count() <= POS_INDICES) {
				return state.append(indices);
			}
			return state.assoc(POS_INDICES, indices);
		});
		return created[0];
	}

	/**
	 * Drops the secondary index on the named column.
	 * Returns false if no such index exists.
	 */
	@SuppressWarnings("unchecked")
	public boolean dropColumnIndex(AString colName) {
		boolean[] dropped = {false};
		cursor.updateAndGet(state -> {
			if (state == null || state.count() <= POS_INDICES) return state;
			ACell rawIndices = state.get(POS_INDICES);
			if (!(rawIndices instanceof Index)) return state;
			Index<AString, Index<ABlob, ABlob>> indices =
				(Index<AString, Index<ABlob, ABlob>>) rawIndices;
			if (indices.get(colName) == null) return state; // doesn't exist
			indices = indices.dissoc(colName);
			dropped[0] = true;
			return state.assoc(POS_INDICES, indices);
		});
		return dropped[0];
	}

	/**
	 * Looks up a single row by its primary key blob (bypassing ACell→ABlob conversion).
	 * Returns the column values vector, or null if not found or tombstoned.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> selectByKeyBlob(ABlob pk) {
		Index<ABlob, ACell> rows = getRows();
		if (rows == null) return null;
		ABlob bk = RowBlock.blockKey(pk);
		ACell block = rows.get(bk);
		AVector<ACell> row = RowBlock.get(block, pk);
		if (row == null || !SQLRow.isLive(row)) return null;
		return SQLRow.getValues(row);
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
			Index<ABlob, ACell> rows = (Index<ABlob, ACell>) state.get(POS_ROWS);
			if (rows == null) rows = BlockTableLattice.INSTANCE.zero();
			ABlob bk = RowBlock.blockKey(pk);
			ACell block = rows.get(bk);
			AVector<ACell> existing = RowBlock.get(block, pk);
			boolean addsLive = (existing == null || !SQLRow.isLive(existing));
			AVector<ACell> oldValues = (existing != null && SQLRow.isLive(existing))
				? SQLRow.getValues(existing) : null;
			ACell newBlock = RowBlock.put(block, pk, SQLRow.create(values, timestamp));
			rows = rows.assoc(bk, newBlock);
			long liveCount = getLiveCount(state) + (addsLive ? 1 : 0);
			result[0] = true;

			// Update column indices (remove old entry if updating, add new)
			AVector<AVector<ACell>> schema = (AVector<AVector<ACell>>) state.get(POS_SCHEMA);
			Index<AString, Index<ABlob, ABlob>> indices =
				indexAddRow(getIndicesFromState(state), schema, pk, oldValues, values);

			return withIndices(state, null, CVMLong.create(liveCount), indices, rows, timestamp);
		});
		return result[0];
	}

	/**
	 * Batch-inserts pre-sorted rows in a single atomic update.
	 *
	 * <p>Rows sharing the same block-key prefix are grouped into one block and
	 * committed with a single {@code Index.assoc()} call, reducing intermediate
	 * allocation from O(N × trie-depth) to O(blocks × trie-depth).
	 *
	 * @param sortedEntries (pk → row-values) pairs sorted by pk ascending
	 * @param timestamp     Write timestamp
	 * @return number of newly-live rows inserted
	 */
	@SuppressWarnings("unchecked")
	public int insertRows(List<Map.Entry<ABlob, AVector<ACell>>> sortedEntries, CVMLong timestamp) {
		if (sortedEntries.isEmpty()) return 0;
		int[] newLive = {0};
		cursor.updateAndGet(state -> {
			if (state == null || state.get(POS_SCHEMA) == null) return state;
			Index<ABlob, ACell> rows = (Index<ABlob, ACell>) state.get(POS_ROWS);
			if (rows == null) rows = BlockTableLattice.INSTANCE.zero();

			// Group entries by block key, then use putAll for each block.
			// For fresh (empty) tables: collect blocks inline to build blockVec at O(batch)
			// cost. For non-empty tables: invalidate blockVec (avoids O(n) full-Index traversal).
			boolean wasEmpty = getLiveCount(state) == 0;
			ABlob curBk = null;
			List<ABlob> blockPks = new ArrayList<>();
			List<AVector<ACell>> blockRows = new ArrayList<>();
			List<ACell> blockList = wasEmpty ? new ArrayList<>() : null;

			for (var e : sortedEntries) {
				ABlob pk = e.getKey();
				ABlob bk = RowBlock.blockKey(pk);
				if (curBk == null || !bk.equals(curBk)) {
					if (curBk != null) {
						ACell existing = rows.get(curBk);
						ACell newBlock = RowBlock.putAll(existing, blockPks, blockRows, newLive);
						rows = rows.assoc(curBk, newBlock);
						if (blockList != null) blockList.add(newBlock);
						blockPks = new ArrayList<>();
						blockRows = new ArrayList<>();
					}
					curBk = bk;
				}
				blockPks.add(pk);
				blockRows.add(SQLRow.create(e.getValue(), timestamp));
			}
			if (curBk != null) {
				ACell existing = rows.get(curBk);
				ACell newBlock = RowBlock.putAll(existing, blockPks, blockRows, newLive);
				rows = rows.assoc(curBk, newBlock);
				if (blockList != null) blockList.add(newBlock);
			}

			long liveCount = getLiveCount(state) + newLive[0];
			// blockVec: inline-built for fresh tables (O(batch)), null for incremental inserts
			AVector<ACell> blockVec = (blockList != null) ? Vectors.create(blockList) : null;

			// Update column indices for all inserted rows
			AVector<AVector<ACell>> schema = (AVector<AVector<ACell>>) state.get(POS_SCHEMA);
			Index<AString, Index<ABlob, ABlob>> indices = getIndicesFromState(state);
			if (indices != null && indices.count() > 0) {
				for (var e : sortedEntries) {
					// For batch inserts (typically fresh tables), no old-value removal needed
					indices = indexAddRow(indices, schema, e.getKey(), null, e.getValue());
				}
			}

			return withIndices(state, blockVec, CVMLong.create(liveCount), indices, rows, timestamp);
		});
		return newLive[0];
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
			Index<ABlob, ACell> rows = (Index<ABlob, ACell>) state.get(POS_ROWS);
			if (rows == null) return state;
			ABlob bk = RowBlock.blockKey(key);
			ACell block = rows.get(bk);
			AVector<ACell> existing = RowBlock.get(block, key);
			if (existing == null || !SQLRow.isLive(existing)) return state;
			AVector<ACell> oldValues = SQLRow.getValues(existing);
			ACell newBlock = RowBlock.put(block, key, SQLRow.createTombstone(timestamp));
			rows = rows.assoc(bk, newBlock);
			long liveCount = getLiveCount(state) - 1;
			result[0] = true;

			// Remove from column indices
			AVector<AVector<ACell>> schema = (AVector<AVector<ACell>>) state.get(POS_SCHEMA);
			Index<AString, Index<ABlob, ABlob>> indices =
				indexRemoveRow(getIndicesFromState(state), schema, key, oldValues);

			// Invalidate blockVec (tombstone inserted; scan must skip it)
			return withIndices(state, null, CVMLong.create(liveCount), indices, rows, timestamp);
		});
		return result[0];
	}

	// ========== Helpers ==========

	/**
	 * Extracts the live count from a state vector, handling legacy format.
	 */
	static long getLiveCount(AVector<ACell> state) {
		if (state == null || state.count() <= POS_LIVE_COUNT) return 0;
		CVMLong lc = (CVMLong) state.get(POS_LIVE_COUNT);
		return (lc != null) ? lc.longValue() : 0;
	}

	/**
	 * Extracts blockVec from a raw state vector, or null if absent/invalid.
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> getBlockVecFromState(AVector<ACell> state) {
		if (state == null || state.count() <= POS_BLOCK_VEC) return null;
		ACell slot = state.get(POS_BLOCK_VEC);
		return (slot instanceof AVector) ? (AVector<ACell>) slot : null;
	}

	/**
	 * Computes live row count by scanning an Index. Used only during merge.
	 */
	@SuppressWarnings("unchecked")
	static long computeLiveCount(Index<ABlob, ACell> rows) {
		if (rows == null) return 0;
		long[] count = new long[1];
		rows.forEach((bk, block) -> {
			if (RowBlock.isBlock(block)) {
				RowBlock.forEach(block, (pk, row) -> { if (SQLRow.isLive(row)) count[0]++; });
			} else if (block instanceof AVector && SQLRow.isLive((AVector<ACell>)block)) {
				// Legacy single raw row entry (backward compat)
				count[0]++;
			}
		});
		return count[0];
	}

	/**
	 * Merges two column indices maps using union semantics.
	 * Entries from both sides are unioned. Stale entries (for deleted rows) are
	 * filtered at query time by re-validating against the row store.
	 */
	@SuppressWarnings("unchecked")
	static Index<AString, Index<ABlob, ABlob>> mergeColumnIndices(
			Index<AString, Index<ABlob, ABlob>> a,
			Index<AString, Index<ABlob, ABlob>> b) {
		if (a == null || a.count() == 0) return b;
		if (b == null || b.count() == 0) return a;

		@SuppressWarnings({"unchecked", "rawtypes"})
		final Index[] result = {a};
		b.forEach((colName, bColIdx) -> {
			ACell rawA = ((Index<AString, ?>) result[0]).get(colName);
			if (!(rawA instanceof Index)) {
				// a doesn't have this column index — use b's
				result[0] = result[0].assoc(colName, bColIdx);
			} else {
				// Both have it — union merge: assoc all of b's entries into a's
				@SuppressWarnings("rawtypes")
				final Index[] mergedCol = {(Index) rawA};
				((Index<ABlob, ABlob>) bColIdx).forEach((k, v) ->
					mergedCol[0] = mergedCol[0].assoc(k, v));
				result[0] = result[0].assoc(colName, mergedCol[0]);
			}
		});
		return (Index<AString, Index<ABlob, ABlob>>) result[0];
	}

	// ========== Static Merge (used by lattice layer) ==========

	/**
	 * Merges two table state vectors.
	 * Schema uses LWW (latest timestamp wins).
	 * Rows merge using BlockTableLattice.
	 * Column indices use union merge.
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
		Index<ABlob, ACell> rowsA = (Index<ABlob, ACell>) a.get(POS_ROWS);
		Index<ABlob, ACell> rowsB = (Index<ABlob, ACell>) b.get(POS_ROWS);
		Index<ABlob, ACell> mergedRows = BlockTableLattice.INSTANCE.merge(rowsA, rowsB);

		// Compute live count and blockVec for merged result
		long liveCount;
		AVector<ACell> blockVec;
		Index<AString, Index<ABlob, ABlob>> mergedIndices;
		if (mergedRows == rowsA) {
			liveCount = getLiveCount(a);
			blockVec = getBlockVecFromState(a);
			mergedIndices = getIndicesFromState(a);
		} else if (mergedRows == rowsB) {
			liveCount = getLiveCount(b);
			blockVec = getBlockVecFromState(b);
			mergedIndices = getIndicesFromState(b);
		} else {
			// Rows actually merged — recompute liveCount; invalidate blockVec;
			// union-merge column indices (stale entries filtered at query time)
			liveCount = computeLiveCount(mergedRows);
			blockVec = null;
			mergedIndices = mergeColumnIndices(
				getIndicesFromState(a), getIndicesFromState(b));
		}

		// Return merged table with schema winner's schema
		return Vectors.of(schemaWinner.get(POS_SCHEMA), mergedRows,
			schemaWinner.get(POS_UTIME), CVMLong.create(liveCount),
			blockVec, mergedIndices);
	}
}
