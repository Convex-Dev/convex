package convex.core.data;

import java.util.HashMap;

/**
 * Defines the format of a Record structure, as an ordered vector of keys.
 * 
 * Keys must be unique keywords in standard records
 */
public class RecordFormat {

	protected final long count;
	protected final AVector<Keyword> keys;
	protected final HashMap<Keyword, Long> indexes = new HashMap<>();
	protected final ASet<Keyword> keySet;

	private RecordFormat(AVector<Keyword> keys) {
		this.keys = keys;
		count = keys.count();
		keySet = Sets.create(keys);
		if (keySet.count()!=count) throw new IllegalArgumentException("Duplicate keys in: "+keys);
		
		for (int i = 0; i < count; i++) {
			indexes.put(keys.get(i), Long.valueOf(i));
		}
	}

	public long count() {
		return count;
	}

	public AVector<Keyword> getKeys() {
		return keys;
	}

	public boolean containsKey(Object key) {
		return indexes.containsKey(key);
	}

	public static RecordFormat of(Keyword... keys) {
		return new RecordFormat(Vectors.create(keys));
	}

	public ASet<Keyword> keySet() {
		return keySet;
	}

	public Long indexFor(Keyword key) {
		return indexes.get(key);
	}

	/**
	 * Gets the key at the specified index
	 * @param i Index of record key
	 * @return Keyword at the specified index
	 */
	public Keyword getKey(long i) {
		return keys.get(i);
	}
}
