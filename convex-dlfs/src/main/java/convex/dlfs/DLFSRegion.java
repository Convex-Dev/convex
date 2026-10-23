package convex.dlfs;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.util.Utils;
import convex.lattice.OwnedMap;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.fs.DLFSLattice;
import convex.lattice.fs.DLFSLocation;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.OwnerMapLattice;

/**
 * Multi-owner DLFS region with explicit verification, authoring and clock policy.
 *
 * <p>The owner rule is shared by incoming delivery, local writes and fork
 * reconciliation. A signer is a capability, not authority: its result must pass
 * that rule. Without a signer this region relays existing envelopes and retains
 * its established value when combining payloads would require a new signature.
 * Indirect owner identities require an explicit verifier.</p>
 *
 * <p>Policy is bound at construction and retained by forks and transaction views.
 * Callbacks must support concurrent use and retries inside atomic updates.
 * The optional store is passed to drives for incremental blob checkpoints.</p>
 */
public final class DLFSRegion extends OwnedMap<ACell,AHashMap<AString,AVector<ACell>>> implements TransactionalComponent<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>,DLFSRegion> {

	/** The standard rule is shared by region and application construction. */
	static final BiPredicate<ACell,AccountKey> KEY_OWNER=(owner,key) -> key.equals(owner);

	/** Standard direct-key ownership, independent of authoring capabilities. */
	public static final OwnerMapLattice<ACell,AHashMap<AString,AVector<ACell>>> LATTICE=
		OwnerMapLattice.create(DLFSDrives.LATTICE,KEY_OWNER);

	private final AStore store;
	private final DLFSLocation location;
	private final Supplier<CVMLong> clock;
	private final DLFSLattice files;
	private final BiFunction<ACell,AHashMap<AString,AVector<ACell>>,SignedData<AHashMap<AString,AVector<ACell>>>> signer;

	DLFSRegion(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store,DLFSLocation location,BiPredicate<ACell,AccountKey> verifier,
			BiFunction<ACell,AHashMap<AString,AVector<ACell>>,SignedData<AHashMap<AString,AVector<ACell>>>> signer,
			Supplier<CVMLong> clock,long maxFutureSkew) {
		this(cursor,store,location,verifier,signer,clock,DLFSLattice.withClock(clock,maxFutureSkew));
	}

	private DLFSRegion(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store,DLFSLocation location,BiPredicate<ACell,AccountKey> verifier,
			BiFunction<ACell,AHashMap<AString,AVector<ACell>>,SignedData<AHashMap<AString,AVector<ACell>>>> signer,
			Supplier<CVMLong> clock,DLFSLattice files) {
		super(OwnerMapLattice.create(MapLattice.create(files),verifier),cursor);
		this.store=store;
		this.location=Objects.requireNonNull(location);
		this.clock=Objects.requireNonNull(clock);
		this.files=files;
		this.signer=signer;
	}

	/** Connects a direct-key relay with a wall clock and no authoring capability. */
	public static DLFSRegion connect(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store) {
		return connect(cursor,store,null,Utils::getCurrentTimestampCell);
	}

	/** Connects direct-key ownership with a candidate signer, such as {@code keyPair::signData}. */
	public static DLFSRegion connect(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store,Function<AHashMap<AString,AVector<ACell>>,SignedData<AHashMap<AString,AVector<ACell>>>> signer,
			Supplier<CVMLong> clock) {
		return connect(cursor,store,KEY_OWNER,
			signer==null?null:(owner,value) -> signer.apply(value),clock,DLFSLattice.DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW);
	}

	/**
	 * Connects custom ownership (for example an application-resolved DID).
	 * The author may return null for owners it cannot sign for. Verification must
	 * use locally available authority data, without network work inside an update.
	 */
	public static DLFSRegion connect(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store,BiPredicate<ACell,AccountKey> verifier,
			BiFunction<ACell,AHashMap<AString,AVector<ACell>>,SignedData<AHashMap<AString,AVector<ACell>>>> signer,
			Supplier<CVMLong> clock,long maxFutureSkew) {
		return new DLFSRegion(cursor,store,DLFSLocation.at(cursor),verifier,signer,clock,maxFutureSkew);
	}

	/** Opens an unsigned drive-map view through this region's one signing boundary. */
	public DLFSDrives drives(ACell owner) {
		var drivesCursor=authoringCursor(owner,value -> signer==null?null:signer.apply(owner,value));
		return new DLFSDrives(drivesCursor,store,location.child(owner),clock,files);
	}

	/** Creates isolated working state with the same ownership and authoring policy. */
	public DLFSRegion fork() {
		return rebind(storage.fork());
	}

	private DLFSRegion(DLFSRegion source,
			AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		super(source.getLattice(),cursor);
		store=source.store;
		location=DLFSLocation.at(cursor);
		clock=source.clock;
		files=source.files;
		signer=source.signer;
	}

	@Override
	public DLFSRegion withCursor(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		return rebind(cursor);
	}

	@Override
	protected DLFSRegion rebind(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		return new DLFSRegion(this,cursor);
	}

	/** Both delivery and publication can author a synthesised value when authorised. */
	@Override
	protected AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> mergeValues(
			AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> own,
			AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> other) {
		return signer==null?super.mergeValues(own,other):getLattice().mergeAndSign(own,other,signer);
	}
}
