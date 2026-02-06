package convex.lattice.queue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;

/**
 * Lattice implementation for a Kafka-style queue partition.
 *
 * <p>The queue state is an AVector&lt;ACell&gt; with four slots:</p>
 * <ul>
 *   <li>POS_ENTRIES (0) - Append-only vector of entry records</li>
 *   <li>POS_META (1) - Queue metadata as keyword map</li>
 *   <li>POS_TIMESTAMP (2) - Last update timestamp</li>
 *   <li>POS_START_OFFSET (3) - Absolute offset of entries[0]</li>
 * </ul>
 *
 * <p>Merge strategy (single-leader model):</p>
 * <ul>
 *   <li>startOffset: max (truncation only advances)</li>
 *   <li>timestamp: max (latest update wins)</li>
 *   <li>metadata: map union (own wins on conflict)</li>
 *   <li>entries: align to max startOffset, take longer vector</li>
 * </ul>
 */
public class QueueLattice extends ALattice<AVector<ACell>> {

	public static final QueueLattice INSTANCE = new QueueLattice();

	public static final int POS_ENTRIES = 0;
	public static final int POS_META = 1;
	public static final int POS_TIMESTAMP = 2;
	public static final int POS_START_OFFSET = 3;
	public static final long STATE_LENGTH = 4;

	private static final AVector<ACell> ZERO = Vectors.of(Vectors.empty(), Maps.empty(), CVMLong.ZERO, CVMLong.ZERO);

	private QueueLattice() {
		// Singleton
	}

	@Override
	public AVector<ACell> merge(AVector<ACell> ownValue, AVector<ACell> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) {
			if (checkForeign(otherValue)) return otherValue;
			return zero();
		}

		// Fast path: identical values
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		// Extract slots
		long ownStart = getStartOffset(ownValue);
		long otherStart = getStartOffset(otherValue);
		long mergedStart = Math.max(ownStart, otherStart);

		long ownTs = getTimestamp(ownValue);
		long otherTs = getTimestamp(otherValue);
		long mergedTs = Math.max(ownTs, otherTs);

		// Merge metadata (union, own wins on conflict)
		AHashMap<ACell, ACell> ownMeta = getMeta(ownValue);
		AHashMap<ACell, ACell> otherMeta = getMeta(otherValue);
		AHashMap<ACell, ACell> mergedMeta = mergeMeta(ownMeta, otherMeta);

		// Merge entries: align to merged start offset, take longer
		AVector<ACell> ownEntries = getEntries(ownValue);
		AVector<ACell> otherEntries = getEntries(otherValue);

		AVector<ACell> ownAligned = alignEntries(ownEntries, ownStart, mergedStart);
		AVector<ACell> otherAligned = alignEntries(otherEntries, otherStart, mergedStart);

		AVector<ACell> mergedEntries;
		if (ownAligned.count() >= otherAligned.count()) {
			mergedEntries = ownAligned;
		} else {
			mergedEntries = otherAligned;
		}

		// Build merged state
		return Vectors.of(mergedEntries, mergedMeta, CVMLong.create(mergedTs), CVMLong.create(mergedStart));
	}

	@Override
	public AVector<ACell> zero() {
		return ZERO;
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		if (value == null) return false;
		if (!(value instanceof AVector)) return false;
		if (value.count() < STATE_LENGTH) return false;
		// Check startOffset is a CVMLong
		ACell startOffset = value.get(POS_START_OFFSET);
		if (!(startOffset instanceof CVMLong)) return false;
		// Check timestamp is a CVMLong
		ACell timestamp = value.get(POS_TIMESTAMP);
		if (!(timestamp instanceof CVMLong)) return false;
		return true;
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null;
	}

	// ===== Static helpers =====

	/**
	 * Gets the entries vector from queue state.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> getEntries(AVector<ACell> state) {
		if (state == null) return Vectors.empty();
		ACell entries = state.get(POS_ENTRIES);
		if (entries == null) return Vectors.empty();
		return (AVector<ACell>) entries;
	}

	/**
	 * Gets the metadata map from queue state.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, ACell> getMeta(AVector<ACell> state) {
		if (state == null) return Maps.empty();
		ACell meta = state.get(POS_META);
		if (meta == null) return Maps.empty();
		return (AHashMap<ACell, ACell>) meta;
	}

	/**
	 * Gets the update timestamp from queue state as a long.
	 */
	public static long getTimestamp(AVector<ACell> state) {
		if (state == null) return 0L;
		ACell ts = state.get(POS_TIMESTAMP);
		if (ts == null) return 0L;
		return ((CVMLong) ts).longValue();
	}

	/**
	 * Gets the start offset from queue state as a long.
	 */
	public static long getStartOffset(AVector<ACell> state) {
		if (state == null) return 0L;
		ACell offset = state.get(POS_START_OFFSET);
		if (offset == null) return 0L;
		return ((CVMLong) offset).longValue();
	}

	/**
	 * Aligns an entries vector to a new (potentially higher) start offset
	 * by slicing off leading entries.
	 */
	private static AVector<ACell> alignEntries(AVector<ACell> entries, long currentStart, long newStart) {
		if (newStart <= currentStart) return entries;
		long trim = newStart - currentStart;
		if (trim >= entries.count()) return Vectors.empty();
		return entries.slice(trim);
	}

	/**
	 * Merges two metadata maps. Own entries take precedence on key conflict.
	 */
	@SuppressWarnings("unchecked")
	private static AHashMap<ACell, ACell> mergeMeta(AHashMap<ACell, ACell> own, AHashMap<ACell, ACell> other) {
		if (other == null || other.isEmpty()) return own;
		if (own == null || own.isEmpty()) return other;
		// own.merge(other) includes all of other's entries,
		// but own's entries take precedence
		return own.merge(other);
	}
}
