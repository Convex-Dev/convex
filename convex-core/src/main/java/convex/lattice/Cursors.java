package convex.lattice;

import convex.core.data.ACell;
import convex.core.lang.RT;

public class Cursors {

	public static <V extends ACell> Root<V> of(Object a) {
		return create(RT.cvm(a));
	}

	private static <V extends ACell> Root<V> create(V value) {
		return Root.create(value);
	}
}
