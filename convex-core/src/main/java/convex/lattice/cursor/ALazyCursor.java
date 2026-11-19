package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * A cursor at a location that its first changed write constructs, with the
 * construction supplied by whoever makes it. While the location does not exist it
 * reads as its default value, or as absent where the default is null, including
 * where its source is absent; a stored nil is present. A changed write constructs
 * the location and anything missing on the way to it. A write that leaves the value
 * unchanged constructs nothing. Where the location cannot be constructed, every
 * write is refused, whatever its value. Reads never construct.
 *
 * <p>A component that supports lazily created children binds each child to one of
 * these within its own value, and keeps it internal.</p>
 *
 * @param <V> Value type at the location
 */
public abstract class ALazyCursor<V extends ACell> extends ATransformCursor<V,ACell> {

	private final V defaultValue;

	/**
	 * @param source Cursor holding the location
	 * @param defaultValue Value read while the location does not exist, or null to read as absent
	 */
	@SuppressWarnings("unchecked")
	protected ALazyCursor(ACursor<? extends ACell> source,V defaultValue) {
		super((ACursor<ACell>)source);
		this.defaultValue=defaultValue;
	}

	/**
	 * Finds this location in a source value. Never constructs.
	 *
	 * @param stored Source value, or null where the source is absent
	 * @return The location's value, including nil, or {@link #MISSING} where it does not exist
	 * @throws IllegalStateException If the source value cannot hold this location
	 */
	protected abstract Object locate(ACell stored);

	/**
	 * Places a value at this location, constructing what is missing on the way to it.
	 * Must be pure: atomic updates may retry it.
	 *
	 * @param stored Source value, or null where the source is absent
	 * @param value Value for the location, including nil
	 * @return Source value holding the value at this location
	 * @throws RuntimeException Where the location cannot be constructed
	 */
	protected abstract ACell construct(ACell stored,V value);

	@SuppressWarnings("unchecked")
	@Override protected final V view(ACell stored) {
		Object found=locate(stored);
		return (found==MISSING)?defaultValue:(V)found;
	}

	@Override protected final Object viewValue(ACell stored) {
		Object found=locate(stored);
		if (found!=MISSING) return found;
		return (defaultValue==null)?MISSING:defaultValue;
	}

	/** An absent source is a location that does not exist, so it reads as the default. */
	@Override protected final Object readValue() {
		Object stored=base.readValue();
		return viewValue((stored==MISSING)?null:(ACell)stored);
	}

	/** A location that cannot be constructed leaves nowhere to write, whatever the value. */
	@Override protected final void requireWritable(ACell stored) {
		if (locate(stored)==MISSING) construct(stored,defaultValue);
	}

	@Override protected final ACell write(ACell current,V value) {
		return construct(current,value);
	}
}
