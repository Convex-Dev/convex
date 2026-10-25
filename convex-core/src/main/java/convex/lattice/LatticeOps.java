package convex.lattice;

import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.generic.AKeyedLattice;

/**
 * Optional lattice-directed merge, construction and path operations, plus strict path updates.
 *
 * <p>Provides {@link #assocIn} as a replacement for {@link RT#assocIn} which knows
 * how to create a missing container: from the lattice of the position being
 * written through. Container types are never inferred from keys. Cursors use only
 * {@link #assocExisting}; components opt into construction via {@code assocIn}.</p>
 */
public class LatticeOps {

	/** Updates an existing path without creating any missing containers. */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T assocExisting(ACell data, ACell value, ACell... keys) {
		return (T) assocIn(data,value,null,keys,0);
	}

	/**
	 * Lattice-directed associative update at a nested path.
	 *
	 * <p>Rules for a missing container:</p>
	 * <ul>
	 *   <li>At the base, {@code lattice.zero()} supplies the correctly typed empty
	 *       container; a null zero is an error.</li>
	 *   <li>Below the base, a missing intermediate is an error,
	 *       because only the component owning that level knows its type. Use
	 *       component-supplied child views instead.</li>
	 *   <li>An existing container is used regardless of the supplied lattice.</li>
	 * </ul>
	 *
	 * @param <T> Return type
	 * @param a Root data structure (may be null if the lattice provides an empty value)
	 * @param value Value to set at the end of the path
	 * @param lattice Lattice of {@code a}'s position, or null for none
	 * @param keys Path of keys to navigate
	 * @return Updated root data structure
	 * @throws IllegalStateException if a missing container cannot be created
	 * @throws IllegalArgumentException if a non-null intermediate is not a data structure, or is a
	 *         signed envelope (a signature covers its value, so nothing may be written through it)
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T assocIn(ACell a, ACell value, ALattice<?> lattice, ACell... keys) {
		return (T) assocIn(a, value, lattice, keys, 0);
	}

	/**
	 * Recursive core: descends to depth {@code i}, then rebuilds on the way back up.
	 * Copy-on-change — each level's {@link RT#assoc} returns the existing structure
	 * unchanged when the child below it didn't change, so a no-op write returns the
	 * original {@code data} by reference and allocates nothing (no working arrays).
	 */
	private static ACell assocIn(ACell data, ACell value, ALattice<?> lattice, ACell[] keys, int i) {
		if (i >= keys.length) return value;

		if (data == null) {
			if (i == 0) {
				data = (lattice == null) ? null : lattice.zero();
				if (data == null) throw new IllegalStateException(
					"Cannot write through an absent value: no empty value is known for this position");
			} else {
				throw new IllegalStateException(
					"Cannot write through non-existent path at depth " + i
					+ ": the intermediate container's type is unknown here; supply component construction logic");
			}
		}
		if (data instanceof SignedData) {
			// A signed envelope is a record, so it would otherwise degrade to a plain map
			// with the signature lost. Writes below an owner's slot go through the
			// SignedCursor the owning component wraps around it, never through here.
			throw new IllegalArgumentException(
				"Cannot write through a signed envelope at depth " + i
				+ ": wrap the owner's slot with SignedCursor.create");
		}
		if (!(data instanceof ADataStructure<?> struct)) {
			throw new IllegalArgumentException(
				"Not a data structure at depth " + i + ", found " + Utils.getClassName(data));
		}

		ACell child = struct.get(keys[i]);
		ACell newChild = assocIn(child, value, null, keys, i + 1);
		return RT.assoc(struct, keys[i], newChild); // returns struct unchanged if newChild == child
	}

	/** Reconciles a lattice-backed publication, retaining destination keys outside a keyed lattice. */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> V mergePublication(ALattice<V> lattice,LatticeContext context,V own,V other) {
		V merged=lattice.merge(context,own,other);
		if (!(lattice instanceof AKeyedLattice<?,?> keyed) || (other!=null && !(other instanceof AMap<?,?>))) return merged;
		if (merged==null) return other;
		AMap<ACell,ACell> result=(AMap<ACell,ACell>)other;
		for (Map.Entry<ACell,ACell> entry:((AMap<ACell,ACell>)merged).entrySet()) {
			if (keyed.path(entry.getKey())!=null) {
				// Rebuild an absent destination only for keys this component owns.
				if (result==null) result=(AMap<ACell,ACell>)keyed.zero();
				result=result.assoc(entry.getKey(),entry.getValue());
			}
		}
		return (V)result;
	}

	/** The value a delivery at a path means at this level: that path alone, in the lattice's empty containers. */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> V reduce(ALattice<V> lattice,ACell value,ACell... path) {
		ALattice<?>[] levels=new ALattice<?>[path.length];
		ALattice<?> level=lattice;
		for (int i=0; i<path.length; i++) {
			levels[i]=level;
			level=level.path(path[i]);
			if (level==null) return null;
		}
		ACell reduced=value;
		for (int i=path.length-1; i>=0; i--) {
			if (!(levels[i].zero() instanceof ADataStructure<?> empty)) return null;
			reduced=RT.assoc((ADataStructure<ACell>)empty,path[i],reduced);
			if (reduced==null) return null;
		}
		return (V)reduced;
	}

}
