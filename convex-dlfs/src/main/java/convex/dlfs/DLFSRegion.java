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
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
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
public final class DLFSRegion extends ALatticeComponent<
		AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> implements TransactionalComponent<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>,DLFSRegion> {

	/** The standard rule is shared by region and application construction. */
	static final BiPredicate<ACell,AccountKey> KEY_OWNER=(owner,key) -> key.equals(owner);

	/** Standard direct-key ownership, independent of authoring capabilities. */
	public static final OwnerMapLattice<ACell,AHashMap<AString,AVector<ACell>>> LATTICE=
		OwnerMapLattice.create(DLFSDrives.LATTICE,KEY_OWNER);

	private final AStore store;
	private final DLFSLocation location;
	private final Supplier<CVMLong> clock;
	private final DLFSLattice files;
	private final OwnerMapLattice<ACell,AHashMap<AString,AVector<ACell>>> owners;
	private final BiFunction<ACell,AHashMap<AString,AVector<ACell>>,SignedData<AHashMap<AString,AVector<ACell>>>> signer;

	DLFSRegion(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor,
			AStore store,DLFSLocation location,BiPredicate<ACell,AccountKey> verifier,
			BiFunction<ACell,AHashMap<AString,AVector<ACell>>,SignedData<AHashMap<AString,AVector<ACell>>>> signer,
			Supplier<CVMLong> clock,long maxFutureSkew) {
		super(cursor);
		this.store=store;
		this.location=Objects.requireNonNull(location);
		this.clock=Objects.requireNonNull(clock);
		this.files=DLFSLattice.withClock(clock,maxFutureSkew);
		this.owners=OwnerMapLattice.create(MapLattice.create(files),verifier);
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
		var rule=owners.signingFor(Objects.requireNonNull(owner));
		AForkableCursor<AHashMap<AString,AVector<ACell>>> drivesCursor=
			SignedCursor.create(cursor().withDefault(Maps.empty()).path(owner),
				value -> signer==null?null:rule.sign(value,v -> signer.apply(owner,v)));
		return new DLFSDrives(drivesCursor,store,location.child(owner),clock,files);
	}

	/** Creates isolated working state with the same ownership and authoring policy. */
	public DLFSRegion fork() {
		return new DLFSRegion(this);
	}

	private DLFSRegion(DLFSRegion source) {
		super(source);
		store=source.store;
		location=DLFSLocation.at(cursor());
		clock=source.clock;
		files=source.files;
		owners=source.owners;
		signer=source.signer;
	}

	private DLFSRegion(DLFSRegion source,
			AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		super(cursor);
		store=source.store;
		location=DLFSLocation.at(cursor);
		clock=source.clock;
		files=source.files;
		owners=source.owners;
		signer=source.signer;
	}

	@Override
	public DLFSRegion withCursor(AForkableCursor<AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>> cursor) {
		return new DLFSRegion(this,cursor);
	}

	/** Merges incoming envelopes into working state; a fork remains unpublished. */
	@Override
	public AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> merge(
			AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> incoming) {
		return cursor().updateAndGet(own -> mergePublication(own,incoming));
	}

	/** Only whole maps and complete owner envelopes are admissible deliveries. */
	@SuppressWarnings("unchecked")
	@Override
	public AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> mergeAt(
			ACell value,ACell... path) {
		if (path.length==0) {
			return value instanceof AHashMap<?,?>?merge(
				(AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>>)value):cursor().get();
		}
		if (path.length!=1 || !(value instanceof SignedData<?>)) return cursor().get();
		return merge(Maps.of(path[0],value));
	}

	/** Both delivery and publication can author a synthesised value when authorised. */
	@Override
	protected AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> mergePublication(
			AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> own,
			AHashMap<ACell,SignedData<AHashMap<AString,AVector<ACell>>>> other) {
		return signer==null?owners.merge(own,other):owners.mergeAndSign(own,other,signer);
	}
}
