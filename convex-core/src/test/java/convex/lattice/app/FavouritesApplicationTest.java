package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;
import static convex.lattice.app.FavouritesExample.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeHost;

/** Alice and Bob edit locally; Charlie publishes from a separate in-memory host. */
class FavouritesApplicationTest {

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
