package convex.lattice;

import convex.core.cvm.Keywords;
import convex.lattice.data.DataLattice;
import convex.lattice.generic.KeyedLattice;

/**
 * Static utility base for the lattice
 */
public class Lattice {

	public static KeyedLattice ROOT = KeyedLattice.create(
		Keywords.DATA, DataLattice.INSTANCE
	);
}
