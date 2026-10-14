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
import convex.lattice.cursor.AForkableCursor;
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

	private DLFSRegion(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store) {
		super(cursor);
		this.store=store;
	}

	/**
	 * Connects a physical DLFS region at a path below a host root, navigated with
	 * the host's lattice and checkpointing streamed blob data into the host store.
	 */
	public static DLFSRegion connect(RootComponent<?> host, ACell... path) {
		if (host==null) throw new IllegalArgumentException("Host must not be null");
		return connect(host.resolve(path),host.store());
	}

	/**
	 * Connects a physical DLFS region at the position of a cursor.
	 *
	 * @param cursor Cursor at the region
	 * @param store Store for incremental blob checkpoints, or null for heap-only file data
	 * @return Region component
	 */
	public static DLFSRegion connect(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store) {
		if (cursor==null) throw new IllegalArgumentException("Region cursor must not be null");
		return new DLFSRegion(cursor,store);
	}

	/**
	 * Connects a physical DLFS region at a path below any cursor, declaring the
	 * region's shape so it can be created when absent. Intermediates along the path
	 * must already exist, or be navigated with their own shapes by the caller.
	 *
	 * @param parent Cursor to navigate from
	 * @param store Store for incremental blob checkpoints, or null for heap-only file data
	 * @param path Path from the cursor to the region
	 * @return Region component
	 */
	@SuppressWarnings("unchecked")
	public static DLFSRegion connect(AForkableCursor<?> parent, AStore store, ACell... path) {
		if (parent==null) throw new IllegalArgumentException("Parent cursor must not be null");
		AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor=
			(path.length==0)
				?(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>>)parent
				:parent.child(path,LATTICE);
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
		AForkableCursor<AHashMap<AString,AVector<ACell>>> drivesCursor=
			SignedCursor.forOwner(cursor.path(owner),LATTICE,owner);
		return new DLFSDrives(drivesCursor,store);
	}

	/** Creates an isolated temporary region component, merging on sync with {@link #LATTICE}. */
	public DLFSRegion fork() {
		return new DLFSRegion(cursor.fork(LATTICE),store);
	}

	@Override
	public DLFSRegion withCursor(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		return new DLFSRegion(cursor,store);
	}

}
