package convex.lattice.app;

import java.util.concurrent.atomic.AtomicReference;
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
 *   <li>{@link FavouritesRegion} verifies ownership and stages the owner collection.</li>
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
 * handle nor syncing its embedded components publishes the region. For example:</p>
 * <pre>{@code
 * var host = LatticeHost.create();
 * var app = new FavouritesApp(host);
 * host.configureApplications(java.util.List.of(app)); // Route incoming state to this app.
 * app.favourites().setTime(100);
 * app.favourites().user(ALICE).add(42);               // Signed region edit, not yet in the host.
 * app.sync();                                       // Publish at [:favourites].
 * host.sync();                                      // Persist when the host has a store.
 * }</pre>
 *
 * <p>The seeded keys and manually advanced clock are deterministic test fixtures.
 * The domain component receives only a timestamp supplier, so it does not depend
 * on how time is maintained. Equal timestamps deliberately retain the local value
 * on incoming merges; tests advance time when an update must supersede a replica.</p>
 *
 * @see FavouritesApplicationTest
 * @see FavouritesPersistenceTest
 */
final class FavouritesExample {
	private FavouritesExample() {}
	static final AKeyPair ALICE=AKeyPair.createSeeded(7310);
	static final AKeyPair BOB=AKeyPair.createSeeded(7311);
	static final AKeyPair CHARLIE=AKeyPair.createSeeded(7312);

	static final Keyword FAVOURITES=Keyword.intern("favourites");
	private static final Keyword ITEMS=Keyword.intern("items");
	static final Keyword TIMESTAMP=Timestamped.TIMESTAMP;

	/**
	 * Serves one region using inherited routing and publication. It has no custom
	 * root merge: values outside {@code :favourites} belong to other applications.
	 */
	static final class FavouritesApp extends SingleRegion<FavouritesRegion> {
		FavouritesApp(LatticeHost<Index<Keyword,ACell>> host) {
			this(host,new FavouritesRegion(host.cursor().path(FAVOURITES)));
		}

		/** Shares the region's working state and clock; does not create a second draft. */
		FavouritesApp(LatticeHost<Index<Keyword,ACell>> host,FavouritesRegion shared) {
			super(host,FAVOURITES,shared);
		}

		FavouritesRegion favourites() { return component(); }
	}

	/**
	 * Stages the owner index at {@code [:favourites]}. {@link KeyOwnedIndex} checks
	 * that each envelope's signer matches its owner key. The shared timestamp rule
	 * selects whole payloads during both incoming merge and publication conflicts.
	 * The region needs no signing capability to accept another user's record.
	 * It checks only ownership, signatures and ordering metadata, preserving all
	 * payload fields even when a local domain view cannot interpret them.
	 */
	static final class FavouritesRegion extends KeyOwnedIndex<AHashMap<Keyword,ACell>> {
		// Test-controlled time shared by all user handles, including handles already opened.
		private final AtomicReference<CVMLong> clock=new AtomicReference<>(CVMLong.ZERO);

		FavouritesRegion(AForkableCursor<Index<AccountKey,SignedData<AHashMap<Keyword,ACell>>>> destination) {
			super(destination,Timestamped.lattice());
		}

		/** Sets the exact next edit time; edits do not automatically advance it. */
		void setTime(long timestamp) { clock.set(CVMLong.create(timestamp)); }

		/** Opens a live authoring handle. An absent owner is created only by a changed edit. */
		Favourites user(AKeyPair user) {
			var owner=user.getAccountKey();
			return new Favourites(path(owner),getLattice().signingFor(owner),user,clock::get);
		}

		SignedData<AHashMap<Keyword,ACell>> record(AccountKey user) {
			return get(user);
		}

		/** Reads the relayed field without assuming the remote owner's item representation. */
		ACell items(AccountKey user) {
			var record=record(user);
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
		final SetComponent<ACell> items;

		Favourites(AForkableCursor<SignedData<AHashMap<Keyword,ACell>>> destination,
				SignedLattice<AHashMap<Keyword,ACell>> rule,AKeyPair user,Supplier<CVMLong> getTimestamp) {
			// This component authors only with the explicitly supplied user's key.
			super(destination,rule,user::signData);
			// The inherited field is the unsigned signing view. Supply an absent record
			// for editing without storing it until something changes; the set handles nil itself.
			record=Timestamped.create(cursor.withDefault(Maps.empty()),getTimestamp);
			items=new SetComponent<>(record.cursor().path(ITEMS));
		}

		/** Direct local edits use the same timestamped path as the embedded set. */
		@Override public AForkableCursor<AHashMap<Keyword,ACell>> cursor() { return record.cursor(); }

		/** Checks a complete candidate without editing, stamping or signing it. */
		@Override public AString checkValue(ACell value) {
			AString error=super.checkValue(value);
			if (error!=null || value==null) return error;
			ACell payload=((SignedData<?>)value).getValue();
			error=record.checkValue(payload);
			if (error!=null || payload==null) return error;
			return items.checkValue(((AHashMap<?,?>)payload).get(ITEMS));
		}

		void add(long item) { items.add(CVMLong.create(item)); }
		void remove(long item) { items.remove(CVMLong.create(item)); }

		/** Set operations preserve unfamiliar members; an incompatible field remains available in the region. */
		ASet<ACell> items() { return items.get(); }
	}

}
