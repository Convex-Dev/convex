package convex.lattice.kv;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.MergeFunction;

/**
 * Helper for Redis Set operations.
 *
 * Set value is stored as Index&lt;ABlob, AVector&lt;ACell&gt;&gt; where each member is keyed
 * by its hash and maps to [member, addTime, removeTime]. A member is present if addTime &gt; removeTime.
 */
public class KVSet {

	public static final int POS_MEMBER = 0;
	public static final int POS_ADD_TIME = 1;
	public static final int POS_REMOVE_TIME = 2;

	@SuppressWarnings("unchecked")
	public static Index<ABlob, AVector<ACell>> empty() {
		return (Index<ABlob, AVector<ACell>>) Index.EMPTY;
	}

	/**
	 * Adds a member to the set, returning the updated set value and the count of newly added members
	 */
	@SuppressWarnings("unchecked")
	public static Index<ABlob, AVector<ACell>> addMember(ACell setValue, ACell member, CVMLong timestamp) {
		Index<ABlob, AVector<ACell>> index = (setValue != null)
			? (Index<ABlob, AVector<ACell>>) setValue
			: empty();
		ABlob key = Hash.get(member);
		AVector<ACell> existing = index.get(key);
		CVMLong removeTime = CVMLong.ZERO;
		if (existing != null) {
			removeTime = (CVMLong) existing.get(POS_REMOVE_TIME);
		}
		AVector<ACell> entry = Vectors.of(member, timestamp, removeTime);
		return index.assoc(key, entry);
	}

	/**
	 * Removes a member from the set
	 */
	@SuppressWarnings("unchecked")
	public static Index<ABlob, AVector<ACell>> removeMember(ACell setValue, ACell member, CVMLong timestamp) {
		Index<ABlob, AVector<ACell>> index = (setValue != null)
			? (Index<ABlob, AVector<ACell>>) setValue
			: empty();
		ABlob key = Hash.get(member);
		AVector<ACell> existing = index.get(key);
		if (existing == null) return index;
		CVMLong addTime = (CVMLong) existing.get(POS_ADD_TIME);
		AVector<ACell> entry = Vectors.of(member, addTime, timestamp);
		return index.assoc(key, entry);
	}

	/**
	 * Checks if a member is present (addTime &gt; removeTime)
	 */
	@SuppressWarnings("unchecked")
	public static boolean isMember(ACell setValue, ACell member) {
		if (setValue == null) return false;
		Index<ABlob, AVector<ACell>> index = (Index<ABlob, AVector<ACell>>) setValue;
		ABlob key = Hash.get(member);
		AVector<ACell> entry = index.get(key);
		if (entry == null) return false;
		long addTime = ((CVMLong) entry.get(POS_ADD_TIME)).longValue();
		long removeTime = ((CVMLong) entry.get(POS_REMOVE_TIME)).longValue();
		return addTime > removeTime;
	}

	/**
	 * Returns the count of live members
	 */
	@SuppressWarnings("unchecked")
	public static long memberCount(ACell setValue) {
		if (setValue == null) return 0;
		Index<ABlob, AVector<ACell>> index = (Index<ABlob, AVector<ACell>>) setValue;
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
	 * Merges two set values. Takes max timestamps per member for both add and remove.
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
			long maxAdd = Math.max(addA, addB);
			long maxRem = Math.max(remA, remB);
			// Use the member value from the one with the latest add
			ACell member = addA >= addB ? ma.get(POS_MEMBER) : mb.get(POS_MEMBER);
			return Vectors.of(member, CVMLong.create(maxAdd), CVMLong.create(maxRem));
		};

		return ia.mergeDifferences(ib, memberMerge);
	}
}
