package convex.lattice.app;

import java.util.function.Supplier;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.lattice.ASignedComponent;
import convex.lattice.KeyOwnedIndex;
import convex.lattice.LatticeHost;
import convex.lattice.SetComponent;
import convex.lattice.SingleRegion;
import convex.lattice.Timestamped;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.SignedLattice;

/**
 * A complete favourites application assembled from standard lattice components.
 * Each public key owns a signed record containing a timestamp and a set of item IDs.
 * Local operations author numeric IDs; replicas may carry unfamiliar item values.
 * The host root and owner collection are indexes; each unsigned record is a hash map:
 * <pre>{@code
 * {:favourites {owner #Signed {:timestamp t :items #{item-ids}}}}
 * }</pre>
 *
 * <p>The composition has four responsibilities:</p>
 * <ul>
 *   <li>{@link FavouritesApp} serves only {@code :favourites}, preserving other host regions.</li>
 *   <li>{@link KeyOwnedIndex} verifies ownership and stages the owner collection.</li>
 *   <li>{@link Favourites} signs local records with its explicitly supplied user's key.</li>
 *   <li>{@link Timestamped} orders whole records; {@link SetComponent} edits their items.</li>
 * </ul>
 *
 * <p>A changed local edit updates the items, stamps the record and signs it in one
 * atomic storage operation. Repeated user handles share that signed working state.
 * Incoming envelopes are verified and compared by their signed timestamps; a selected
 * envelope keeps its signature and is never stamped with the receiver's clock.
 * The newer record replaces the entire older record, so removed items stay removed.
 * This is a last-write-wins record, not a union of independently edited replica sets.</p>
 *
 * <p>Publication is explicit: {@code app.sync()} publishes the region to the host;
 * {@code host.sync()} handles host persistence/publication. Neither creating a user
 * handle nor syncing its embedded components publishes the region.</p>
 *
 * <p>Run {@link #main(String[])} as a Java application (for example, Eclipse's
 * Run As &gt; Java Application). It edits two owners' favourites, publishes them,
 * and delivers the signed state to a second in-memory host. No disk store or
 * network is needed; persistence is covered by {@link FavouritesPersistenceTest}.</p>
 *
 * <p>The seeded keys and manually advanced clocks in tests are deterministic fixtures.
 * The application receives only a timestamp supplier, so it does not depend
 * on how time is maintained. Equal timestamps deliberately retain the local value
 * on incoming merges; tests advance time when an update must supersede a replica.</p>
 *
 * @see FavouritesApplicationTest
 * @see FavouritesPersistenceTest
 */
public final class FavouritesExample {
	private FavouritesExample() {}
	static final AKeyPair ALICE=AKeyPair.createSeeded(7310);
	static final AKeyPair BOB=AKeyPair.createSeeded(7311);
	static final AKeyPair CHARLIE=AKeyPair.createSeeded(7312);

	static final Keyword FAVOURITES=Keyword.intern("favourites");
	private static final Keyword ITEMS=Keyword.intern("items");
	static final Keyword TIMESTAMP=Timestamped.TIMESTAMP;

	/** Runs the complete example using the demo keys and the runtime clock. */
	public static void main(String[] args) {
		var host=LatticeHost.create();
		var app=host.addApplication(new FavouritesApp(host,Utils::getCurrentTimestampCell));

		// Domain edits stamp and sign immediately, within the region's working state.
		var alice=app.user(ALICE);
		alice.add(42);
		alice.add(7);
		alice.remove(42);
		app.user(BOB).add(99);
		System.out.println("Alice's favourites: "+alice.items());
		System.out.println("Bob's favourites: "+app.items(BOB.getAccountKey()));
		System.out.println("Host empty before publication: "+host.cursor().get().isEmpty());

		app.sync(); // Publishes only [:favourites]; host.sync() would persist a store-backed host.
		System.out.println("Host empty after publication: "+host.cursor().get().isEmpty());

		// A replica verifies and merges complete signed records without authoring them.
		var replicaHost=LatticeHost.create();
		var replica=replicaHost.addApplication(new FavouritesApp(replicaHost,Utils::getCurrentTimestampCell));
		replicaHost.merge(host.cursor().get());
		replica.sync();
		System.out.println("Alice on replica: "+replica.items(ALICE.getAccountKey()));
		System.out.println("Bob on replica: "+replica.items(BOB.getAccountKey()));
		System.out.println("Published hosts agree: "+host.cursor().get().equals(replicaHost.cursor().get()));
	}

