package convex.dlfs;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.store.AStore;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.fs.DLFSLocation;
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
	private final DLFSLocation location;

	private DLFSRegion(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store) {
		this(cursor,store,DLFSLocation.at(cursor));
	}

	private DLFSRegion(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store,DLFSLocation location) {
		super(cursor);
		this.store=store;
		this.location=location;
	}

	/**
	 * Connects a physical DLFS region at the position of a cursor. The application
	 * that owns the root resolves the region's path and hands the cursor down; the
	 * region navigates no further up.
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
	 * Connects a physical DLFS region at the position of a cursor with an explicit
	 * logical location. Applications supply the location of a region they resolved,
	 * so that repeated resolutions of the same path identify the same files.
	 *
	 * @param cursor Cursor at the region
	 * @param store Store for incremental blob checkpoints, or null for heap-only file data
	 * @param location Logical location of the region within the application root
	 * @return Region component
	 */
	public static DLFSRegion connect(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store,DLFSLocation location) {
		if (cursor==null) throw new IllegalArgumentException("Region cursor must not be null");
		if (location==null) throw new IllegalArgumentException("Region location must not be null");
		return new DLFSRegion(cursor,store,location);
	}

	/**
	 * Gets the drive-map component belonging to an owner. This component owns the
	 * owner-keyed level, so it wraps the owner's slot in the signing boundary that
	 * {@link #LATTICE} verifies with; the drive map and drives below never see
	 * {@code SignedData}.
	 */
	public DLFSDrives drives(AccountKey owner) {
		if (owner==null) throw new IllegalArgumentException("Owner key must not be null");
		var rule=LATTICE.signingFor(owner);
		var policy=getContext();
		AForkableCursor<AHashMap<AString,AVector<ACell>>> drivesCursor=
			SignedCursor.create(cursor.withDefault(Maps.empty()).path(owner),value -> rule.sign(policy,value));
		return configured(new DLFSDrives(drivesCursor,store,location.child(owner)));
	}

	/** Creates an isolated temporary region component, merging on sync with {@link #LATTICE}. */
	public DLFSRegion fork() {
		return configured(new DLFSRegion(this));
	}

	private DLFSRegion(DLFSRegion source) {
		super(source);
		this.store=source.store;
		this.location=DLFSLocation.at(cursor);
	}

	@Override
	public DLFSRegion withCursor(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		return configured(new DLFSRegion(cursor,store));
	}

	/** Reconciles this component's draft using its domain merge rule. */
	@Override
	protected AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> mergePublication(
			AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> own,
			AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> other) {
		return LATTICE.merge(getContext(),own,other);
	}
}
