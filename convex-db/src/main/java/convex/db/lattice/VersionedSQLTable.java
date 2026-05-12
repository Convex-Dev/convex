package convex.db.lattice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.generic.IndexLattice;

/**
 * A system-versioned SQL table: extends {@link SQLTable} with a history index
 * that records every insert, update, and deletion with a nanotime timestamp.
 *
 * <p>State vector: {@code [schema, rows, utime, liveCount, history]}
 * <ul>
 *   <li>{@code history} — {@code Index<ABlob, AVector<ACell>>} keyed by
 *       {@link HistoryKey} (pk ++ nanotime), value = {@code [Blob(values)|null, nanotime, changeType]};
 *       use {@link #getHistoryValues} to decode the values slot</li>
 * </ul>
 *
 * <p>Change types:
 * <ul>
 *   <li>{@link #CT_INSERT} = 1</li>
 *   <li>{@link #CT_UPDATE} = 2</li>
 *   <li>{@link #CT_DELETE} = 3</li>
 * </ul>
 *
 * <p>Deduplication: writes are skipped if the incoming values are identical to the
 * current live row (same content → same Etch cell hash).
 *
 * <p>Merge: history uses union semantics — history keys are unique by design
 * (pk + nanotime), so merging two replicas simply takes all entries from both.
 */
public class VersionedSQLTable extends SQLTable {

	/** Position of the history index in the versioned state vector. */
	static final int POS_HISTORY = 4;

	/** Change type: row was inserted (no prior live row for this pk). */
	public static final long CT_INSERT = 1;
	/** Change type: row was updated (prior live row existed). */
	public static final long CT_UPDATE = 2;
	/** Change type: row was deleted. */
	public static final long CT_DELETE = 3;

