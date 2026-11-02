package convex.lattice;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Persister;

/**
 * Base for one service's state that is not shared externally, held as a record at
 * {@code [:local <owner> <service>]} ({@link LocalLattice}). Subclasses add the
 * service's domain operations over {@link #cursor()}.
 *
 * <p>The component works on a private copy of its record. Edits through
 * {@link #cursor()} stamp {@code :timestamp} from the supplied clock but neither sign
 * nor reach the owner's slot. {@link #sync()} writes the record into the slot with a
 * single signature; a sync with nothing changed writes and signs nothing. When to
 * sync is the application's policy, so any number of edits can share one signature.</p>
 *
 * <p>Several services share an owner's slot, each under its own keyword, and each
 * sync re-signs the slot. Records merge whole, the newer {@code :timestamp} winning
 * and a tie keeping the current record, so a restored backup and the current record
 * resolve to the newer one in either direction. {@link #merge} takes such a record
 * into the private copy, for the next sync to publish. Where records come from is the
 * application's decision: {@code :local} is not exchanged with other nodes.</p>
 *
 * <p>Given a persistence function, such as the host's, {@link #persist()} writes the
 * private copy to the application's store without signing or touching the slot.</p>
 */
public abstract class ALocalComponent extends ALatticeComponent<AHashMap<Keyword,ACell>> {

	private final Timestamped<AHashMap<Keyword,ACell>> record;

	/**
	 * Binds a private copy of {@code [:local owner service]} under the supplied root.
	 * Construction creates no state: the first sync of a change creates any missing
	 * containers on the way to the record.
	 *
	 * @param root Root cursor of the containing application
	 * @param service Keyword naming this service's record in the owner's slot
	 * @param owner Key that owns, and signs, the slot
	 * @param signer Signs the owner's slot when the record is synced
	 * @param clock Supplies the record's {@code :timestamp} for each edit
	 * @param persister Persists the private copy, such as {@code host::persist}, or null if it is never persisted
	 */
	protected ALocalComponent(AForkableCursor<Index<Keyword,ACell>> root,Keyword service,AccountKey owner,
			Function<AHashMap<Keyword,ACell>,SignedData<AHashMap<Keyword,ACell>>> signer,Supplier<CVMLong> clock,
			Persister<AHashMap<Keyword,ACell>> persister) {
		super(recordPath(root,service,owner,signer).fork(),persister);
		record=Timestamped.create(storage.withDefault(Maps.empty()),clock);
	}

	private static AForkableCursor<AHashMap<Keyword,ACell>> recordPath(AForkableCursor<Index<Keyword,ACell>> root,
			Keyword service,AccountKey owner,
			Function<AHashMap<Keyword,ACell>,SignedData<AHashMap<Keyword,ACell>>> signer) {
		var slots=new OwnedMap<ACell,AHashMap<Keyword,ACell>>(LocalLattice.LATTICE,
			new IndexComponent<Keyword,ACell>(root).<AHashMap<ACell,SignedData<AHashMap<Keyword,ACell>>>>path(LocalLattice.KEY_LOCAL));
		var slot=new MapComponent<Keyword,ACell>(slots.authoringCursor(Objects.requireNonNull(owner),signer));
		return slot.<AHashMap<Keyword,ACell>>path(Objects.requireNonNull(service));
	}

	/** The stamped editing view of the private record; an absent record reads as an empty map. */
	@Override
	public AForkableCursor<AHashMap<Keyword,ACell>> cursor() {
		return record.cursor();
	}

	/** Takes a complete record into the private copy if it is newer. Nothing is re-stamped or signed. */
	@Override
	public AHashMap<Keyword,ACell> merge(AHashMap<Keyword,ACell> incoming) {
		return record.merge(incoming);
	}

	/** Accepts only a complete record at this level; any other value or path changes nothing. */
	@SuppressWarnings("unchecked")
	@Override
	public AHashMap<Keyword,ACell> mergeAt(ACell incoming,ACell... path) {
		if (path.length!=0 || !(incoming instanceof AHashMap<?,?> map)) return cursor().get();
		return merge((AHashMap<Keyword,ACell>)map);
	}

	/** When the slot's record also changed, the newer record wins. */
	@Override
	protected AHashMap<Keyword,ACell> mergePublication(AHashMap<Keyword,ACell> own,AHashMap<Keyword,ACell> other) {
		return record.getLattice().merge(own,other);
	}
}