	/**
	 * A root application serving an owner index at {@code [:favourites]}. {@link KeyOwnedIndex} checks
	 * that each envelope's signer matches its owner key. The shared timestamp rule
	 * selects whole payloads during both incoming merge and publication conflicts.
	 * The region needs no signing capability to accept another user's record.
	 * It checks only ownership, signatures and ordering metadata, preserving all
	 * payload fields even when a local domain view cannot interpret them.
	 */
	static final class FavouritesApp extends SingleRegion<KeyOwnedIndex<AHashMap<Keyword,ACell>>> {
		private final Supplier<CVMLong> getTimestamp;

		FavouritesApp(LatticeHost<Index<Keyword,ACell>> host,Supplier<CVMLong> getTimestamp) {
			this(host,new KeyOwnedIndex<>(host.cursor().path(FAVOURITES),Timestamped.lattice()),getTimestamp);
		}

		/** Shares the supplied region's pending state and destination; the clock is authoring policy. */
		FavouritesApp(LatticeHost<Index<Keyword,ACell>> host,
				KeyOwnedIndex<AHashMap<Keyword,ACell>> shared,Supplier<CVMLong> getTimestamp) {
			super(host,FAVOURITES,shared);
			this.getTimestamp=getTimestamp;
		}

		/** Opens a live authoring handle. An absent owner is created only by a changed edit. */
		Favourites user(AKeyPair user) {
			var owner=user.getAccountKey();
			return new Favourites(component().path(owner),component().signingFor(owner),user,getTimestamp);
		}

		/** Reads the relayed field without assuming the remote owner's item representation. */
		ACell items(AccountKey user) {
			var record=component().get(user);
			ACell value=record==null?null:record.getValue().get(ITEMS);
			return value==null?Sets.empty():value;
		}
	}

	/**
	 * A live view of one owner's record. Local writes pass through the embedded set,
	 * then the timestamp wrapper, then the signing boundary. All handles at this path
	 * observe the same edits; this class owns no detached copy or publication step.
	 *
	 * <p>The set supplies ordinary collection operations. Timestamp ordering belongs
	 * to the whole record, so clearing items retains a newer signed record with an empty set rather
	 * than removing the owner entry and losing the evidence of deletion.</p>
	 */
	static final class Favourites extends ASignedComponent<AHashMap<Keyword,ACell>> {
		private final Timestamped<AHashMap<Keyword,ACell>> record;
		final SetComponent<ACell> itemSet;

		Favourites(AForkableCursor<SignedData<AHashMap<Keyword,ACell>>> destination,
				SignedLattice<AHashMap<Keyword,ACell>> rule,AKeyPair user,Supplier<CVMLong> getTimestamp) {
			// This component authors only with the explicitly supplied user's key.
			super(destination,rule,user::signData);
			// Wrap the base signing view once; every domain edit uses cursor().
			record=Timestamped.create(super.cursor().withDefault(Maps.empty()),getTimestamp);
			itemSet=new SetComponent<>(cursor().path(ITEMS));
		}

		/** Direct local edits use the same timestamped path as the embedded set. */
		@Override public AForkableCursor<AHashMap<Keyword,ACell>> cursor() { return record.cursor(); }

		/** Domain checks inspect the payload; the base handles the signed envelope. */
		@Override protected AString checkPayload(ACell payload) {
			AString error=record.checkValue(payload);
			if (error!=null || payload==null) return error;
			return itemSet.checkValue(((AHashMap<?,?>)payload).get(ITEMS));
		}

		void add(long item) { itemSet.add(CVMLong.create(item)); }
		void remove(long item) { itemSet.remove(CVMLong.create(item)); }

		/** Set operations preserve unfamiliar members; an incompatible field remains available in the region. */
		ASet<ACell> items() { return itemSet.get(); }
	}

}
