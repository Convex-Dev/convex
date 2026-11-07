package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ATransformCursor;

/**
 * Atomic map operations, without replication, signing or timestamp policy.
 * Missing storage reads as null. Subclasses choose the container representation
 * and creation policy. Reads and no-op removals leave absent storage absent.
 * Direct puts and child edits use the same creation policy, preserving siblings.
 *
 * @param <K> Key type
 * @param <V> Entry value type
 * @param <M> Map representation
 */
public abstract class AMapComponent<K extends ACell,V extends ACell,M extends AMap<K,V>>
		extends ALatticeComponent<M> {

	protected AMapComponent(AForkableCursor<M> cursor) { super(cursor); }

	/** Returns the map, including null for absent storage; rejects incompatible storage. */
	protected abstract M requireMap(ACell value);

	/**
	 * Creates a container for a write to absent storage. Return a non-null container
	 * (which may contain initial entries), or throw to forbid creation. This hook
	 * must be pure: atomic updates may retry it. Reads, removals and clear never
	 * call it, and incompatible existing storage is rejected before creation.
	 */
	protected abstract M createContainer();

	// Inspect foreign storage before Java's generic cast to the declared map type.
	@SuppressWarnings("unchecked")
	private AForkableCursor<ACell> values() {
		return (AForkableCursor<ACell>)(AForkableCursor<?>)cursor();
	}

	/** Returns an immutable snapshot, or null for absent storage. */
	public M get() { return requireMap(values().getOrDefault(null)); }
	public V get(K key) { return entry(values().getOrDefault(null),key); }
	public boolean containsKey(K key) { M map=get(); return map!=null && map.containsKey(key); }
	public long size() { M map=get(); return map==null?0:map.count(); }
	public boolean isEmpty() { return size()==0; }

	private V entry(ACell stored,K key) {
		M map=requireMap(stored);
		return map==null?null:map.get(key);
	}

	private AMap<K,V> associate(ACell stored,K key,V value) {
		M map=requireMap(stored);
		if (map==null) map=createContainer();
		// Keeping an equal current value leaves the map unchanged
		AMap<K,V> updated=map.update(key,(current,v) -> Utils.equals(current,v)?current:v,value);
		if (updated==null) throw new IllegalArgumentException("Key is not supported by this map");
		return updated;
	}

	/** Associates a value atomically, returning the previous value. Nil is a stored value. */
	public V put(K key,V value) {
		ACell before=values().getAndUpdate(stored -> associate(stored,key,value));
		return entry(before,key);
	}

	/** Removes a key, returning its previous value; absent storage stays absent. */
	public V remove(K key) {
		ACell before=values().getAndUpdate(stored -> {
			M map=requireMap(stored);
			return map!=null && map.containsKey(key)?map.dissoc(key):stored;
		});
		return entry(before,key);
	}

	/** Empties an existing map without materialising absent storage. */
	public void clear() {
		values().update(map -> map==null || requireMap(map).isEmpty()?map:requireMap(map).empty());
	}

	/**
	 * A shared entry view, suitable for binding a domain child component.
	 * A missing key is absent, as on a path: a required read throws, and a stored nil
	 * is present. A changed write uses {@link #createContainer()} only when the
	 * containing map is absent.
	 * Setting an absent entry to null is a no-op; use {@link #put} to insert a nil value.
	 * The caller declares the entry type for a heterogeneous map; this does not
	 * validate the entry's representation. The child component owns that check.
	 * @param <T> Entry type expected by the child
	 * @param key Entry key
	 * @return Shared entry cursor
	 */
	@SuppressWarnings("unchecked")
	public <T extends V> AForkableCursor<T> path(K key) {
		return (AForkableCursor<T>)new Entry(values(),key);
	}

	/** One entry, read through the map's storage check and written with its creation policy. */
	private final class Entry extends ATransformCursor<V,ACell> {
		private final K key;

		Entry(AForkableCursor<ACell> values,K key) {
			super(values);
			this.key=key;
		}

		@Override protected V view(ACell stored) { return entry(stored,key); }

		@Override protected Object viewValue(ACell stored) {
			M map=requireMap(stored);
			if (map==null) return MISSING;
			V value=map.get(key);
			return value==null && !map.containsKey(key)?MISSING:value;
		}

		@Override protected ACell write(ACell current,V value) { return associate(current,key,value); }
	}
}
