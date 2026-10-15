package convex.lattice;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Utility operations for shape-aware data manipulation.
 *
 * <p>Provides {@link #assocIn} as a replacement for {@link RT#assocIn} which knows
 * how to create a missing container: from the {@link Shape} of the position being
 * written through, or from the key for structural (JSON-like) data. Nothing is
 * ever silently promoted to a hash map.</p>
 */
public class LatticeOps {

	/**
	 * Shape-aware associative update at a single key.
	 *
	 * @param <T> Return type
	 * @param a Data structure (may be null if the shape provides an empty value)
	 * @param key Key to write at
	 * @param value Value to set at the key
	 * @param shape Shape of {@code a}'s position, or null for none
	 * @return Updated data structure
	 */
	public static <T extends ACell> T assoc(ACell a, ACell key, ACell value, Shape<?> shape) {
		return assocIn(a, value, shape, key);
	}

	/**
	 * Shape-aware associative update at a nested path.
	 *
	 * <p>Rules for a missing container:</p>
	 * <ul>
	 *   <li>At the base, {@code shape.zero()} supplies the correctly typed empty
	 *       container; a null zero is an error.</li>
	 *   <li>Below the base, a structural shape builds each missing container from the
	 *       key written through it; otherwise a missing intermediate is an error,
	 *       because only the component owning that level knows its type. Navigate
	 *       with typed children instead.</li>
	 *   <li>An existing container is used regardless of shape.</li>
	 * </ul>
	 *
	 * @param <T> Return type
	 * @param a Root data structure (may be null if the shape provides an empty value)
	 * @param value Value to set at the end of the path
	 * @param shape Shape of {@code a}'s position, or null for none
	 * @param keys Path of keys to navigate
	 * @return Updated root data structure
	 * @throws IllegalStateException if a missing container cannot be created
	 * @throws IllegalArgumentException if a non-null intermediate is not a data structure, or is a
	 *         signed envelope (a signature covers its value, so nothing may be written through it)
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T assocIn(ACell a, ACell value, Shape<?> shape, ACell... keys) {
		boolean structural = (shape != null) && shape.isStructural();
		return (T) assocIn(a, value, shape, structural, keys, 0);
	}

	/**
	 * Recursive core: descends to depth {@code i}, then rebuilds on the way back up.
	 * Copy-on-change — each level's {@link RT#assoc} returns the existing structure
	 * unchanged when the child below it didn't change, so a no-op write returns the
	 * original {@code data} by reference and allocates nothing (no working arrays).
	 */
	private static ACell assocIn(ACell data, ACell value, Shape<?> shape, boolean structural, ACell[] keys, int i) {
		if (i >= keys.length) return value;

		if (data == null) {
			if (structural) {
				// Structural region (e.g. JSON): build the container from the key
				// that indexes into it.
				data = containerForKey(keys[i]);
			} else if (i == 0) {
				data = (shape == null) ? null : shape.zero();
				if (data == null) throw new IllegalStateException(
					"Cannot write through an absent value: no empty value is known for this position");
			} else {
				throw new IllegalStateException(
					"Cannot write through non-existent path at depth " + i
					+ ": the intermediate container's type is unknown here; navigate with typed children");
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
		ACell newChild = assocIn(child, value, null, structural, keys, i + 1);
		return RT.assoc(struct, keys[i], newChild); // returns struct unchanged if newChild == child
	}

	/**
	 * Chooses an empty container for a missing intermediate in a structural
	 * (e.g. JSON) region, based on the shape of the key that indexes into it:
	 * <ul>
	 *   <li>integer → {@link Vectors#empty() vector} (array index)</li>
	 *   <li>string → {@link Maps#empty() hash map} (dynamic JSON object key)</li>
	 *   <li>keyword / blob / address → {@link Index#EMPTY index} (short internal key)</li>
	 *   <li>anything else → hash map</li>
	 * </ul>
	 *
	 * <p><b>Vector caveat:</b> Convex vectors cannot be grown via {@code assoc}
	 * (only existing indices can be replaced), so a freshly-created vector
	 * intermediate supports navigation but not element creation through this path.</p>
	 *
	 * @param key Key that will index into the container
	 * @return An empty container appropriate for the key shape
	 */
	public static ADataStructure<?> containerForKey(ACell key) {
		if (key instanceof AInteger) return Vectors.empty();
		if (key instanceof AString) return Maps.empty();      // dynamic JSON string key
		if (key instanceof ABlobLike) return Index.EMPTY;     // keyword / blob / address
		return Maps.empty();
	}
}
