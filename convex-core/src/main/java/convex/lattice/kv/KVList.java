package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * Helper for Redis List operations.
 *
 * Lists use LWW (last-writer-wins) on the entire list value since ordered
 * sequences are not naturally CRDT-friendly. The list is stored as an AVector&lt;ACell&gt;.
 */
public class KVList {

	/**
	 * Creates an empty list value
	 */
	public static AVector<ACell> empty() {
		return Vectors.empty();
	}

	/**
	 * Prepends values to the list (LPUSH)
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> lpush(ACell listValue, ACell... values) {
		AVector<ACell> list = (listValue != null) ? (AVector<ACell>) listValue : empty();
		for (ACell v : values) {
			// Prepend by creating new vector with element at front
			AVector<ACell> newList = Vectors.of(v);
			for (long i = 0; i < list.count(); i++) {
				newList = newList.append(list.get(i));
			}
			list = newList;
		}
		return list;
	}

	/**
	 * Appends values to the list (RPUSH)
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> rpush(ACell listValue, ACell... values) {
		AVector<ACell> list = (listValue != null) ? (AVector<ACell>) listValue : empty();
		for (ACell v : values) {
			list = list.append(v);
		}
		return list;
	}

	/**
	 * Returns the first element (LPOP), or null if empty
	 */
	@SuppressWarnings("unchecked")
	public static ACell lpop(ACell listValue) {
		if (listValue == null) return null;
		AVector<ACell> list = (AVector<ACell>) listValue;
		if (list.count() == 0) return null;
		return list.get(0);
	}

	/**
	 * Returns the list after removing the first element
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> afterLpop(ACell listValue) {
		if (listValue == null) return empty();
		AVector<ACell> list = (AVector<ACell>) listValue;
		if (list.count() == 0) return list;
		return list.subVector(1, list.count() - 1);
	}

	/**
	 * Returns the last element (RPOP), or null if empty
	 */
	@SuppressWarnings("unchecked")
	public static ACell rpop(ACell listValue) {
		if (listValue == null) return null;
		AVector<ACell> list = (AVector<ACell>) listValue;
		if (list.count() == 0) return null;
		return list.get(list.count() - 1);
	}

	/**
	 * Returns the list after removing the last element
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> afterRpop(ACell listValue) {
		if (listValue == null) return empty();
		AVector<ACell> list = (AVector<ACell>) listValue;
		if (list.count() <= 1) return empty();
		return list.subVector(0, list.count() - 1);
	}

	/**
	 * Returns a subrange of the list (LRANGE)
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> lrange(ACell listValue, long start, long stop) {
		if (listValue == null) return empty();
		AVector<ACell> list = (AVector<ACell>) listValue;
		long size = list.count();
		if (start < 0) start = Math.max(0, size + start);
		if (stop < 0) stop = size + stop;
		stop = Math.min(stop, size - 1);
		if (start > stop || start >= size) return empty();
		return list.subVector(start, stop - start + 1);
	}

	/**
	 * Returns the list length
	 */
	@SuppressWarnings("unchecked")
	public static long length(ACell listValue) {
		if (listValue == null) return 0;
		return ((AVector<ACell>) listValue).count();
	}
}
