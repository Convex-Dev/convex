package convex.core.data;

import java.util.HashMap;

import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;

/**
 * Utility class for map functions
 *
 */
public class Maps {
	private static final AMap<?, ?> EMPTY_MAP = Cells.intern(MapLeaf.emptyMap());
	
	public static final Ref<AMap<?, ?>> EMPTY_REF = EMPTY_MAP.getRef();

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell, R extends AHashMap<K, V>> R empty() {
		return (R) EMPTY_MAP;
	}

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell, R extends AMap<K, V>> Ref<R> emptyRef() {
		return (Ref<R>) EMPTY_REF;
	}

	public static <K extends ACell, V extends ACell> MapLeaf<K, V> create(K k, V v) {
		return MapLeaf.create(MapEntry.create(k, v));
	}

	/**
	 * Constructs a map with the given keys and values. If keys are repeated, later keys will
	 * overwrite earlier ones. Performs conversion to CVM types.
	 * @param <R> Map type
	 * @param <K> Key type
	 * @param <V> Value type
	 * @param keysAndValues Keys and values to include
	 * @return Map with given keys and values
	 */
	@SuppressWarnings("unchecked")
	public static <R extends AHashMap<K, V>, K extends ACell, V extends ACell> R of(Object... keysAndValues) {
		int n = keysAndValues.length >> 1;
		if (keysAndValues.length != n * 2)
			throw new IllegalArgumentException("Even number of values need for key-value pairs");

		AMap<K, V> result = Maps.empty();
		for (int i = 0; i < n; i++) {
			K key = (K) RT.cvm(keysAndValues[i * 2]);
			V value = (V) RT.cvm(keysAndValues[i * 2 + 1]);
			result = result.assoc(key, value);
		}
		return (R) result;
	}
	
	/**
	 * Constructs a map with the given keys and values. If keys are repeated, later keys will
	 * overwrite earlier ones. Performs conversion to CVM types.
	 * @param <R> Map type
	 * @param <K> Key type
	 * @param <V> Value type
	 * @param keysAndValues Keys and values to include
	 * @return Map with given keys and values
	 */
	@SuppressWarnings("unchecked")
	public static <R extends AHashMap<K, V>, K extends ACell, V extends ACell> R create(ACell[] keysAndValues) {
		int n = keysAndValues.length >> 1;
		if (keysAndValues.length != n * 2)
			throw new IllegalArgumentException("Even number of values need for key-value pairs");

		AMap<K, V> result = Maps.empty();
		for (int i = 0; i < n; i++) {
			K key = (K) keysAndValues[i * 2];
			V value = (V) keysAndValues[i * 2 + 1];
			result = result.assoc(key, value);
		}
		return (R) result;
	}

	@SuppressWarnings("unchecked")
	public static <K, V> HashMap<K, V> hashMapOf(Object... keysAndValues) {
		int n = keysAndValues.length >> 1;
		HashMap<K, V> result = new HashMap<>(n);
		if (keysAndValues.length != n * 2)
			throw new IllegalArgumentException("Even number of values need for key-value pairs");
		for (int i = 0; i < n; i++) {
			K key = (K) keysAndValues[i * 2];
			V value = (V) keysAndValues[i * 2 + 1];
			result.put(key, value);
		}
		return result;
	}

	/**
	 * Create a map with a collection of entries.
	 * 
	 * @param <K> Key type
	 * @param <V> Value type
	 * @param entries Entries to include
	 * @return AHashMap instance
	 */
	public static <K extends ACell, V extends ACell> AHashMap<K, V> create(java.util.List<MapEntry<K, V>> entries) {
		return createWithShift(0, entries);
	}

	/**
	 * Create a hashmap with the correct shift and given entries.
	 * 
	 * @param <K>     Key type
	 * @param <V>     Value type
	 * @param shift Shift level of map
	 * @param entries Entries to include
	 * @return AHashMap instance
	 */
	public static <K extends ACell, V extends ACell> AHashMap<K, V> createWithShift(int shift, java.util.List<MapEntry<K, V>> entries) {
		int n = entries.size();
		if (n == 0) return empty();
		AHashMap<K, V> result = Maps.empty();
		for (int i=0; i<n; i++) {
			AVector<?> v=entries.get(i);
			@SuppressWarnings("unchecked")
			MapEntry<K,V> e=MapEntry.convertOrNull(v); // Ensure a Map entry
			result = result.assocEntry(e);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell, R extends AMap<K, V>> R coerce(AMap<?, ?> m) {
		return (R) m;
	}
	
	/**
	 * Read a Hashmap from a Blob. Assumes tag byte already read and at position specified
	 * @param <K> Key type
	 * @param <V> Value type
	 * @param b ByteBuffer to read from
	 * @param pos Position to read from Blob (tag location)
	 * @return Map instance
	 * @throws BadFormatException If encoding is invalid
	 */
	public static <K extends ACell, V extends ACell> AHashMap<K, V> read(Blob b, int pos) throws BadFormatException {
		// A hashmap always starts with a VLQ count after the tag
		// We use this to distinguish the type of Map cell
		long count = Format.readVLQCount(b,pos+1);
		
		if (count==0) return empty();
		if (count <= MapLeaf.MAX_ENTRIES) {
			if (count < 0) throw new BadFormatException("Overflowed count of map elements!");
			return MapLeaf.read(b, pos, count);
		} else {
			return MapTree.read(b, pos, count);
		}
	}
	
	public static int MAX_ENCODING_SIZE = Math.max(MapTree.MAX_ENCODING_LENGTH, MapLeaf.MAX_ENCODING_LENGTH);

	public static <K extends ACell, V extends ACell> Hash getFirstHash(AHashMap<K, V> map) {
		return map.getFirstHash();
	}




}
