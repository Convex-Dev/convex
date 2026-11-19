package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ALazyCursor;

/**
 * Atomic map operations for components to extend, without replication, signing or
 * timestamp policy. The operations are protected, so a subclass chooses which to
 * expose; {@link MapComponent} and {@link IndexComponent} expose them all.
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
	 * call it, and incompatible existing storage is rejected before creation. A write
	 * through an {@link #entry} view calls it whenever the map is absent, including a
	 * write that changes nothing, and is refused if it throws.
	 */
	protected abstract M createContainer();

	// Inspect foreign storage before Java's generic cast to the declared map type.
	@SuppressWarnings("unchecked")
	private AForkableCursor<ACell> values() {
		return (AForkableCursor<ACell>)(AForkableCursor<?>)cursor();
	}

	/** Returns an immutable snapshot, or null for absent storage. */
	protected M get() { return requireMap(values().getOrDefault(null)); }
	protected V get(K key) { return valueAt(values().getOrDefault(null),key); }
	protected boolean containsKey(K key) { M map=get(); return map!=null && map.containsKey(key); }
	protected long size() { M map=get(); return map==null?0:map.count(); }
	protected boolean isEmpty() { return size()==0; }

	private V valueAt(ACell stored,K key) {
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
	protected V put(K key,V value) {
		ACell before=values().getAndUpdate(stored -> associate(stored,key,value));
		return valueAt(before,key);
	}

	/** Removes a key, returning its previous value; absent storage stays absent. */
	protected V remove(K key) {
		ACell before=values().getAndUpdate(stored -> {
			M map=requireMap(stored);
			return map!=null && map.containsKey(key)?map.dissoc(key):stored;
		});
		return valueAt(before,key);
	}

	/** Empties an existing map without materialising absent storage. */
	protected void clear() {
		values().update(map -> map==null || requireMap(map).isEmpty()?map:requireMap(map).empty());
	}

	/**
	 * A shared view of one entry, for binding a child component within this
	 * component's own value. A missing key is absent, as on a path: a required read
	 * throws, and a stored nil is present. A changed write inserts the key, using
	 * {@link #createContainer()} when the containing map is absent. Where the map is
	 * absent and {@link #createContainer()} throws, every write is refused with its
	 * exception, whatever the value. A write that changes nothing creates nothing;
	 * setting an absent entry to null is such a write, and {@link #put} inserts a nil value.
	 * The caller declares the entry type for a heterogeneous map; this does not
	 * validate the entry's representation. The child component owns that check.
	 * @param <T> Entry type expected by the child
	 * @param key Entry key
	 * @return Shared entry cursor
	 */
	@SuppressWarnings("unchecked")
	protected <T extends V> AForkableCursor<T> entry(K key) {
		return (AForkableCursor<T>)new Entry(values(),key);
	}

	/** One entry, read through the map's storage check and constructed with its creation policy. */
	private final class Entry extends ALazyCursor<V> {
		private final K key;

		Entry(AForkableCursor<ACell> values,K key) {
			super(values,null);
			this.key=key;
		}

		@Override protected Object locate(ACell stored) {
			M map=requireMap(stored);
			if (map==null) return MISSING;
			V value=map.get(key);
			return value==null && !map.containsKey(key)?MISSING:value;
		}

		@Override protected ACell construct(ACell stored,V value) { return associate(stored,key,value); }
	}
}
