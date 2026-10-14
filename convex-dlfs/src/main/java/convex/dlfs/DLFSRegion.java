package convex.dlfs;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.store.AStore;
import convex.lattice.ALatticeComponent;
import convex.lattice.RootComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.generic.OwnerLattice;

/**
 * Physical multi-owner DLFS region at one lattice path.
 *
 * <p>Each owner has a signed {@link DLFSDrives} value. The region carries an
 * optional store, handed down to drives for incremental blob checkpoints during
 * streamed writes. Local routing across multiple regions or roots remains the
 * responsibility of {@link DLFSDriveManager}.</p>
 */
public final class DLFSRegion extends ALatticeComponent<
		AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> implements TransactionalComponent<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>,DLFSRegion> {

	/** Standard owner-signed lattice definition for a DLFS region. */
	public static final OwnerLattice<AHashMap<AString,AVector<ACell>>> LATTICE=
		OwnerLattice.create(DLFSDrives.LATTICE);

	private final AStore store;

	private DLFSRegion(ALatticeCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store) {
		super(cursor);
		this.store=store;
	}

	/**
	 * Connects a physical DLFS region at a path below a host root, checkpointing
	 * streamed blob data into the host store.
	 */
	public static DLFSRegion connect(RootComponent<?> host, ACell... path) {
		if (host==null) throw new IllegalArgumentException("Host must not be null");
		return connect(host.cursor(),host.store(),path);
	}

	/**
	 * Connects a physical DLFS region at a path below any cursor.
	 *
	 * @param parent Cursor to navigate from
	 * @param store Store for incremental blob checkpoints, or null for heap-only file data
	 * @param path Path from the cursor to the region
	 * @return Region component
	 */
	public static DLFSRegion connect(ALatticeCursor<?> parent, AStore store, ACell... path) {
		if (parent==null) throw new IllegalArgumentException("Parent cursor must not be null");
		ALatticeCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor=
			parent.path(path);
		return new DLFSRegion(cursor,store);
	}

	/**
	 * Gets the drive-map component belonging to an owner. This component owns the
	 * owner-keyed level, so it wraps the owner's slot in the signing boundary that
	 * {@link #LATTICE} verifies with; the drive map and drives below never see
	 * {@code SignedData}.
	 */
	public DLFSDrives drives(AccountKey owner) {
		if (owner==null) throw new IllegalArgumentException("Owner key must not be null");
		ALatticeCursor<AHashMap<AString,AVector<ACell>>> drivesCursor=
			SignedCursor.forOwner(cursor.path(owner),LATTICE,owner);
		return new DLFSDrives(drivesCursor,store);
	}

	/** Creates an isolated temporary region component. */
	public DLFSRegion fork() {
		return new DLFSRegion(cursor.fork(),store);
	}

	@Override
	public DLFSRegion withCursor(ALatticeCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		return new DLFSRegion(cursor,store);
	}

}
