package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ProjectionCursor;

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
	public M get() { return requireMap(values().get()); }
	public V get(K key) { return entry(values().get(),key); }
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
		if (map.containsKey(key) && Utils.equals(map.get(key),value)) return map;
		AMap<K,V> updated=map.assoc(key,value);
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
	 * A changed write uses {@link #createContainer()} only when the containing map is absent.
	 * Setting an absent entry to null is a no-op; use {@link #put} to insert a nil value.
	 */
	public AForkableCursor<V> path(K key) {
		return ProjectionCursor.create(values(),stored -> entry(stored,key),
			(stored,value) -> associate(stored,key,value));
	}
}
