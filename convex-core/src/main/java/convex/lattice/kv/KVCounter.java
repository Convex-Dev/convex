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
 * Helper for PN-Counter operations.
 *
 * Counter value is stored as Index&lt;AString, AVector&lt;ACell&gt;&gt; where each actor maps
 * to [positive, negative] counts. The displayed value is sum(positive) - sum(negative).
 *
 * Merge takes max per actor per column (PN-Counter CRDT).
 */
public class KVCounter {

	public static final int POS_POSITIVE = 0;
	public static final int POS_NEGATIVE = 1;

	@SuppressWarnings("unchecked")
	public static Index<AString, AVector<ACell>> empty() {
		return (Index<AString, AVector<ACell>>) Index.EMPTY;
	}

	/**
	 * Increments the counter for a given actor
	 */
	@SuppressWarnings("unchecked")
	public static Index<AString, AVector<ACell>> increment(ACell counterValue, String actorId, long amount) {
		Index<AString, AVector<ACell>> index = (counterValue != null)
			? (Index<AString, AVector<ACell>>) counterValue
			: empty();
		AString actor = Strings.create(actorId);
		AVector<ACell> existing = index.get(actor);
		long pos = 0, neg = 0;
		if (existing != null) {
			pos = ((CVMLong) existing.get(POS_POSITIVE)).longValue();
			neg = ((CVMLong) existing.get(POS_NEGATIVE)).longValue();
		}
		if (amount >= 0) {
			pos += amount;
		} else {
			neg += (-amount);
		}
		AVector<ACell> entry = Vectors.of(CVMLong.create(pos), CVMLong.create(neg));
		return index.assoc(actor, entry);
	}

	/**
	 * Gets the current counter value (sum of all positive - sum of all negative)
	 */
	@SuppressWarnings("unchecked")
	public static long getValue(ACell counterValue) {
		if (counterValue == null) return 0;
		Index<AString, AVector<ACell>> index = (Index<AString, AVector<ACell>>) counterValue;
		long total = 0;
		for (var e : index.entrySet()) {
			AVector<ACell> entry = e.getValue();
			total += ((CVMLong) entry.get(POS_POSITIVE)).longValue();
			total -= ((CVMLong) entry.get(POS_NEGATIVE)).longValue();
		}
		return total;
	}

	/**
	 * Merges two counter values. Takes max per actor per column.
	 */
	@SuppressWarnings("unchecked")
	public static ACell merge(ACell a, ACell b) {
		if (a == null) return b;
		if (b == null) return a;
		Index<AString, AVector<ACell>> ia = (Index<AString, AVector<ACell>>) a;
		Index<AString, AVector<ACell>> ib = (Index<AString, AVector<ACell>>) b;

		MergeFunction<AVector<ACell>> actorMerge = (ea, eb) -> {
			if (ea == null) return eb;
			if (eb == null) return ea;
			long posA = ((CVMLong) ea.get(POS_POSITIVE)).longValue();
			long posB = ((CVMLong) eb.get(POS_POSITIVE)).longValue();
			long negA = ((CVMLong) ea.get(POS_NEGATIVE)).longValue();
			long negB = ((CVMLong) eb.get(POS_NEGATIVE)).longValue();
			return Vectors.of(CVMLong.create(Math.max(posA, posB)), CVMLong.create(Math.max(negA, negB)));
		};

		return ia.mergeDifferences(ib, actorMerge);
	}
}
