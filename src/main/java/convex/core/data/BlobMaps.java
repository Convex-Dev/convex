package convex.core.data;

import convex.core.lang.RT;
import convex.core.util.Utils;

public class BlobMaps {

	/**
	 * Returns the empty BlobMap. Guaranteed singleton.
	 * 
	 * @param <R>
	 * @param <K>
	 * @param <V>
	 * @return The empty BlobMap
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ABlobMap<K, V>, K extends ABlob, V> R empty() {
		return (R) BlobMap.EMPTY;
	}

	@SuppressWarnings("unchecked")
	public static <R extends ABlobMap<K, V>, K extends ABlob, V> R create(K k, V v) {
		return (R) BlobMap.create(k, v);
	}

	@SuppressWarnings("unchecked")
	public static <R extends ABlobMap<K, V>, K extends ABlob, V> R of(Object... kvs) {
		int n = kvs.length;
		if (Utils.isOdd(n)) throw new IllegalArgumentException("Even number of key + values required");
		BlobMap<K, V> result = empty();
		for (int i = 0; i < n; i += 2) {
			V value=RT.cvm(kvs[i + 1]);
			result = result.assoc((K) kvs[i], value);
		}

		return (R) result;
	}
}
