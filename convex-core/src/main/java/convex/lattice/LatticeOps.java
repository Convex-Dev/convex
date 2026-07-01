package convex.lattice;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Utility operations for lattice-aware data manipulation.
 *
 * <p>Provides {@link #assocIn} as a lattice-aware replacement for
 * {@link RT#assocIn}. When writing through a null intermediate, uses
 * {@code lattice.zero()} to create correctly-typed empty containers
 * (e.g. {@link convex.core.data.Index} instead of {@link convex.core.data.AHashMap}).</p>
 */
public class LatticeOps {

	/**
	 * Lattice-aware associative update at a single key.
	 *
	 * <p>Convenience shorthand for {@link #assocIn} with a single-element path.</p>
	 *
	 * @param <T> Return type
	 * @param a Data structure (may be null if lattice provides zero)
	 * @param key Key to write at
	 * @param value Value to set at the key
	 * @param lattice Lattice describing the type (may be null if a is non-null)
	 * @return Updated data structure
	 */
	public static <T extends ACell> T assoc(ACell a, ACell key, ACell value, ALattice<?> lattice) {
		return assocIn(a, value, lattice, key);
	}

	/**
	 * Lattice-aware associative update at a nested path.
	 *
	 * <p>Two-pass algorithm like {@link RT#assocIn}:</p>
	 * <ol>
	 *   <li>Forward pass: descend through data, using {@code lattice.zero()}
	 *       for null intermediates</li>
	 *   <li>Backward pass: reconstruct the structure immutably</li>
	 * </ol>
	 *
	 * <p>Rules for null intermediates:</p>
	 * <ul>
	 *   <li>{@code data==null && lat!=null}: use {@code lat.zero()} to auto-initialise</li>
	 *   <li>{@code data==null && lat.zero()==null}: throw (leaf lattice, cannot create container)</li>
	 *   <li>{@code data==null && lat==null}: throw (beyond known lattice hierarchy)</li>
	 *   <li>{@code data!=null}: use existing structure regardless of lattice</li>
	 * </ul>
	 *
	 * @param <T> Return type
	 * @param a Root data structure (may be null if baseLattice provides zero)
	 * @param value Value to set at the end of the path
	 * @param baseLattice Lattice describing the type hierarchy (may be null if data is non-null at every level)
	 * @param keys Path of keys to navigate
	 * @return Updated root data structure
	 * @throws IllegalStateException if a null intermediate cannot be initialised (no lattice or leaf lattice)
	 * @throws IllegalArgumentException if a non-null intermediate is not a data structure
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T assocIn(ACell a, ACell value, ALattice<?> baseLattice, ACell... keys) {
		int n = keys.length;
		ADataStructure<?>[] ass = new ADataStructure[n];
		ACell[] ks = new ACell[n];
		ACell data = a;
		ALattice<?> lat = baseLattice;

		// Forward pass: descend, using lattice.zero() for null intermediates
		for (int i = 0; i < n; i++) {
			if (data == null) {
				if (lat == null) throw new IllegalStateException(
					"Cannot write through non-existent path at depth " + i +
					": no lattice type information available");
				if (lat.isStructural()) {
					// Structural region (e.g. JSON): build the container from the
					// key that indexes into it, not from a fixed lattice zero().
					data = containerForKey(keys[i]);
				} else {
					data = lat.zero();
					if (data == null) throw new IllegalStateException(
						"Cannot write through non-existent path at depth " + i +
						": lattice zero is null (leaf lattice)");
				}
			}
			if (!(data instanceof ADataStructure<?> struct)) {
				throw new IllegalArgumentException(
					"Not a data structure at depth " + i +
					", found " + Utils.getClassName(data));
			}
			ass[i] = struct;
			ks[i] = keys[i];
			data = struct.get(keys[i]);
			lat = (lat != null) ? lat.path(keys[i]) : null;
		}

		// Backward pass: reconstruct immutably
		for (int i = n - 1; i >= 0; i--) {
			value = RT.assoc(ass[i], ks[i], value);
		}
		return (T) value;
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
