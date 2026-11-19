package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.type.AType;

/**
 * The standard lazy cursor: one key of the data structure its container cursor
 * holds, with a default value. The location is constructed in the container's
 * current value; where the container is absent, that is the container cursor's own
 * default, such as another lazy cursor's or {@link AForkableCursor#withDefault}'s,
 * and with no default there the location cannot be constructed. A chain of lazy
 * cursors with a default at each level therefore constructs a whole path in one
 * atomic write.
 *
 * <p>Where the default is a data structure, a stored value of another CVM type is
 * rejected. A container that is not a data structure is rejected, and a key the
 * container cannot hold is refused.</p>
 *
 * @param <V> Value type at the location
 */
public final class LazyCursor<V extends ACell> extends ALazyCursor<V> {

	private final ACell key;
	private final AType kind;

	private LazyCursor(ACursor<? extends ACell> container,ACell key,V defaultValue) {
		super(container,defaultValue);
		this.key=key;
		this.kind=(defaultValue instanceof ADataStructure)?defaultValue.getType():null;
	}

	/**
	 * Creates a lazy cursor that reads as absent while its location does not exist.
	 *
	 * @param <V> Value type at the location
	 * @param container Cursor holding the data structure that holds the location
	 * @param key Key of the location
	 * @return Lazy cursor at the key
	 */
	public static <V extends ACell> LazyCursor<V> create(ACursor<? extends ACell> container,ACell key) {
		return new LazyCursor<>(container,key,null);
	}

	/**
	 * Creates a lazy cursor that reads as a default value while its location does not exist.
	 *
	 * @param <V> Value type at the location
	 * @param container Cursor holding the data structure that holds the location
	 * @param key Key of the location
	 * @param defaultValue Value read while the location does not exist, or null to read as absent
	 * @return Lazy cursor at the key
	 */
	public static <V extends ACell> LazyCursor<V> create(ACursor<? extends ACell> container,ACell key,V defaultValue) {
		return new LazyCursor<>(container,key,defaultValue);
	}

	@Override protected Object locate(ACell stored) {
		if (stored==null) return MISSING;
		ADataStructure<?> structure=structure(stored);
		if (!structure.containsKey(key)) return MISSING;
		ACell value=structure.get(key);
		if (kind!=null && value!=null && !kind.check(value)) {
			throw new IllegalStateException("Lazy location holds a value of another type than its default");
		}
		return value;
	}

	@Override protected ACell construct(ACell stored,V value) {
		if (stored==null) throw new IllegalStateException("No container to construct a lazy location in");
		ACell updated=structure(stored).assoc(key,value);
		if (updated==null) throw new IllegalArgumentException("Key is not supported by the container");
		return updated;
	}

	private static ADataStructure<?> structure(ACell stored) {
		if (!(stored instanceof ADataStructure<?> structure)) {
			throw new IllegalStateException("Lazy cursor requires a data structure as its container");
		}
		return structure;
	}
}
