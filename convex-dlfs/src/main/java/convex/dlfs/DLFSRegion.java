package convex.dlfs;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.generic.OwnerLattice;

/**
 * Physical multi-owner DLFS region at one lattice path.
 *
 * <p>Each owner has a signed {@link DLFSDrives} value. Local routing across
 * multiple regions or roots remains the responsibility of
 * {@link DLFSDriveManager}.</p>
 */
public final class DLFSRegion extends ALatticeComponent<
		AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> {

	/** Standard owner-signed lattice definition for a DLFS region. */
	public static final OwnerLattice<AHashMap<AString,AVector<ACell>>> LATTICE=
		OwnerLattice.create(DLFSDrives.LATTICE);

	private DLFSRegion(ALatticeComponent<?> parent,
			ALatticeCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		super(parent,cursor);
	}

	/** Connects a physical DLFS region at an arbitrary lattice path. */
	public static DLFSRegion connect(ALatticeComponent<?> parent, ACell... path) {
		if (parent==null) throw new IllegalArgumentException("Parent component must not be null");
		ALatticeCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor=
			parent.cursor().path(path);
		return new DLFSRegion(parent,cursor);
	}

	/** Gets the signed drive-map component belonging to an owner. */
	public DLFSDrives drives(AccountKey owner) {
		if (owner==null) throw new IllegalArgumentException("Owner key must not be null");
		ALatticeCursor<AHashMap<AString,AVector<ACell>>> drivesCursor=
			cursor.path(owner,Keywords.VALUE);
		return new DLFSDrives(this,drivesCursor);
	}

	/** Creates an isolated temporary region component. */
	public DLFSRegion fork() {
		return new DLFSRegion(parent(),cursor.fork());
	}
}
