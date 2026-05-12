package convex.db.lattice;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.CAD3Encoder;
import convex.core.data.Cells;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

/**
 * Utility class for SQL row entries in the table lattice.
 *
 * <p>Current row format (v4):
 * <ul>
 *   <li>Live row  — 2-element vector: [blob_values, version_blob8]</li>
 *   <li>Tombstone — 3-element vector: [null,        version_blob8, version_blob8]</li>
 * </ul>
 * {@code blob_values} is the standard CAD3 encoding of the column-values AVector,
 * stored as a single {@link Blob}.  This reduces per-row heap cost by ~4× compared
 * to storing the AVector and its element cells separately.
 *
 * <p>{@code version_blob8} is an 8-byte {@link Blob} holding a monotonic write
 * sequence number supplied by {@link SQLSchema}.  The counter is initialised from
 * {@code System.currentTimeMillis()} at schema creation time and incremented
 * atomically on every write, so every row gets a unique version — no two writes
 * within a single schema instance can tie.
 *
 * <p>Legacy format (v3, read-only): [blob_values, utime_blob4] — 4-byte compact
 * seconds since {@link #COMPACT_EPOCH_S}.  Values decode to ~1.7e12, which is
 * below any v4 counter (also initialised near 1.7e12 from currentTimeMillis),
 * so old rows sort before new rows in LWW comparisons.
 * Legacy format (v2, read-only): [AVector(values), utime_blob4].
 * Legacy format (v1, read-only): [AVector(values), CVMLong_ms, CVMLong_ms_or_null].
 * {@link #getVersion} handles all formats transparently.
 *
 * <p>Merge semantics: highest version wins; equal versions → deletion wins.
 */
public class SQLRow {

	/** Position of column values in the row vector */
	static final int POS_VALUES  = 0;
	/** Position of version / write sequence number */
	static final int POS_UTIME   = 1;
	/** Position of deletion version (tombstones only) */
	static final int POS_DELETED = 2;

	/** Unix seconds for 2020-01-01 00:00:00 UTC — used only for v3 legacy decode. */
	static final long COMPACT_EPOCH_S = 1_577_836_800L;

	private SQLRow() {}

	// ── Version codec ───────────────────────────────────────────────────────

	/**
	 * Encodes a write-sequence version as an 8-byte Blob (v4 format).
	 * The {@code version} value is the raw long from {@link SQLSchema}'s write counter.
	 */
	static Blob encodeTimestamp(long version) {
		byte[] bs = new byte[8];
		Utils.writeLong(bs, 0, version);
		return Blob.wrap(bs);
	}

	/**
	 * Decodes the version from a row's timestamp blob.
	 * Handles v4 (8-byte, raw long), v3 (4-byte compact seconds), and
	 * v1/v2 (CVMLong milliseconds) transparently.
	 */
	static long decodeTimestampMs(ABlob blob) {
		if (blob.count() == 8) return blob.longValue();          // v4: raw sequence number
		long s = blob.longValue() & 0xFFFFFFFFL;                  // v3: unsigned 32-bit compact seconds
		return (COMPACT_EPOCH_S + s) * 1000L;
	}

	// ── Values codec (v3) ──────────────────────────────────────────────────

	/**
	 * Encodes column values to a compact Blob using standard CAD3 format.
	 * For a 4-column row the Blob is typically 30–50 bytes vs ~160 bytes for
	 * the equivalent AVector + child cells.
	 */
	static Blob encodeValues(AVector<ACell> values) {
		return Cells.encode(values);
	}

	/**
	 * Decodes column values from a compact Blob (v3 format).
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> decodeValues(Blob blob) {
		try {
			return (AVector<ACell>) CAD3Encoder.INSTANCE.decode(blob);
		} catch (BadFormatException e) {
			throw new IllegalStateException("Compact row decode failed", e);
		}
	}

	// ── Factory methods ────────────────────────────────────────────────────

	/**
	 * Creates a new live row entry (v3 compact format).
	 *
	 * @param values    Column values for the row
	 * @param timestamp Update timestamp (milliseconds)
	 * @return 2-element row vector [blob_values, utime_blob4]
	 */
	public static AVector<ACell> create(AVector<ACell> values, CVMLong timestamp) {
		return Vectors.of(encodeValues(values), encodeTimestamp(timestamp.longValue()));
	}

