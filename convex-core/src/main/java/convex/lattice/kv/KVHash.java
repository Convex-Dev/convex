package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;

/**
 * Helper for Redis Hash operations.
 *
 * Hash value is stored as Index&lt;AString, AVector&lt;ACell&gt;&gt; where each field maps
 * to [value, timestamp]. Per-field LWW merge by timestamp.
 */
public class KVHash {

	public static final int FIELD_VALUE = 0;
	public static final int FIELD_TIMESTAMP = 1;

	/**
	 * Creates an empty hash value
	 */
	@SuppressWarnings("unchecked")
	public static Index<AString, AVector<ACell>> empty() {
		return (Index<AString, AVector<ACell>>) Index.EMPTY;
	}

	/**
	 * Sets a field in a hash, returning the updated hash value
	 */
	@SuppressWarnings("unchecked")
	public static Index<AString, AVector<ACell>> setField(ACell hashValue, String field, ACell value, CVMLong timestamp) {
		Index<AString, AVector<ACell>> index = (hashValue != null)
			? (Index<AString, AVector<ACell>>) hashValue
			: empty();
		AVector<ACell> fieldEntry = Vectors.of(value, timestamp);
		return index.assoc(Strings.create(field), fieldEntry);
	}

	/**
	 * Gets a field value from a hash, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public static ACell getField(ACell hashValue, String field) {
		if (hashValue == null) return null;
		Index<AString, AVector<ACell>> index = (Index<AString, AVector<ACell>>) hashValue;
		AVector<ACell> fieldEntry = index.get(Strings.create(field));
		if (fieldEntry == null) return null;
		return fieldEntry.get(FIELD_VALUE);
	}

	/**
	 * Deletes a field from a hash using a tombstone (null value with timestamp)
	 */
	@SuppressWarnings("unchecked")
	public static Index<AString, AVector<ACell>> deleteField(ACell hashValue, String field, CVMLong timestamp) {
		if (hashValue == null) return empty();
		Index<AString, AVector<ACell>> index = (Index<AString, AVector<ACell>>) hashValue;
		AVector<ACell> tombstone = Vectors.of(null, timestamp);
		return index.assoc(Strings.create(field), tombstone);
	}

	/**
	 * Checks if a field exists (non-tombstoned) in a hash
	 */
	@SuppressWarnings("unchecked")
	public static boolean fieldExists(ACell hashValue, String field) {
		if (hashValue == null) return false;
		Index<AString, AVector<ACell>> index = (Index<AString, AVector<ACell>>) hashValue;
		AVector<ACell> fieldEntry = index.get(Strings.create(field));
		if (fieldEntry == null) return false;
		return fieldEntry.get(FIELD_VALUE) != null;
	}

	/**
	 * Returns the count of live (non-tombstoned) fields
	 */
	@SuppressWarnings("unchecked")
	public static long fieldCount(ACell hashValue) {
		if (hashValue == null) return 0;
		Index<AString, AVector<ACell>> index = (Index<AString, AVector<ACell>>) hashValue;
		long count = 0;
		for (var e : index.entrySet()) {
			if (e.getValue().get(FIELD_VALUE) != null) count++;
		}
		return count;
	}

	/**
	 * Merges two hash values using per-field LWW
	 */
	@SuppressWarnings("unchecked")
	public static ACell merge(ACell a, ACell b) {
		if (a == null) return b;
		if (b == null) return a;
		Index<AString, AVector<ACell>> ia = (Index<AString, AVector<ACell>>) a;
		Index<AString, AVector<ACell>> ib = (Index<AString, AVector<ACell>>) b;

		MergeFunction<AVector<ACell>> fieldMerge = (fa, fb) -> {
			if (fa == null) return fb;
			if (fb == null) return fa;
			long tA = ((CVMLong) fa.get(FIELD_TIMESTAMP)).longValue();
			long tB = ((CVMLong) fb.get(FIELD_TIMESTAMP)).longValue();
			return tA >= tB ? fa : fb;
		};

		return ia.mergeDifferences(ib, fieldMerge);
	}
}
