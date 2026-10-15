package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Ref;
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

/** Alice and Bob edit locally; Charlie publishes from a separate in-memory host. */
class SignedComponentTest {

	static final AKeyPair ALICE=AKeyPair.createSeeded(7310);
	static final AKeyPair BOB=AKeyPair.createSeeded(7311);
	private static final AKeyPair CHARLIE=AKeyPair.createSeeded(7312);

	// {:favourites {owner #Signed {:timestamp t :items #{item-ids}}}}
	// Each signed record is one LWW value: its items are replaced, never unioned on merge.
	private static final Keyword FAVOURITES=Keyword.intern("favourites");
	private static final Keyword ITEMS=Keyword.intern("items");
	static final Keyword TIMESTAMP=LWWLattice.KEY_TIMESTAMP;
	private static final LWWLattice<AHashMap<Keyword,ACell>> RECORD=
		LWWLattice.create(value -> RT.ensureLong(value.get(TIMESTAMP)).longValue());
	private static final OwnerKeyLattice<AHashMap<Keyword,ACell>> OWNERS=OwnerKeyLattice.create(RECORD);
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

	@Test void openingTheSameUserTwiceSharesSignedEdits() {
		var host=LatticeHost.create();
		var app=new FavouritesApp(host);
		host.configureApplications(List.of(app));
		var first=app.user(ALICE);
		var second=app.user(ALICE);
		assertNotSame(first,second,"User handles are distinct, not cached components");
		var firstFavourites=first.favourites();
		var secondFavourites=second.favourites();
		app.setTime(100);
		firstFavourites.add(10);
		assertEquals(Sets.of(10),secondFavourites.items(),"Repeated user views share edits immediately");
		assertTrue(app.record(ALICE.getAccountKey()).checkSignature(),"The first edit is already signed");
		app.setTime(200);
		secondFavourites.add(20);
		secondFavourites.remove(10);
		assertEquals(Sets.of(20),firstFavourites.items());
		assertEquals(Sets.of(20),app.user(ALICE).favourites().items());
		assertEquals(LATTICE.zero(),host.cursor().get(),"Only the application decides when to publish to the host");
		assertEquals(Sets.of(20),app.items(ALICE.getAccountKey()));
		assertEquals(CVMLong.create(200),app.record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		assertTrue(app.record(ALICE.getAccountKey()).checkSignature());
		app.sync();
		assertEquals(app.cursor().get(),host.cursor().get());
	}

	@Test void concurrentUserViewsPreserveEveryAddition() throws Exception {
		try (var executor=Executors.newFixedThreadPool(2)) {
			var host=LatticeHost.create();
			var app=new FavouritesApp(host);
			host.configureApplications(List.of(app));
			app.setTime(100);
			var first=app.user(ALICE).favourites();
			var second=app.user(ALICE).favourites();
			var firstWriter=executor.submit(() -> {
				for (int i=0;i<20;i++) first.add(i);
			});
			var secondWriter=executor.submit(() -> {
				for (int i=20;i<40;i++) second.add(i);
			});
			firstWriter.get(10,TimeUnit.SECONDS);
			secondWriter.get(10,TimeUnit.SECONDS);
			assertEquals(40,first.items().count());
			assertEquals(first.items(),second.items());
			assertEquals(first.items(),app.items(ALICE.getAccountKey()));
			assertTrue(app.record(ALICE.getAccountKey()).checkSignature());
			assertEquals(CVMLong.create(100),app.record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
			assertEquals(LATTICE.zero(),host.cursor().get());
			app.sync();
			assertEquals(app.cursor().get(),host.cursor().get());
		}
	}

	@Test void charliesNewerRecordWinsRegardlessOfDeliveryOrder() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var remoteHost=LatticeHost.create();
		var remote=new FavouritesApp(remoteHost);
		remoteHost.configureApplications(List.of(remote));
		var replicaHost=LatticeHost.create();
		var replica=new FavouritesApp(replicaHost);
		replicaHost.configureApplications(List.of(replica));
		local.setTime(500);
		var alice=local.user(ALICE);
		var bob=local.user(BOB);
		alice.favourites().add(10);
		bob.favourites().add(20);
		local.sync();
		replicaHost.merge(localHost.cursor().get());
		replica.sync();

		var charlie=remote.user(CHARLIE);
		remote.setTime(100);
		charlie.favourites().add(30);
		charlie.favourites().add(40);
		remote.sync();
		var older=remoteHost.cursor().get();
		remote.setTime(200);
		charlie.favourites().remove(30);
		remote.sync();
		var newer=remoteHost.cursor().get();

		local.setTime(10000);
		localHost.merge(older);
		localHost.merge(newer);
		replica.setTime(1);
		replicaHost.merge(newer);
		replicaHost.merge(older);
		localHost.merge(older); // Replays cannot resurrect the removed item.
		assertNull(localHost.cursor().get(FAVOURITES,CHARLIE.getAccountKey()),
			"Incoming merges wait for application publication");
		assertEquals(local.cursor().get(),replica.cursor().get());
		assertEquals(Sets.of(40),local.items(CHARLIE.getAccountKey()));
		assertEquals(remote.record(CHARLIE.getAccountKey()),local.record(CHARLIE.getAccountKey()),
			"The application adopts Charlie's original signed record without his private key");
		assertEquals(CVMLong.create(200),local.record(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		assertEquals(Sets.of(10),local.items(ALICE.getAccountKey()));
		assertEquals(Sets.of(20),local.items(BOB.getAccountKey()));
		assertTrue(local.record(CHARLIE.getAccountKey()).checkSignature());
		local.sync();
		replica.sync();
		assertEquals(local.cursor().get(),localHost.cursor().get());
		assertEquals(localHost.cursor().get(),replicaHost.cursor().get());
	}

	@Test void clearingFavouritesIsANewerRecordRatherThanAnAbsentOwner() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var remoteHost=LatticeHost.create();
		var remote=new FavouritesApp(remoteHost);
		remoteHost.configureApplications(List.of(remote));
		var charlie=remote.user(CHARLIE);
		remote.setTime(100);
		charlie.favourites().add(30);
		remote.sync();
		var older=remoteHost.cursor().get();
		remote.setTime(200);
		charlie.favourites().remove(30);
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		localHost.merge(older);
		assertEquals(Sets.empty(),local.items(CHARLIE.getAccountKey()));
		assertNotNull(local.record(CHARLIE.getAccountKey()));
		assertEquals(CVMLong.create(200),local.record(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get());
	}

	@Test void incomingTiesKeepOwnButLocalSameTimeEditsCanPublish() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var remoteHost=LatticeHost.create();
		var remote=new FavouritesApp(remoteHost);
		remoteHost.configureApplications(List.of(remote));
		var charlie=remote.user(CHARLIE);
		remote.setTime(100);
		charlie.favourites().add(30);
		remote.sync();
		var first=remoteHost.cursor().get();
		localHost.merge(first);
		charlie.favourites().add(40); // Deliberate second edit at the same timestamp.
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		remoteHost.merge(first);
		assertEquals(Sets.of(30),local.items(CHARLIE.getAccountKey()),"Incoming ties keep the established value");
		assertEquals(Sets.of(30,40),remote.items(CHARLIE.getAccountKey()),"An intentional local edit replaces its predecessor");

		remote.setTime(101);
		charlie.favourites().remove(30);
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		assertEquals(Sets.of(40),local.items(CHARLIE.getAccountKey()));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get(),"A strictly newer record resolves the tie");
	}

	@Test void existingUserViewsSeeIncomingApplicationMerges() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var alice=local.user(ALICE);
		var anotherApp=new FavouritesApp(localHost);
		var anotherSession=anotherApp.user(ALICE);
		local.setTime(100);
		alice.favourites().add(10);
		anotherApp.setTime(200);
		anotherSession.favourites().add(20);
		anotherApp.sync();
		local.merge(localHost.cursor().get());
		var published=localHost.cursor().get();
		assertEquals(Sets.of(20),alice.favourites().items(),"Existing user views see the newer whole record");
		local.setTime(300);
		alice.favourites().add(30);
		localHost.merge(published);
		assertEquals(Sets.of(20,30),alice.favourites().items(),"A stale incoming record cannot overwrite a newer local edit");
		assertEquals(Sets.of(20,30),local.items(ALICE.getAccountKey()));
		assertEquals(CVMLong.create(300),local.record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(local.cursor().get(),localHost.cursor().get());
	}

	@Test void anIncomingRecordCannotForgeItsOwnerOrItsTimestamp() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var remoteHost=LatticeHost.create();
		var remote=new FavouritesApp(remoteHost);
		remoteHost.configureApplications(List.of(remote));
		var charlie=remote.user(CHARLIE);
		remote.setTime(100);
		charlie.favourites().add(30);
		remote.sync();
		var valid=remote.record(CHARLIE.getAccountKey());
		localHost.merge(remoteHost.cursor().get());

		// A valid Charlie signature does not authorise a value in Alice's slot.
		localHost.merge(LATTICE.zero().assoc(FAVOURITES,OWNERS.zero().assoc(ALICE.getAccountKey(),valid)));
		assertNull(local.record(ALICE.getAccountKey()));

		// The timestamp is inside the signed payload, so changing it invalidates the envelope.
		var changed=valid.getValue().assoc(TIMESTAMP,CVMLong.create(1000));
		var forged=SignedData.create(CHARLIE.getAccountKey(),valid.getSignature(),Ref.get(changed));
		localHost.merge(LATTICE.zero().assoc(FAVOURITES,OWNERS.zero().assoc(CHARLIE.getAccountKey(),forged)));
		assertEquals(valid,local.record(CHARLIE.getAccountKey()));
		assertEquals(CVMLong.create(100),local.record(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get());
	}
}
