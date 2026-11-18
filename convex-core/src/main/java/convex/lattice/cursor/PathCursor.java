package convex.lattice.cursor;

import java.util.Arrays;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.PathWriteException;

/**
 * A cursor that navigates to a path within a base cursor's value.
 *
 * <p>Required reads throw for absence, including unresolvable paths; a stored nil remains
 * nil. Optional reads handle absence explicitly. Changed writes rebuild existing ancestors.
 * Any write where the entry does not exist, or to a container that cannot hold its key,
 * throws {@link convex.lattice.PathWriteException} at that key.
 * Update functions receive the actual
 * value, including null; components supply any defaults or construction logic.
 * Atomic operations, unchanged-write handling and claims come from
 * {@link ATransformCursor}.</p>
 *
 * @param <V> Type of cursor values at the path location
 */
public class PathCursor<V extends ACell> extends ATransformCursor<V,ACell> {

	private final ACell[] path;

	public PathCursor(ACursor<?> base, ACell... path) {
		this(base,path,0,path.length);
	}

	/** Creates a view owning a copy of the keys in {@code [start,end)}. */
	@SuppressWarnings("unchecked")
	public PathCursor(ACursor<?> base,ACell[] path,int start,int end) {
		super((ACursor<ACell>)base);
		Objects.checkFromToIndex(start,end,path.length);
		this.path=Arrays.copyOfRange(path,start,end);
	}

	public static <T extends ACell,V extends ACell> PathCursor<T> create(ACursor<V> base,ACell[] path) {
		return new PathCursor<>(base,path);
	}

	@Override protected Object viewValue(ACell stored) {
		return CursorValues.at(stored,path);
	}

	/** Write operations retain the existing nil input for absent entries. */
	@Override protected V view(ACell stored) {
		return RT.getIn(stored,path);
	}

	/** A path writes only where its entry exists; writing back what is there finds a container that cannot hold it. */
	@Override protected void requireWritable(ACell stored) {
		write(stored,view(stored));
		if (viewValue(stored)==MISSING) throw new PathWriteException("Cannot write an entry that does not exist",path,path.length-1);
	}

	/** Associates through existing containers, preserving sibling values. */
	@Override protected ACell write(ACell current,V value) {
		return assocExisting(current,value,path);
	}

	/**
	 * Writes a value at a path through existing containers, creating none. An empty
	 * path returns the value; a write that changes nothing returns {@code data} itself.
	 *
	 * @param <T> Return type
	 * @param data Value holding the path
	 * @param value Value to write at the end of the path
	 * @param keys Path of keys to navigate
	 * @return Updated value
	 * @throws PathWriteException If a container is absent, is not a data structure or
	 *         cannot hold its key
	 * @throws IllegalArgumentException If a container is a signed envelope
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T assocExisting(ACell data,ACell value,ACell... keys) {
		return (T)assocExisting(data,value,keys,0);
	}

	/**
	 * Descends to depth {@code i}, then rebuilds on the way back up. Each level's
	 * {@link RT#assoc} returns the existing structure when the child below it did not
	 * change, so a no-op write allocates nothing.
	 */
	private static ACell assocExisting(ACell data,ACell value,ACell[] keys,int i) {
		if (i>=keys.length) return value;
		if (data==null) {
			if (i==0) throw new PathWriteException(
				"Cannot write through an absent value: no empty value is known for this position",keys,i);
			throw new PathWriteException(
				"Cannot write through an absent container: its type is unknown here; supply component construction logic",keys,i);
		}
		if (data instanceof SignedData) {
			// A signed envelope is a record, so it would otherwise degrade to a plain map
			// with the signature lost. Writes below an owner's slot go through the
			// SignedCursor the owning component wraps around it, never through here.
			throw new IllegalArgumentException(
				"Cannot write through a signed envelope at depth "+i+": wrap the owner's slot with SignedCursor.create");
		}
		if (!(data instanceof ADataStructure<?> struct)) {
			throw new PathWriteException(
				"Cannot write a key into "+Utils.getClassName(data)+", which is not a data structure",keys,i);
		}
		ACell child=struct.get(keys[i]);
		ACell updated=RT.assoc(struct,keys[i],assocExisting(child,value,keys,i+1));
		// A container refuses a key it cannot hold with null, which would otherwise
		// be written in its place one level up
		if (updated==null) throw new PathWriteException(
			"Cannot write the key: "+Utils.getClassName(struct)+" cannot hold it",keys,i);
		return updated;
	}
}
