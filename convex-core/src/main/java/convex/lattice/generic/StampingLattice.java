package convex.lattice.generic;

import java.util.function.BiFunction;

import convex.core.data.ACell;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Stamp-on-write lattice layer.
 *
 * <p>Marks a level whose values must be stamped with the write clock as they are
 * written. The timestamp is sourced from the write context (the single write
 * clock); the caller-supplied stamp function only says <em>where</em> to inject a
 * given timestamp into the value's shape (the write-side dual of a timestamp
 * extractor). Merge and navigation are delegated entirely to the inner lattice, so
 * this layer knows nothing about how values merge or what they contain.</p>
 *
 * <p>The lattice declares the boundary; the component which owns this level
 * enforces it by wrapping its cursor:
 * {@code StampedCursor.create(cursor.path(key), STAMPING).path(...)}. Lattices do
 * not construct cursors.</p>
 *
 * <p>Compose with a merge layer and a structure layer to build a durable,
 * navigable region, e.g.
 * {@code StampingLattice.create(LWWLattice.create(JSONLattice.INSTANCE, ts), stamp)}:
 * whole-value LWW merge (deletions durable) + JSON navigation + stamp-on-write.</p>
 *
 * @param <V> Type of lattice values
 */
public class StampingLattice<V extends ACell> extends ADelegatingLattice<V> {

	private final BiFunction<V, CVMLong, V> stampFn;

	private StampingLattice(ALattice<V> inner, BiFunction<V, CVMLong, V> stampFn) {
		super(inner);
		this.stampFn = stampFn;
	}

	/**
	 * Creates a stamp-on-write layer over an inner lattice.
	 *
	 * @param <V> Value type
	 * @param inner Lattice providing merge and navigation
	 * @param stampFn Injects a given timestamp (sourced from the context at write time)
	 *                into a value's shape
	 * @return New StampingLattice instance
	 */
	public static <V extends ACell> StampingLattice<V> create(ALattice<V> inner, BiFunction<V, CVMLong, V> stampFn) {
		return new StampingLattice<>(inner, stampFn);
	}

	/** Returns the function injecting a timestamp into a value at this level. */
	public BiFunction<V, CVMLong, V> getStampFunction() {
		return stampFn;
	}
}
