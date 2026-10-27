package convex.db.lattice;

import java.util.Objects;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;
import convex.db.ConvexDB;

/**
 * Per-row last-write-wins: a merge policy that Convex DB provides for databases whose
 * tables several writers update row by row. It is one choice among others: whatever
 * constructs a database supplies its policy, and an application that serialises its
 * updates, schema changes included, supplies its own. Pass {@code RowLWW::merge} to
 * choose this one.
 *
 * <ul>
 *   <li>Database: the {@code :tables} section merges with {@link #mergeTables}; other
 *       incoming sections are ignored and those already held are kept</li>
 *   <li>Table store: a union of table names, each entry merged with {@link #mergeTable}</li>
 *   <li>Table entry: the schema is last-writer-wins on ctime, the time the table was
 *       created or dropped, with the own entry kept on a tie; a dropped table's tombstone
 *       wins in the same way. Rows merge by key with {@link #mergeRows}, whichever schema
 *       wins</li>
 *   <li>Row: the latest timestamp wins; on equal timestamps a deletion wins, otherwise
 *       the own row is kept. A row held on one side only survives if it was written at
 *       or after the other side's ctime: a row written before a table was created
 *       belongs to an earlier table of that name</li>
 * </ul>
 *
 * <p>So rows written before a table is dropped and created again never return, and the
 * copies of a single writer's tables converge to the writer's own, as long as its clock
 * stamps each row at or after its table's ctime: a row stamped earlier is dropped, on the
 * writer's own copy too. Timestamping is the writer's choice. Two creations of a
 * table by different writers are different tables: rows written to the earlier one
 * before the later creation are dropped. Ordering schema changes between writers is
 * application policy.
 *
 * <p>A table entry without a ctime carries only rows, from a delivery below the entry
 * (see {@link SQLDatabase}). Its rows merge into a table that exists, live or dropped,
 * as those of a whole entry would; an absent table takes nothing.
 *
 * <p>Each piece is a pure function, so an application can compose a variant from them,
 * for example keeping its own entry for one table and using {@link #mergeTable} for the
 * others.
 */
public class RowLWW {

	private RowLWW() {}

	/**
	 * Merges two database states.
	 *
	 * @param own Established database state, kept on ties; null if absent
	 * @param other Database state to merge in; null if absent
	 * @return Merged database state, absent when own is absent and nothing is accepted
	 */
	@SuppressWarnings("unchecked")
	public static Index<Keyword, ACell> merge(Index<Keyword, ACell> own, Index<Keyword, ACell> other) {
		ACell incoming = other;
		if (!(incoming instanceof Index<?, ?> index) || !(index.get(ConvexDB.KEY_TABLES) instanceof Index<?, ?> tables)) return own;
		ACell current = (own == null) ? null : own.get(ConvexDB.KEY_TABLES);
		if (current != null && !(current instanceof Index)) return own;
		Index<AString, AVector<ACell>> merged = mergeTables(
			(Index<AString, AVector<ACell>>) current, (Index<AString, AVector<ACell>>) tables);
		if (Utils.equals(merged, current)) return own;
		return ((own == null) ? Index.<Keyword, ACell>none() : own).assoc(ConvexDB.KEY_TABLES, merged);
	}

