package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ALattice;

/**
 * Lattice for merging KV entries (AVector&lt;ACell&gt;).
 *
 * Merge rules (modelled on DLFSNode.merge):
 * <ol>
 *   <li>Equal entries - return own</li>
 *   <li>One side null - return the other</li>
 *   <li>Same mergeable type (hash, set, sortedset, counter) - type-specific structural merge</li>
 *   <li>Otherwise (string, list, type conflict) - LWW by timestamp</li>
 *   <li>Tombstone vs live - newer timestamp wins</li>
 * </ol>
 */
public class KVEntryLattice extends ALattice<AVector<ACell>> {

	public static final KVEntryLattice INSTANCE = new KVEntryLattice();

	private KVEntryLattice() {}

	@Override
	public AVector<ACell> merge(AVector<ACell> ownValue, AVector<ACell> otherValue) {
		if (ownValue == null) {
			if (otherValue != null && checkForeign(otherValue)) return otherValue;
			return zero();
		}
		if (otherValue == null) return ownValue;
		if (Utils.equals(ownValue, otherValue)) return ownValue;

		long timeA = KVEntry.getUTime(ownValue).longValue();
		long timeB = KVEntry.getUTime(otherValue).longValue();

		long typeA = KVEntry.getType(ownValue);
		long typeB = KVEntry.getType(otherValue);

		// If same type and type supports structural merge, do it
		if (typeA == typeB && typeA >= 0 && isMergeableType(typeA)) {
			return mergeByType(typeA, ownValue, otherValue, timeA, timeB);
		}

		// Otherwise LWW: newer timestamp wins, own value favoured on tie
		return timeA >= timeB ? ownValue : otherValue;
	}

	/**
	 * Returns true if the type supports structural (per-field/member) merge
	 * rather than whole-value LWW.
	 */
	private static boolean isMergeableType(long type) {
		return type == KVEntry.TYPE_HASH
			|| type == KVEntry.TYPE_SET
			|| type == KVEntry.TYPE_SORTED_SET
			|| type == KVEntry.TYPE_COUNTER;
	}

	/**
	 * Performs a type-specific structural merge of two entries with the same type.
	 */
	private AVector<ACell> mergeByType(long type, AVector<ACell> a, AVector<ACell> b, long timeA, long timeB) {
		ACell valueA = KVEntry.getValue(a);
		ACell valueB = KVEntry.getValue(b);
		ACell mergedValue;

		switch ((int) type) {
			case (int) KVEntry.TYPE_HASH:
				mergedValue = KVHash.merge(valueA, valueB);
				break;
			case (int) KVEntry.TYPE_SET:
				mergedValue = KVSet.merge(valueA, valueB);
				break;
			case (int) KVEntry.TYPE_SORTED_SET:
				mergedValue = KVSortedSet.merge(valueA, valueB);
				break;
			case (int) KVEntry.TYPE_COUNTER:
				mergedValue = KVCounter.merge(valueA, valueB);
				break;
			default:
				// Fallback to LWW
				return timeA >= timeB ? a : b;
		}

		// Take max timestamp, merge expiry (nil = no expiry wins over any timestamp)
		CVMLong mergedTime = timeA >= timeB ? KVEntry.getUTime(a) : KVEntry.getUTime(b);
		CVMLong expA = KVEntry.getExpire(a);
		CVMLong expB = KVEntry.getExpire(b);
		CVMLong mergedExpire;
		if (expA == null || expB == null) {
			mergedExpire = null; // no expiry wins
		} else {
			mergedExpire = (expA.longValue() >= expB.longValue()) ? expA : expB;
		}

		return KVEntry.create(mergedValue, type, mergedTime).assoc(KVEntry.POS_EXPIRE, mergedExpire);
	}

	@Override
	public AVector<ACell> zero() {
		return KVEntry.createTombstone(CVMLong.ZERO);
	}

	@Override
	public boolean checkForeign(AVector<ACell> value) {
		return KVEntry.isValid(value);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null;
	}
}
