package convex.core.lang;

import java.util.HashMap;
import java.util.Set;

import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.Vectors;

public class RecordFormat {

	protected final long count;
	protected final AVector<Keyword> keys;
	protected final HashMap<Keyword, Long> indexes = new HashMap<>();
	protected final Set<Keyword> keySet;

	private RecordFormat(AVector<Keyword> keys) {
		this.keys = keys;
		count = keys.count();
		for (int i = 0; i < count; i++) {
			indexes.put(keys.get(i), Long.valueOf(i));
		}
		keySet = Sets.create(keys);
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

	public Set<Keyword> keySet() {
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
	public Keyword getKey(int i) {
		return keys.get(i);
	}
}
