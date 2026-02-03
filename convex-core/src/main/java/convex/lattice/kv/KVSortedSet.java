package convex.lattice.kv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;

/**
 * Helper for Redis Sorted Set operations.
 *
 * Sorted set value is stored as Index&lt;ABlob, AVector&lt;ACell&gt;&gt; where each member
 * (keyed by hash) maps to [member, score, addTime, removeTime].
 *
 * Merge takes max timestamps per member. Score comes from the entry with the latest addTime.
 */
public class KVSortedSet {

	public static final int POS_MEMBER = 0;
	public static final int POS_SCORE = 1;
	public static final int POS_ADD_TIME = 2;
	public static final int POS_REMOVE_TIME = 3;

	@SuppressWarnings("unchecked")
	public static Index<ABlob, AVector<ACell>> empty() {
		return (Index<ABlob, AVector<ACell>>) Index.EMPTY;
	}

	/**
	 * Adds a member with a score
	 */
	@SuppressWarnings("unchecked")
	public static Index<ABlob, AVector<ACell>> addMember(ACell zsetValue, ACell member, CVMDouble score, CVMLong timestamp) {
		Index<ABlob, AVector<ACell>> index = (zsetValue != null)
			? (Index<ABlob, AVector<ACell>>) zsetValue
			: empty();
		ABlob key = Hash.get(member);
		AVector<ACell> existing = index.get(key);
		CVMLong removeTime = CVMLong.ZERO;
		if (existing != null) {
			removeTime = (CVMLong) existing.get(POS_REMOVE_TIME);
		}
		AVector<ACell> entry = Vectors.of(member, score, timestamp, removeTime);
		return index.assoc(key, entry);
	}

	/**
	 * Removes a member
	 */
	@SuppressWarnings("unchecked")
	public static Index<ABlob, AVector<ACell>> removeMember(ACell zsetValue, ACell member, CVMLong timestamp) {
		Index<ABlob, AVector<ACell>> index = (zsetValue != null)
			? (Index<ABlob, AVector<ACell>>) zsetValue
			: empty();
		ABlob key = Hash.get(member);
		AVector<ACell> existing = index.get(key);
		if (existing == null) return index;
		CVMLong addTime = (CVMLong) existing.get(POS_ADD_TIME);
		CVMDouble score = (CVMDouble) existing.get(POS_SCORE);
		AVector<ACell> entry = Vectors.of(member, score, addTime, timestamp);
		return index.assoc(key, entry);
	}

	/**
	 * Gets the score of a member, or null if not present
	 */
	@SuppressWarnings("unchecked")
	public static CVMDouble getScore(ACell zsetValue, ACell member) {
		if (zsetValue == null) return null;
		Index<ABlob, AVector<ACell>> index = (Index<ABlob, AVector<ACell>>) zsetValue;
		ABlob key = Hash.get(member);
		AVector<ACell> entry = index.get(key);
		if (entry == null) return null;
		long addTime = ((CVMLong) entry.get(POS_ADD_TIME)).longValue();
		long removeTime = ((CVMLong) entry.get(POS_REMOVE_TIME)).longValue();
		if (addTime <= removeTime) return null;
		return (CVMDouble) entry.get(POS_SCORE);
	}

	/**
	 * Returns live members sorted by score, within the given index range.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> range(ACell zsetValue, long start, long stop) {
		if (zsetValue == null) return Vectors.empty();
		Index<ABlob, AVector<ACell>> index = (Index<ABlob, AVector<ACell>>) zsetValue;

		// Collect live members
		List<AVector<ACell>> live = new ArrayList<>();
		for (var e : index.entrySet()) {
			AVector<ACell> entry = e.getValue();
			long addTime = ((CVMLong) entry.get(POS_ADD_TIME)).longValue();
			long removeTime = ((CVMLong) entry.get(POS_REMOVE_TIME)).longValue();
			if (addTime > removeTime) live.add(entry);
		}

		// Sort by score
		live.sort(Comparator.comparingDouble(e -> ((CVMDouble) e.get(POS_SCORE)).doubleValue()));

		// Handle negative indices
		int size = live.size();
		if (start < 0) start = Math.max(0, size + start);
		if (stop < 0) stop = size + stop;
		stop = Math.min(stop, size - 1);

		AVector<ACell> result = Vectors.empty();
		for (long i = start; i <= stop && i < size; i++) {
			result = result.append(live.get((int) i).get(POS_MEMBER));
		}
		return result;
	}

	/**
	 * Returns the count of live members
	 */
	@SuppressWarnings("unchecked")
	public static long memberCount(ACell zsetValue) {
		if (zsetValue == null) return 0;
		Index<ABlob, AVector<ACell>> index = (Index<ABlob, AVector<ACell>>) zsetValue;
		long count = 0;
		for (var e : index.entrySet()) {
			AVector<ACell> entry = e.getValue();
			long addTime = ((CVMLong) entry.get(POS_ADD_TIME)).longValue();
			long removeTime = ((CVMLong) entry.get(POS_REMOVE_TIME)).longValue();
			if (addTime > removeTime) count++;
		}
		return count;
	}

	/**
	 * Merges two sorted set values. Max timestamps; score from latest addTime.
	 */
	@SuppressWarnings("unchecked")
	public static ACell merge(ACell a, ACell b) {
		if (a == null) return b;
		if (b == null) return a;
		Index<ABlob, AVector<ACell>> ia = (Index<ABlob, AVector<ACell>>) a;
		Index<ABlob, AVector<ACell>> ib = (Index<ABlob, AVector<ACell>>) b;

		MergeFunction<AVector<ACell>> memberMerge = (ma, mb) -> {
			if (ma == null) return mb;
			if (mb == null) return ma;
			long addA = ((CVMLong) ma.get(POS_ADD_TIME)).longValue();
			long addB = ((CVMLong) mb.get(POS_ADD_TIME)).longValue();
			long remA = ((CVMLong) ma.get(POS_REMOVE_TIME)).longValue();
			long remB = ((CVMLong) mb.get(POS_REMOVE_TIME)).longValue();
			// Score from the entry with latest addTime
			CVMDouble score = addA >= addB
				? (CVMDouble) ma.get(POS_SCORE)
				: (CVMDouble) mb.get(POS_SCORE);
			ACell member = addA >= addB ? ma.get(POS_MEMBER) : mb.get(POS_MEMBER);
			return Vectors.of(member, score, CVMLong.create(Math.max(addA, addB)), CVMLong.create(Math.max(remA, remB)));
		};

		return ia.mergeDifferences(ib, memberMerge);
	}
}