	/**
	 * Creates a tombstone entry for a deleted row.
	 *
	 * @param timestamp Deletion timestamp (milliseconds)
	 * @return 3-element tombstone vector [null, utime_blob4, deleted_blob4]
	 */
	public static AVector<ACell> createTombstone(CVMLong timestamp) {
		Blob ts = encodeTimestamp(timestamp.longValue());
		return Vectors.of(null, ts, ts);
	}

	// ── Accessors ──────────────────────────────────────────────────────────

	/**
	 * Gets the column values from a row entry.
	 * Handles v3 (Blob-encoded), v2 (AVector+Blob4), and v1 (AVector+CVMLong).
	 *
	 * @param row Row entry vector
	 * @return Column values, or null if tombstone
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> getValues(AVector<ACell> row) {
		if (row == null) return null;
		ACell cell = row.get(POS_VALUES);
		if (cell instanceof Blob blob) return decodeValues(blob);   // v3 compact
		return (AVector<ACell>) cell;                               // v1/v2 legacy
	}

	/**
	 * Gets the update timestamp as a CVMLong (milliseconds).
	 * Handles both compact Blob(4) format (v2) and legacy CVMLong format (v1).
	 *
	 * @param row Row entry vector
	 * @return Update timestamp in milliseconds, or null
	 */
	public static CVMLong getTimestamp(AVector<ACell> row) {
		if (row == null) return null;
		ACell cell = row.get(POS_UTIME);
		if (cell instanceof CVMLong) return (CVMLong) cell;          // v1 legacy
		if (cell instanceof ABlob)  return CVMLong.create(decodeTimestampMs((ABlob) cell)); // v2
		return null;
	}

	/**
	 * Gets the deletion timestamp from a tombstone row entry.
	 *
	 * @param row Row entry vector
	 * @return Deletion timestamp in milliseconds, or null if live
	 */
	public static CVMLong getDeleted(AVector<ACell> row) {
		if (row == null || row.count() < 3) return null;
		ACell cell = row.get(POS_DELETED);
		if (cell instanceof CVMLong) return (CVMLong) cell;
		if (cell instanceof ABlob)  return CVMLong.create(decodeTimestampMs((ABlob) cell));
		return null;
	}

	/**
	 * Checks if a row entry is a tombstone (deleted).
	 * Tombstones always have 3 elements; live rows have 2.
	 *
	 * @param row Row entry vector
	 * @return true if tombstone
	 */
	public static boolean isTombstone(AVector<ACell> row) {
		if (row == null) return false;
		return row.count() == 3;
	}

	/**
	 * Checks if a row entry is live (not deleted).
	 *
	 * @param row Row entry vector
	 * @return true if live
	 */
	public static boolean isLive(AVector<ACell> row) {
		return row != null && !isTombstone(row);
	}

	/**
	 * Updates a row entry with new values and a new timestamp.
	 *
	 * @param row       Original row entry (unused; kept for API symmetry)
	 * @param values    New column values
	 * @param timestamp New update timestamp (milliseconds)
	 * @return Updated live row entry
	 */
	public static AVector<ACell> withValues(AVector<ACell> row, AVector<ACell> values, CVMLong timestamp) {
		return Vectors.of(encodeValues(values), encodeTimestamp(timestamp.longValue()));
	}

	// ── Merge ──────────────────────────────────────────────────────────────

	/**
	 * Merges two row entries using LWW semantics.
	 * Latest timestamp wins. If equal, deletion wins.
	 *
	 * @param a First row entry
	 * @param b Second row entry
	 * @return Merged row entry
	 */
	public static AVector<ACell> merge(AVector<ACell> a, AVector<ACell> b) {
		if (a == null) return b;
		if (b == null) return a;

		CVMLong timeA = getTimestamp(a);
		CVMLong timeB = getTimestamp(b);

		if (timeA == null && timeB == null) return a;
		if (timeA == null) return b;
		if (timeB == null) return a;

		long ta = timeA.longValue();
		long tb = timeB.longValue();

		if (ta > tb) return a;
		if (tb > ta) return b;

		// Equal timestamps: deletion wins
		if (isTombstone(b)) return b;
		return a;
	}
}
