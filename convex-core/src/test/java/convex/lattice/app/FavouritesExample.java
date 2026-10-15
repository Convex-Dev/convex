package convex.lattice.app;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeApplication;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.SignedCursor;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.OwnerKeyLattice;

/** Shared domain model for the in-memory and persisted favourites examples. */
final class FavouritesExample {
	private FavouritesExample() {}
	static final AKeyPair ALICE=AKeyPair.createSeeded(7310);
	static final AKeyPair BOB=AKeyPair.createSeeded(7311);
	static final AKeyPair CHARLIE=AKeyPair.createSeeded(7312);

	// {:favourites {owner #Signed {:timestamp t :items #{item-ids}}}}
	// Each signed record is one LWW value: its items are replaced, never unioned on merge.
	static final Keyword FAVOURITES=Keyword.intern("favourites");
	private static final Keyword ITEMS=Keyword.intern("items");
	static final Keyword TIMESTAMP=LWWLattice.KEY_TIMESTAMP;
	private static final LWWLattice<AHashMap<Keyword,ACell>> RECORD=
		LWWLattice.create(value -> RT.ensureLong(value.get(TIMESTAMP)).longValue());
	static final OwnerKeyLattice<AHashMap<Keyword,ACell>> OWNERS=OwnerKeyLattice.create(RECORD);
	static final KeyedLattice LATTICE=KeyedLattice.create(FAVOURITES,OWNERS);

	/** The application supplies domain views and a clock; the generic host needs no user's key. */
	static final class FavouritesApp extends ALatticeApplication<Index<Keyword,ACell>> {
		private final AtomicLong clock=new AtomicLong();

		FavouritesApp(LatticeHost<Index<Keyword,ACell>> host) {
			super(LATTICE,host);
		}

		void setTime(long timestamp) { clock.set(timestamp); }

		FavouritesUser user(AKeyPair user) {
			return new FavouritesUser(resolve(FAVOURITES,user.getAccountKey()),user,clock::get);
		}

		SignedData<AHashMap<Keyword,ACell>> record(AccountKey user) {
			return this.<SignedData<AHashMap<Keyword,ACell>>>resolve(FAVOURITES,user).get();
		}

		ASet<CVMLong> items(AccountKey user) {
			var record=record(user);
			return Favourites.items(record==null?null:record.getValue());
		}
	}

	/** A lightweight user view: every edit signs directly into the application working state. */
	static final class FavouritesUser extends ALatticeComponent<AHashMap<Keyword,ACell>> {
		private final LongSupplier clock;

		FavouritesUser(ACursor<SignedData<AHashMap<Keyword,ACell>>> slot,AKeyPair user,LongSupplier clock) {
			super(SignedCursor.create(slot,user));
			this.clock=clock;
		}

		Favourites favourites() { return new Favourites(cursor(),clock); }
	}

	/** Domain edits atomically change items and their timestamp through the user's signing cursor. */
	static final class Favourites extends ALatticeComponent<AHashMap<Keyword,ACell>> {
		private final LongSupplier clock;

		Favourites(AForkableCursor<AHashMap<Keyword,ACell>> cursor,LongSupplier clock) {
			super(cursor);
			this.clock=clock;
		}

		void add(long item) { edit(items -> items.include(CVMLong.create(item))); }
		void remove(long item) { edit(items -> items.exclude(CVMLong.create(item))); }

		private void edit(UnaryOperator<ASet<CVMLong>> change) {
			CVMLong timestamp=CVMLong.create(clock.getAsLong());
			cursor().updateAndGet(value -> {
				AHashMap<Keyword,ACell> record=value==null?Maps.empty():value;
				return record.assoc(ITEMS,change.apply(items(value))).assoc(TIMESTAMP,timestamp);
			});
		}

		@SuppressWarnings("unchecked")
		private static ASet<CVMLong> items(AHashMap<Keyword,ACell> record) {
			return record==null?Sets.empty():(ASet<CVMLong>)record.get(ITEMS);
		}

		ASet<CVMLong> items() { return items(cursor().get()); }
	}

}
