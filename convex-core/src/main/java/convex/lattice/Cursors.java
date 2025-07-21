package convex.lattice;

import convex.core.data.ACell;
import convex.core.lang.RT;

public class Cursors {

	/**
	 * Create a root cursor with the given value
	 * @param <V> Type of cursor value
	 * @param a Any object to be converted to CVM type
	 * @return New cursor instance
	 */
	public static <V extends ACell> Root<V> of(Object a) {
		return create(RT.cvm(a));
	}

	/**
	 * Create a root cursor with the given value
	 * @param <V> Type of cursor value
	 * @param a Any compatible CVM value
	 * @return New cursor instance
	 */
	private static <V extends ACell> Root<V> create(V value) {
		return Root.create(value);
	}
}
