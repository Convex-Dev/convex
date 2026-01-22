package convex.lattice;

import convex.core.cvm.Keywords;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.lattice.data.DataLattice;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerLattice;

/**
 * Static utility base for the lattice
 */
public class Lattice {

	/**
	 * ROOT lattice structure with support for:
	 * - :data - General purpose data storage
	 * - :fs - DLFS replicated filesystem (owner -> drive name -> DLFS node)
	 *   where drive names are AString (not Keywords)
	 */
	public static KeyedLattice ROOT = KeyedLattice.create(
		Keywords.DATA, DataLattice.INSTANCE,
		Keywords.FS, OwnerLattice.create(
			MapLattice.create(DLFSLattice.INSTANCE)
		)
	);
}