	/**
	 * Leaf lattice for individual history entries.
	 * History keys are unique by (pk, nanotime), so conflicts do not occur in practice.
	 * When they do (same nanotime), own value wins per lattice convention.
	 */
	@SuppressWarnings("unchecked")
	static final ALattice<AVector<ACell>> HISTORY_ENTRY_LATTICE = new ALattice<>() {
		@Override public AVector<ACell> zero() { return null; }
		@Override public AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) { return a != null ? a : b; }
		@Override public boolean checkForeign(AVector<ACell> v) { return v instanceof AVector; }
		@Override public <T extends ACell> ALattice<T> path(ACell k) { return null; }
	};

	/** IndexLattice for the history slot — union merge via the identity entry lattice. */
	static final IndexLattice<ABlob, AVector<ACell>> HISTORY_LATTICE =
		IndexLattice.create(HISTORY_ENTRY_LATTICE);

	VersionedSQLTable(ALatticeCursor<AVector<ACell>> cursor) {
		super(cursor);
	}

	// ── State factories ──────────────────────────────────────────────────────

	/**
	 * Creates the initial 5-slot state vector for a new versioned table.
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> createState(AVector<AVector<ACell>> schema, CVMLong timestamp) {
		return Vectors.of(
			schema,
			(Index<ABlob, ACell>) Index.EMPTY,             // rows
			timestamp,
			CVMLong.ZERO,                                   // liveCount
			(Index<ABlob, AVector<ACell>>) Index.EMPTY     // history
		);
	}

	// ── History accessor ─────────────────────────────────────────────────────

	/** Returns the raw history index from the current cursor state. */
	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> getHistoryIndex() {
		return historyFrom(getState());
	}

	// ── Versioned mutations ──────────────────────────────────────────────────

	/**
	 * Inserts or updates a row, also appending a history entry.
	 * No-op (returns false) if values are identical to the current live row.
	 *
	 * @param pk             Primary key blob
	 * @param values         Full row values vector
	 * @param nanotime       Monotonic timestamp for history ordering
	 * @param milliTimestamp Wall-clock timestamp for LWW conflict resolution
	 * @return true if the row was written; false if skipped as duplicate
	 */
	@SuppressWarnings("unchecked")
	public boolean insertRowVersioned(ABlob pk, AVector<ACell> values, long nanotime, CVMLong milliTimestamp) {
		boolean[] changed = {false};
		cursor.updateAndGet(state -> {
			if (!isLiveState(state)) return state;

			Index<ABlob, ACell> rows = rowsFrom(state);
			ABlob bk = RowBlock.blockKey(pk);
			ACell block = rows.get(bk);
			AVector<ACell> existing = RowBlock.get(block, pk);

			// Deduplication: identical content is a no-op
			if (existing != null && SQLRow.isLive(existing) && values.equals(SQLRow.getValues(existing)))
				return state;

			long changeType = (existing == null || !SQLRow.isLive(existing)) ? CT_INSERT : CT_UPDATE;
			boolean addsLive = (existing == null || !SQLRow.isLive(existing));

			rows = rows.assoc(bk, RowBlock.put(block, pk, SQLRow.create(values, milliTimestamp)));
			long liveCount = getLiveCount(state) + (addsLive ? 1 : 0);

			Index<ABlob, AVector<ACell>> history = historyFrom(state);
			history = history.assoc(
				HistoryKey.of(pk, nanotime),
				Vectors.of(SQLRow.encodeValues(values), CVMLong.create(nanotime), CVMLong.create(changeType))
			);

			changed[0] = true;
			return buildState(state.get(POS_SCHEMA), rows, milliTimestamp, CVMLong.create(liveCount), history);
		});
		return changed[0];
	}

	/**
	 * Batch-inserts pre-sorted rows with history tracking in a single atomic update.
	 *
	 * <p>Applies the same block-grouping optimisation as {@link SQLTable#insertRows}
	 * and additionally records one history entry per non-deduplicated row.
	 * Nanotimes are assigned sequentially from {@code startNanotime} to guarantee
	 * ordering within the batch.
	 *
	 * @param sortedEntries  (pk → row-values) pairs sorted by pk ascending
	 * @param startNanotime  Monotonic base timestamp; incremented per row written
	 * @param milliTimestamp Wall-clock timestamp for LWW conflict resolution
	 * @return number of newly-live rows inserted
	 */
	@SuppressWarnings("unchecked")
	public int insertRowsVersioned(List<Map.Entry<ABlob, AVector<ACell>>> sortedEntries,
			long startNanotime, CVMLong milliTimestamp) {
		if (sortedEntries.isEmpty()) return 0;
		int[] newLive = {0};
		cursor.updateAndGet(state -> {
			if (!isLiveState(state)) return state;
			Index<ABlob, ACell> rows = rowsFrom(state);
			Index<ABlob, AVector<ACell>> history = historyFrom(state);

			ABlob curBk = null;
			List<ABlob> blockPks = new ArrayList<>();
			List<AVector<ACell>> blockRows = new ArrayList<>();
			// We also need to track history entries per block group
			// but history entries are individual per row — collect them separately
			long nanotime = startNanotime;

			// We need to handle deduplication check before batching
			// so we use the single-row path for history but batch for blocks
			// Re-implement with block-grouped putAll for rows, individual history entries
			for (var e : sortedEntries) {
				ABlob pk = e.getKey();
				AVector<ACell> values = e.getValue();
				ABlob bk = RowBlock.blockKey(pk);

				if (curBk == null || !bk.equals(curBk)) {
					if (curBk != null) {
						ACell existing = rows.get(curBk);
						ACell newBlock = RowBlock.putAll(existing, blockPks, blockRows, null);
						rows = rows.assoc(curBk, newBlock);
						blockPks = new ArrayList<>();
						blockRows = new ArrayList<>();
					}
					curBk = bk;
				}

				// Check existing for deduplication and live-count tracking
				// We need to look up in the current rows index (before this batch)
				// For simplicity with dedup: read from rows as currently modified
				// (We flush each block group before moving to the next, so rows is up to date for prior groups)
				ACell curBlock = rows.get(bk);
				// Also account for already-queued entries in blockPks for this block
				AVector<ACell> existing = RowBlock.get(curBlock, pk);
				// Check deduplication
				if (existing != null && SQLRow.isLive(existing)
						&& values.equals(SQLRow.getValues(existing))) {
					nanotime++;
					continue;
				}

				long changeType = (existing == null || !SQLRow.isLive(existing)) ? CT_INSERT : CT_UPDATE;
				if (changeType == CT_INSERT) newLive[0]++;

				blockPks.add(pk);
				blockRows.add(SQLRow.create(values, milliTimestamp));

				history = history.assoc(
					HistoryKey.of(pk, nanotime),
					Vectors.of(SQLRow.encodeValues(values), CVMLong.create(nanotime), CVMLong.create(changeType))
				);
				nanotime++;
			}
			if (curBk != null && !blockPks.isEmpty()) {
				ACell existing = rows.get(curBk);
				ACell newBlock = RowBlock.putAll(existing, blockPks, blockRows, null);
				rows = rows.assoc(curBk, newBlock);
			}

			long liveCount = getLiveCount(state) + newLive[0];
			return buildState(state.get(POS_SCHEMA), rows, milliTimestamp, CVMLong.create(liveCount), history);
		});
		return newLive[0];
	}

	/**
	 * Marks a row as deleted, also appending a history entry with {@link #CT_DELETE}.
	 * No-op if the row does not exist or is already deleted.
	 *
	 * @param pk             Primary key blob
	 * @param nanotime       Monotonic timestamp for history ordering
	 * @param milliTimestamp Wall-clock timestamp for LWW conflict resolution
	 * @return true if the row was deleted; false if not found
	 */
	@SuppressWarnings("unchecked")
	public boolean deleteRowVersioned(ABlob pk, long nanotime, CVMLong milliTimestamp) {
		boolean[] changed = {false};
		cursor.updateAndGet(state -> {
			if (!isLiveState(state)) return state;

			Index<ABlob, ACell> rows = rowsFrom(state);
			ABlob bk = RowBlock.blockKey(pk);
			ACell block = rows.get(bk);
			AVector<ACell> existing = RowBlock.get(block, pk);
			if (existing == null || !SQLRow.isLive(existing)) return state;

			rows = rows.assoc(bk, RowBlock.put(block, pk, SQLRow.createTombstone(milliTimestamp)));
			long liveCount = getLiveCount(state) - 1;

			Index<ABlob, AVector<ACell>> history = historyFrom(state);
			history = history.assoc(
				HistoryKey.of(pk, nanotime),
				Vectors.of(null, CVMLong.create(nanotime), CVMLong.create(CT_DELETE))
			);

			changed[0] = true;
			return buildState(state.get(POS_SCHEMA), rows, milliTimestamp, CVMLong.create(liveCount), history);
		});
		return changed[0];
	}

	// ── History entry accessors ──────────────────────────────────────────────

	/**
	 * Gets the column values from a history entry.
	 * Handles v3 compact (Blob-encoded) and legacy (AVector) formats.
	 * Returns null for delete entries.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> getHistoryValues(AVector<ACell> histEntry) {
		if (histEntry == null) return null;
		ACell cell = histEntry.get(0);
		if (cell == null) return null;                             // delete entry
		if (cell instanceof Blob blob) return SQLRow.decodeValues(blob); // v3 compact
		return (AVector<ACell>) cell;                              // legacy
	}

	// ── Temporal queries ─────────────────────────────────────────────────────

	/**
	 * Returns all history entries for the given pk, oldest first.
	 * Each entry is {@code [values|null, CVMLong(nanotime), CVMLong(changeType)]}.
	 *
	 * @param pk Primary key blob
	 * @return Ordered list of history entries; empty if none recorded
	 */
	public List<AVector<ACell>> getHistory(ABlob pk) {
		ABlob prefix = HistoryKey.prefix(pk);
		List<AVector<ACell>> result = new ArrayList<>();
		getHistoryIndex().forEach((k, v) -> {
			if (HistoryKey.hasPrefix(k, prefix)) result.add(v);
		});
		return result;
	}

	/**
	 * Returns the latest history entry for pk at or before the given nanotime
	 * (i.e., the row as it existed at that point in time).
	 *
	 * @param pk       Primary key blob
	 * @param nanotime Upper bound timestamp (inclusive)
	 * @return History entry, or null if no version existed at that point
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getAsOf(ABlob pk, long nanotime) {
		ABlob prefix = HistoryKey.prefix(pk);
		AVector<ACell>[] best = new AVector[1];
		getHistoryIndex().forEach((k, v) -> {
			if (HistoryKey.hasPrefix(k, prefix) && HistoryKey.extractNanotime(k) <= nanotime)
				best[0] = v; // forEach is ordered → last match is the latest ≤ nanotime
		});
		return best[0];
	}

	// ── Merge ────────────────────────────────────────────────────────────────

	/**
	 * Merges two versioned table state vectors.
	 * Delegates schema + rows to {@link SQLTable#merge}, then union-merges history.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		AVector<ACell> base = SQLTable.merge(a, b);
		if (base == null) return null;

		Index<ABlob, AVector<ACell>> histA = historyFrom(a);
		Index<ABlob, AVector<ACell>> histB = historyFrom(b);
		Index<ABlob, AVector<ACell>> merged = HISTORY_LATTICE.merge(histA, histB);

		// If history is unchanged from the base winner, no new vector needed
		if (base.count() > POS_HISTORY && base.get(POS_HISTORY) == merged) return base;

		return buildState(
			base.get(POS_SCHEMA),
			(Index<ABlob, ACell>) base.get(POS_ROWS),
			(CVMLong) base.get(POS_UTIME),
			(CVMLong) base.get(POS_LIVE_COUNT),
			merged
		);
	}

	// ── Private helpers ──────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	static Index<ABlob, ACell> rowsFrom(AVector<ACell> state) {
		if (state == null) return (Index<ABlob, ACell>) Index.EMPTY;
		ACell r = state.get(POS_ROWS);
		return r != null ? (Index<ABlob, ACell>) r : (Index<ABlob, ACell>) Index.EMPTY;
	}

	@SuppressWarnings("unchecked")
	static Index<ABlob, AVector<ACell>> historyFrom(AVector<ACell> state) {
		if (state == null || state.count() <= POS_HISTORY) return (Index<ABlob, AVector<ACell>>) Index.EMPTY;
		ACell h = state.get(POS_HISTORY);
		return h != null ? (Index<ABlob, AVector<ACell>>) h : (Index<ABlob, AVector<ACell>>) Index.EMPTY;
	}

	static AVector<ACell> buildState(ACell schema,
			Index<ABlob, ACell> rows, CVMLong utime,
			CVMLong liveCount, Index<ABlob, AVector<ACell>> history) {
		return Vectors.of(schema, rows, utime, liveCount, history);
	}
}
