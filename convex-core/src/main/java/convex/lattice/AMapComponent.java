package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ProjectionCursor;

/**
 * Atomic map operations, without replication, signing or timestamp policy.
 * Concrete subclasses supply only the container representation. Nil reads as
 * empty; reads and no-op removals leave absent storage absent. A child path
 * creates the containing map only when changed, preserving sibling entries.
 *
 * @param <K> Key type
 * @param <V> Entry value type
 * @param <M> Map representation
 */
public abstract class AMapComponent<K extends ACell,V extends ACell,M extends AMap<K,V>>
		extends ALatticeComponent<M> {

	protected AMapComponent(AForkableCursor<M> cursor) { super(cursor); }

	/** Returns the map or an empty value for nil; rejects incompatible storage. */
	protected abstract M requireMap(ACell value);

	// Inspect foreign storage before Java's generic cast to the declared map type.
	@SuppressWarnings("unchecked")
	private AForkableCursor<ACell> values() {
		return (AForkableCursor<ACell>)(AForkableCursor<?>)cursor();
	}

	/** Returns an immutable snapshot, with nil represented as an empty map. */
	public M get() { return requireMap(values().get()); }
	public V get(K key) { return get().get(key); }
	public boolean containsKey(K key) { return get().containsKey(key); }
	public long size() { return get().count(); }
	public boolean isEmpty() { return get().isEmpty(); }

	/** Associates a value atomically, returning the previous value. Nil is a stored value. */
	public V put(K key,V value) {
		ACell before=values().getAndUpdate(stored -> {
			M map=requireMap(stored);
			if (map.containsKey(key) && Utils.equals(map.get(key),value)) return stored;
			AMap<K,V> updated=map.assoc(key,value);
			if (updated==null) throw new IllegalArgumentException("Key is not supported by this map");
			return updated;
		});
		return requireMap(before).get(key);
	}

	/** Removes a key, returning its previous value; absent storage stays absent. */
	public V remove(K key) {
		ACell before=values().getAndUpdate(stored -> {
			M map=requireMap(stored);
			return map.containsKey(key)?map.dissoc(key):stored;
		});
		return requireMap(before).get(key);
	}

	/** Empties an existing map without materialising absent storage. */
	public void clear() {
		values().update(map -> map==null || requireMap(map).isEmpty()?map:requireMap(map).empty());
	}

	/** A shared entry view, suitable for binding a domain child component. */
	public AForkableCursor<V> path(K key) {
		// The same shape/default rule applies to direct edits and child views.
		AForkableCursor<M> map=ProjectionCursor.create(values(),this::requireMap,
			(stored,updated) -> { requireMap(stored); return updated; });
		return map.path(key);
	}
}
