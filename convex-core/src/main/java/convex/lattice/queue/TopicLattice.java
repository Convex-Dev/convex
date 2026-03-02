package convex.lattice.queue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.generic.MapLattice;

/**
 * Lattice for a Kafka-style topic containing partitions and metadata.
 *
 * <p>The topic state is an {@code Index<Keyword, ACell>} with two keys:</p>
 * <ul>
 *   <li>{@link #KEY_PARTITIONS} — partition map (AHashMap of partition-id → queue state)</li>
 *   <li>{@link #KEY_META} — topic metadata (AHashMap of keyword → value)</li>
 * </ul>
 *
 * <p>Merge strategy:</p>
 * <ul>
 *   <li>Partitions: delegate to MapLattice(QueueLattice) — per-partition queue merge</li>
 *   <li>Metadata: map union (own wins on conflict)</li>
 * </ul>
 */
public class TopicLattice extends ALattice<Index<Keyword, ACell>> {

	public static final TopicLattice INSTANCE = new TopicLattice();

	public static final Keyword KEY_PARTITIONS = Keyword.intern("partitions");
	public static final Keyword KEY_META = Keyword.intern("meta");

	static final MapLattice<ACell, AVector<ACell>> PARTITION_LATTICE =
		MapLattice.create(QueueLattice.INSTANCE);

	private TopicLattice() {
		// Singleton
	}

	@Override
	public Index<Keyword, ACell> merge(Index<Keyword, ACell> ownValue, Index<Keyword, ACell> otherValue) {
		if (otherValue == null) return ownValue;
		if (ownValue == null) {
			if (checkForeign(otherValue)) return otherValue;
			return zero();
		}
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		// Merge partitions via MapLattice
		AHashMap<ACell, AVector<ACell>> ownParts = getPartitions(ownValue);
		AHashMap<ACell, AVector<ACell>> otherParts = getPartitions(otherValue);
		AHashMap<ACell, AVector<ACell>> mergedParts = PARTITION_LATTICE.merge(ownParts, otherParts);

		// Merge metadata (map union, own wins on conflict)
		AHashMap<ACell, ACell> ownMeta = getMeta(ownValue);
		AHashMap<ACell, ACell> otherMeta = getMeta(otherValue);
		AHashMap<ACell, ACell> mergedMeta = mergeMeta(ownMeta, otherMeta);

		// Build result
		Index<Keyword, ACell> result = ownValue;
		if (!Utils.equals(mergedParts, ownParts)) {
			result = result.assoc(KEY_PARTITIONS, mergedParts);
		}
		if (!Utils.equals(mergedMeta, ownMeta)) {
			result = result.assoc(KEY_META, mergedMeta);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<Keyword, ACell> zero() {
		return (Index<Keyword, ACell>) Index.EMPTY;
	}

	@Override
	public boolean checkForeign(Index<Keyword, ACell> value) {
		return (value instanceof Index);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (childKey instanceof Keyword k) {
			if (k.equals(KEY_PARTITIONS)) return (ALattice<T>) PARTITION_LATTICE;
		}
		// No sub-navigation for metadata or unknown keys
		return null;
	}

	// ===== Static helpers =====

	/**
	 * Gets the partitions map from topic state.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, AVector<ACell>> getPartitions(Index<Keyword, ACell> state) {
		if (state == null) return Maps.empty();
		ACell parts = state.get(KEY_PARTITIONS);
		if (parts == null) return Maps.empty();
		return (AHashMap<ACell, AVector<ACell>>) parts;
	}

	/**
	 * Gets the metadata map from topic state.
	 */
	@SuppressWarnings("unchecked")
	public static AHashMap<ACell, ACell> getMeta(Index<Keyword, ACell> state) {
		if (state == null) return Maps.empty();
		ACell meta = state.get(KEY_META);
		if (meta == null) return Maps.empty();
		return (AHashMap<ACell, ACell>) meta;
	}

	/**
	 * Merges two metadata maps. Own entries take precedence on key conflict.
	 */
	private static AHashMap<ACell, ACell> mergeMeta(AHashMap<ACell, ACell> own, AHashMap<ACell, ACell> other) {
		if (other == null || other.isEmpty()) return own;
		if (own == null || own.isEmpty()) return other;
		return own.merge(other);
	}
}
