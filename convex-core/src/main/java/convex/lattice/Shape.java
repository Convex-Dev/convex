package convex.lattice;

import convex.core.data.ACell;

/**
 * The empty value at one position in a lattice-structured value, as a cursor needs
 * to know it: what to create when a write arrives through an absent container, and
 * what an update function receives in place of null. A structural shape (JSON-like
 * data) builds missing containers from the key being written through them instead
 * of from a fixed empty value.
 *
 * <p>Every {@link ALattice} is a shape, so a component passes its declared lattice
 * wherever a cursor asks for one. Cursors depend on this interface and never on the
 * lattice itself.</p>
 *
 * @param <V> Value type at this position
 */
public interface Shape<V extends ACell> {

	/** A shape with no empty value: a write through an absent value here fails. */
	Shape<ACell> NONE = () -> null;

	/**
	 * Returns the empty value at this position, or null when nothing can be created
	 * here (a leaf register, or an untyped location).
	 *
	 * @return Empty value, or null
	 */
	V zero();

	/**
	 * Whether missing containers below this position are built from the shape of the
	 * key written through them, as for JSON-like data, rather than from a fixed
	 * empty value.
	 *
	 * @return true for structural data
	 */
	default boolean isStructural() {
		return false;
	}

	/** Returns the shape with no empty value. */
	@SuppressWarnings("unchecked")
	static <V extends ACell> Shape<V> none() {
		return (Shape<V>) NONE;
	}

	/**
	 * Returns a non-structural shape with the given empty value.
	 *
	 * @param <V> Value type
	 * @param zero Empty value, or null for none
	 * @return Shape
	 */
	static <V extends ACell> Shape<V> of(V zero) {
		return () -> zero;
	}
}