	/**
	 * Merges two table stores: a union of table names, each entry merged with
	 * {@link #mergeTable}. Entries that are not table entries are ignored.
	 *
	 * @param own Established table store, kept on ties; null if absent
	 * @param other Table store to merge in; null if absent
	 * @return Merged table store, absent when own is absent and nothing is accepted
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Index<AString, AVector<ACell>> mergeTables(Index<AString, AVector<ACell>> own,
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
				if (!(key instanceof AString) || !(b instanceof AVector)) return a;
				if (a != null && !(a instanceof AVector)) return a;
				return mergeTable((AVector<ACell>) a, (AVector<ACell>) b);
			}
		});
		return (own == null && merged.isEmpty()) ? null : (Index) merged;
	}

	/**
	 * Merges two entries of one table. The schema is last-writer-wins on ctime, the time
	 * the table was created or dropped, with the own entry kept on a tie; a dropped table's
	 * tombstone wins in the same way. Rows merge by key with {@link #mergeRows}, whichever
	 * schema wins, so a dropped table holds the rows written after it was dropped. An entry
	 * without a ctime carries only rows, which merge into a table that exists, live or
	 * dropped; an absent table takes nothing.
	 *
	 * @param own Established entry, kept on ties; null if absent
	 * @param other Entry to merge in; null if absent
	 * @return Merged entry
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> mergeTable(AVector<ACell> own, AVector<ACell> other) {
		if (other == null) return own;
		CVMLong timeB = (CVMLong) other.get(SQLTable.POS_CTIME);
		if (own == null) return (timeB == null) ? null : other;
		CVMLong timeA = (CVMLong) own.get(SQLTable.POS_CTIME);

		// Determine schema winner (LWW)
		AVector<ACell> schemaWinner;
		if (timeA == null && timeB == null) {
			schemaWinner = own;
		} else if (timeA == null) {
			schemaWinner = other;
		} else if (timeB == null) {
			schemaWinner = own;
		} else if (timeB.longValue() > timeA.longValue()) {
			schemaWinner = other;
		} else {
			schemaWinner = own;
		}

		// Merge rows, whichever schema wins
		Index<ABlob, AVector<ACell>> rowsA = (Index<ABlob, AVector<ACell>>) own.get(SQLTable.POS_ROWS);
		Index<ABlob, AVector<ACell>> rowsB = (Index<ABlob, AVector<ACell>>) other.get(SQLTable.POS_ROWS);
		Index<ABlob, AVector<ACell>> mergedRows = mergeRows(rowsA, rowsB, timeA, timeB);
		if (mergedRows != null && mergedRows.isEmpty() && schemaWinner.get(SQLTable.POS_ROWS) == null) {
			mergedRows = null; // a tombstone holding no rows keeps its form
		}

		// Compute live count for merged result
		long liveCount;
		if (mergedRows == rowsA) {
			liveCount = SQLTable.getLiveCount(own);
		} else if (mergedRows == rowsB) {
			liveCount = SQLTable.getLiveCount(other);
		} else if (timeB == null && hasLiveCount(own)) {
			// Delivered rows only: adjust the count held without reading the rest of the table
			liveCount = SQLTable.getLiveCount(own) + liveChange(rowsA, mergedRows, rowsB);
		} else {
			// Rows actually merged — recompute
			liveCount = SQLTable.computeLiveCount(mergedRows);
		}

		// The schema winner's version. On a tie of timestamp and schema the higher version
		// is kept, restoring a version that an older implementation dropped when it
		// rebuilt the entry from four fields (CAD039).
		ACell version = SQLTable.versionField(schemaWinner);
		if (Objects.equals(timeA, timeB) && Objects.equals(own.get(SQLTable.POS_SCHEMA), other.get(SQLTable.POS_SCHEMA))) {
			long versionA = SQLTable.getVersion(own);
			long versionB = SQLTable.getVersion(other);
			if (versionA != versionB) {
				version = (versionA > versionB) ? SQLTable.versionField(own) : SQLTable.versionField(other);
			} else if (SQLTable.versionField(own) == null || SQLTable.versionField(other) == null) {
				version = null; // equal versions: prefer the form without a field
			}
		}

		// Return merged table with schema winner's schema
		return SQLTable.entry(schemaWinner.get(SQLTable.POS_SCHEMA), mergedRows, schemaWinner.get(SQLTable.POS_CTIME), liveCount, version);
	}

	/**
	 * Merges the rows of two entries of one table by primary key. A row held on both sides
	 * merges with {@link #mergeRow}. A row held on one side only survives if it was written
	 * at or after the other side's ctime: written before that table was created, it belongs
	 * to an earlier table of the same name, or before it was dropped, to the dropped table.
	 *
	 * <p>When {@code other} carries only rows, as a delivery below an entry does, every row
	 * held only by {@code own} survives, so those rows are not visited: the cost is
	 * proportional to the delivered rows, not to the table.</p>
	 *
	 * @param own Established rows; null if absent
	 * @param other Rows to merge in; null if absent
	 * @param ownTime Ctime of the entry holding {@code own}; null if it carries only rows
	 * @param otherTime Ctime of the entry holding {@code other}; null if it carries only rows
	 * @return Merged rows; null if both are absent
	 */
	public static Index<ABlob, AVector<ACell>> mergeRows(Index<ABlob, AVector<ACell>> own,
			Index<ABlob, AVector<ACell>> other, CVMLong ownTime, CVMLong otherTime) {
		if (own == null && other == null) return null;
		Index<ABlob, AVector<ACell>> base = (own == null) ? Index.none() : own;
		Index<ABlob, AVector<ACell>> incoming = (other == null) ? Index.none() : other;
		MergeFunction<AVector<ACell>> rule = new MergeFunction<AVector<ACell>>() {
			@Override
			public AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
				if (a == null) return writtenSince(b, ownTime) ? b : null;
				if (b == null) return writtenSince(a, otherTime) ? a : null;
				return mergeRow(a, b);
			}
		};
		return (otherTime == null) ? base.merge(incoming, rule) : base.mergeDifferences(incoming, rule);
	}

	/** Whether an entry holds a live row count, which entries written before the field existed lack. */
	private static boolean hasLiveCount(AVector<ACell> entry) {
		return entry.count() > SQLTable.POS_LIVE_COUNT && entry.get(SQLTable.POS_LIVE_COUNT) instanceof CVMLong;
	}

	/** The change in live rows from merging {@code delivered} into {@code before}, giving {@code after}. */
	private static long liveChange(Index<ABlob, AVector<ACell>> before, Index<ABlob, AVector<ACell>> after,
			Index<ABlob, AVector<ACell>> delivered) {
		if (delivered == null) return 0;
		long[] change = {0};
		delivered.forEach((key, row) -> {
			boolean was = (before != null) && SQLRow.isLive(before.get(key));
			boolean is = (after != null) && SQLRow.isLive(after.get(key));
			change[0] += (is ? 1 : 0) - (was ? 1 : 0);
		});
		return change[0];
	}

	/** Whether a row was written at or after a table's ctime; always so when there is none. */
	private static boolean writtenSince(AVector<ACell> row, CVMLong time) {
		if (time == null) return true;
		CVMLong written = SQLRow.getTimestamp(row);
		return written != null && written.longValue() >= time.longValue();
	}

	/**
	 * Merges two entries of one row. The latest timestamp wins; on equal timestamps a
	 * deletion wins, otherwise the own row is kept.
	 *
	 * @param own Established row entry; null if absent
	 * @param other Row entry to merge in; null if absent
	 * @return Merged row entry
	 */
	public static AVector<ACell> mergeRow(AVector<ACell> own, AVector<ACell> other) {
		if (own == null) return other;
		if (other == null) return own;

		CVMLong timeA = SQLRow.getTimestamp(own);
		CVMLong timeB = SQLRow.getTimestamp(other);

		if (timeA == null && timeB == null) return own;
		if (timeA == null) return other;
		if (timeB == null) return own;

		long ta = timeA.longValue();
		long tb = timeB.longValue();

		if (ta > tb) return own;
		if (tb > ta) return other;

		// Equal timestamps: deletion wins
		if (SQLRow.isTombstone(other)) return other;
		return own;
	}
}
