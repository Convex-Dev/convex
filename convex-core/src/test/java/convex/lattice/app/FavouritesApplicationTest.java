package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;
import static convex.lattice.app.FavouritesExample.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeHost;
import convex.lattice.LatticeContext;

/**
 * Executable contracts for the composition in {@link FavouritesExample}.
 * Local handles share atomic edits in a staged owner index. Separate hosts exchange
 * complete signed records through application routing; no network runtime is needed.
 * Alice and Bob exercise independent ownership, while Charlie supplies remote updates.
 *
 * <p>Assertions distinguish region working state from published host state. The
 * tests also distinguish concurrent edits through shared storage (which preserve
 * each addition) from merging independent replicas (which selects one whole record).
 * Fixed timestamps expose ties and stale deliveries without relying on wall time.</p>
 *
 * @see FavouritesPersistenceTest
 */
class FavouritesApplicationTest {

	@Test void eachSignedComponentUsesOnlyItsExplicitUserKey() {
		var host=LatticeHost.create();
		// A tripwire proves neither the host nor region supplies a fallback signer.
		var unexpected=new LatticeContext() {
			@Override public <T extends ACell> SignedData<T> sign(AccountKey key,T value) {
				throw new AssertionError("Favourites must resolve its signer in the domain component");
			}
		};
		host.setContext(unexpected);
		var app=new FavouritesApp(host);
		app.favourites().setContext(unexpected);
		var alice=app.favourites().user(ALICE);
		var bob=app.favourites().user(BOB);
		alice.add(10);
		bob.add(20);
		alice.add(30);
		var aliceRecord=app.favourites().record(ALICE.getAccountKey());
		var bobRecord=app.favourites().record(BOB.getAccountKey());
		assertEquals(ALICE.getAccountKey(),aliceRecord.getAccountKey());
		assertEquals(BOB.getAccountKey(),bobRecord.getAccountKey());
		assertTrue(aliceRecord.checkSignature());
		assertTrue(bobRecord.checkSignature());
		app.sync();
		assertEquals(aliceRecord,host.cursor().get(FAVOURITES,ALICE.getAccountKey()));
		assertEquals(bobRecord,host.cursor().get(FAVOURITES,BOB.getAccountKey()));
	}

	/** Absence and unchanged edits are inert; clearing an existing set is a real signed edit. */
	@Test void embeddedSetEditsStampAndSignOnlyWhenItemsChange() {
		var host=LatticeHost.create();
		var app=new FavouritesApp(host);
		var alice=app.favourites().user(ALICE);
		assertTrue(alice.items.isEmpty());
		assertFalse(alice.items.remove(CVMLong.ONE));
		alice.items.clear();
		app.sync();
		assertTrue(host.cursor().get().isEmpty(),"opening or clearing an absent set creates no record");
		// The first changed item creates the record, timestamp and signature together.
		app.favourites().setTime(100);
		assertTrue(alice.items.add(CVMLong.ONE));
		var signed=app.favourites().record(ALICE.getAccountKey());
		assertTrue(signed.checkSignature());
		assertEquals(CVMLong.create(100),signed.getValue().get(TIMESTAMP));
		app.favourites().setTime(200);
		assertFalse(alice.items.add(CVMLong.ONE));
		assertFalse(alice.items.remove(CVMLong.TWO));
		assertSame(signed,app.favourites().record(ALICE.getAccountKey()),"no-op edits do not re-stamp or re-sign");
		// An empty set remains inside a newer record, preserving the fact that it was cleared.
		alice.items.clear();
		var cleared=app.favourites().record(ALICE.getAccountKey());
		assertTrue(cleared.checkSignature());
		assertEquals(CVMLong.create(200),cleared.getValue().get(TIMESTAMP));
		assertTrue(alice.items.isEmpty());
		alice.items.sync();
		assertTrue(host.cursor().get().isEmpty(),"the embedded set cannot publish its containing application");
		app.sync();
		assertEquals(cleared,host.cursor().get(FAVOURITES,ALICE.getAccountKey()));
	}

	/** Adding a new domain field must not accidentally bypass the record's timestamp wrapper. */
	@Test void directDomainCursorEditsAlsoStampBeforeSigning() {
		var app=new FavouritesApp(LatticeHost.create());
		var alice=app.favourites().user(ALICE);
		app.favourites().setTime(123);
		alice.cursor().path(Keyword.intern("note")).set(CVMLong.ONE);
		var record=app.favourites().record(ALICE.getAccountKey());
		assertEquals(CVMLong.create(123),record.getValue().get(TIMESTAMP));
		assertTrue(record.checkSignature());
		app.favourites().setTime(456);
		alice.cursor().set(alice.cursor().get());
		assertSame(record,app.favourites().record(ALICE.getAccountKey()));
	}

	/** A user handle is a path view, so opening another handle needs neither a cache nor reconciliation. */
	@Test void openingTheSameUserTwiceSharesSignedEdits() {
		var host=LatticeHost.create();
		var app=new FavouritesApp(host);
		host.configureApplications(List.of(app));
		var first=app.favourites().user(ALICE);
		var second=app.favourites().user(ALICE);
		assertNotSame(first,second,"User handles are distinct, not cached components");
		app.favourites().setTime(100);
		first.add(10);
		assertEquals(Sets.of(10),second.items(),"Repeated user views share edits immediately");
		assertTrue(app.favourites().record(ALICE.getAccountKey()).checkSignature(),"The first edit is already signed");
		app.favourites().setTime(200);
		second.add(20);
		second.remove(10);
		assertEquals(Sets.of(20),first.items());
		assertEquals(Sets.of(20),app.favourites().user(ALICE).items());
		assertEquals(Index.<Keyword,ACell>none(),host.cursor().get(),"Only the application decides when to publish to the host");
		assertEquals(Sets.of(20),app.favourites().items(ALICE.getAccountKey()));
		assertEquals(CVMLong.create(200),app.favourites().record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		assertTrue(app.favourites().record(ALICE.getAccountKey()).checkSignature());
		app.sync();
		assertEquals(app.favourites().cursor().get(),host.cursor().get().get(FAVOURITES));
	}

	/**
	 * Writers share one atomic record: retries preserve the other writer's fields.
	 * This tests local contention, not a union merge between independent replicas.
	 */
	@Test void concurrentUserViewsPreserveEveryAddition() throws Exception {
		try (var executor=Executors.newFixedThreadPool(2)) {
			var host=LatticeHost.create();
			var app=new FavouritesApp(host);
			host.configureApplications(List.of(app));
			app.favourites().setTime(100);
			var first=app.favourites().user(ALICE);
			var second=app.favourites().user(ALICE);
			var firstWriter=executor.submit(() -> {
				for (int i=0;i<20;i++) first.add(i);
			});
			var secondWriter=executor.submit(() -> {
				for (int i=20;i<40;i++) second.add(i);
			});
			firstWriter.get(10,TimeUnit.SECONDS);
			secondWriter.get(10,TimeUnit.SECONDS);
			// Both futures have completed; the timeout bounds failure rather than scheduling the writers.
			assertEquals(40,first.items().count());
			assertEquals(first.items(),second.items());
			assertEquals(first.items(),app.favourites().items(ALICE.getAccountKey()));
			assertTrue(app.favourites().record(ALICE.getAccountKey()).checkSignature());
			assertEquals(CVMLong.create(100),app.favourites().record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
			assertEquals(Index.<Keyword,ACell>none(),host.cursor().get());
			app.sync();
			assertEquals(app.favourites().cursor().get(),host.cursor().get().get(FAVOURITES));
		}
	}

	/** Distinct timestamps converge despite reordering and replay, without Charlie's key at the receiver. */
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
		local.favourites().setTime(500);
		var alice=local.favourites().user(ALICE);
		var bob=local.favourites().user(BOB);
		alice.add(10);
		bob.add(20);
		local.sync();
		replicaHost.merge(localHost.cursor().get());
		replica.sync();

		var charlie=remote.favourites().user(CHARLIE);
		remote.favourites().setTime(100);
		charlie.add(30);
		charlie.add(40);
		remote.sync();
		var older=remoteHost.cursor().get();
		remote.favourites().setTime(200);
		charlie.remove(30);
		remote.sync();
		var newer=remoteHost.cursor().get();

		// Opposite receiver clocks expose accidental re-stamping of incoming records.
		local.favourites().setTime(10000);
		localHost.merge(older);
		localHost.merge(newer);
		replica.favourites().setTime(1);
		replicaHost.merge(newer);
		replicaHost.merge(older);
		localHost.merge(older); // Replays cannot resurrect the removed item.
		assertNull(localHost.cursor().get(FAVOURITES,CHARLIE.getAccountKey()),
			"Incoming merges wait for application publication");
		assertEquals(local.favourites().cursor().get(),replica.favourites().cursor().get());
		assertEquals(Sets.of(40),local.favourites().items(CHARLIE.getAccountKey()));
		assertEquals(remote.favourites().record(CHARLIE.getAccountKey()),local.favourites().record(CHARLIE.getAccountKey()),
			"The application adopts Charlie's original signed record without his private key");
		assertEquals(CVMLong.create(200),local.favourites().record(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		assertEquals(Sets.of(10),local.favourites().items(ALICE.getAccountKey()));
		assertEquals(Sets.of(20),local.favourites().items(BOB.getAccountKey()));
		assertTrue(local.favourites().record(CHARLIE.getAccountKey()).checkSignature());
		local.sync();
		replica.sync();
		assertEquals(local.favourites().cursor().get(),localHost.cursor().get().get(FAVOURITES));
		assertEquals(localHost.cursor().get(),replicaHost.cursor().get());
	}

	/** Retaining the signed empty record prevents an old non-empty snapshot resurrecting favourites. */
	@Test void clearingFavouritesIsANewerRecordRatherThanAnAbsentOwner() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var remoteHost=LatticeHost.create();
		var remote=new FavouritesApp(remoteHost);
		remoteHost.configureApplications(List.of(remote));
		var charlie=remote.favourites().user(CHARLIE);
		remote.favourites().setTime(100);
		charlie.add(30);
		remote.sync();
		var older=remoteHost.cursor().get();
		remote.favourites().setTime(200);
		charlie.remove(30);
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		localHost.merge(older);
		assertEquals(Sets.empty(),local.favourites().items(CHARLIE.getAccountKey()));
		assertNotNull(local.favourites().record(CHARLIE.getAccountKey()));
		assertEquals(CVMLong.create(200),local.favourites().record(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get());
	}

	/**
	 * Equal timestamps deliberately prefer each receiver's established value.
	 * A local edit can still publish, but replica convergence requires a newer timestamp.
	 */
	@Test void incomingTiesKeepOwnButLocalSameTimeEditsCanPublish() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var remoteHost=LatticeHost.create();
		var remote=new FavouritesApp(remoteHost);
		remoteHost.configureApplications(List.of(remote));
		var charlie=remote.favourites().user(CHARLIE);
		remote.favourites().setTime(100);
		charlie.add(30);
		remote.sync();
		var first=remoteHost.cursor().get();
		localHost.merge(first);
		charlie.add(40); // Deliberate second edit at the same timestamp.
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		remoteHost.merge(first);
		assertEquals(Sets.of(30),local.favourites().items(CHARLIE.getAccountKey()),"Incoming ties keep the established value");
		assertEquals(Sets.of(30,40),remote.favourites().items(CHARLIE.getAccountKey()),"An intentional local edit replaces its predecessor");

		remote.favourites().setTime(101);
		charlie.remove(30);
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		assertEquals(Sets.of(40),local.favourites().items(CHARLIE.getAccountKey()));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get(),"A strictly newer record resolves the tie");
	}

	/** Long-lived handles read accepted incoming state and base subsequent edits on that same record. */
	@Test void existingUserViewsSeeIncomingApplicationMerges() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var alice=local.favourites().user(ALICE);
		// A separate region instance models another session with its own staged copy.
		var anotherApp=new FavouritesApp(localHost);
		var anotherSession=anotherApp.favourites().user(ALICE);
		local.favourites().setTime(100);
		alice.add(10);
		anotherApp.favourites().setTime(200);
		anotherSession.add(20);
		anotherApp.sync();
		local.merge(localHost.cursor().get());
		var published=localHost.cursor().get();
		assertEquals(Sets.of(20),alice.items(),"Existing user views see the newer whole record");
		local.favourites().setTime(300);
		alice.add(30);
		localHost.merge(published);
		assertEquals(Sets.of(20,30),alice.items(),"A stale incoming record cannot overwrite a newer local edit");
		assertEquals(Sets.of(20,30),local.favourites().items(ALICE.getAccountKey()));
		assertEquals(CVMLong.create(300),local.favourites().record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(local.favourites().cursor().get(),localHost.cursor().get().get(FAVOURITES));
	}

	/** Owner authorisation and payload integrity are separate checks, both enforced on incoming state. */
	@Test void anIncomingRecordCannotForgeItsOwnerOrItsTimestamp() {
		var localHost=LatticeHost.create();
		var local=new FavouritesApp(localHost);
		localHost.configureApplications(List.of(local));
		var remoteHost=LatticeHost.create();
		var remote=new FavouritesApp(remoteHost);
		remoteHost.configureApplications(List.of(remote));
		var charlie=remote.favourites().user(CHARLIE);
		remote.favourites().setTime(100);
		charlie.add(30);
		remote.sync();
		var valid=remote.favourites().record(CHARLIE.getAccountKey());
		localHost.merge(remoteHost.cursor().get());

		// A valid Charlie signature does not authorise a value in Alice's slot.
		localHost.merge(Index.of(FAVOURITES,Index.of(ALICE.getAccountKey(),valid)));
		assertNull(local.favourites().record(ALICE.getAccountKey()));

		// The timestamp is inside the signed payload, so changing it invalidates the envelope.
		var changed=valid.getValue().assoc(TIMESTAMP,CVMLong.create(1000));
		var forged=SignedData.create(CHARLIE.getAccountKey(),valid.getSignature(),Ref.get(changed));
		localHost.merge(Index.of(FAVOURITES,Index.of(CHARLIE.getAccountKey(),forged)));
		assertEquals(valid,local.favourites().record(CHARLIE.getAccountKey()));
		assertEquals(CVMLong.create(100),local.favourites().record(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get());
	}

	/** Explicitly sharing the region shares edits and its clock; duplicate delivery remains idempotent. */
	@Test void twoApplicationsCanShareOneFavouritesWorkspace() {
		var host=LatticeHost.create();
		var first=new FavouritesApp(host);
		var second=new FavouritesApp(host,first.favourites());
		host.configureApplications(List.of(first,second));
		assertSame(first.favourites(),second.favourites());
		var alice=first.favourites().user(ALICE);
		var otherView=second.favourites().user(ALICE);
		first.favourites().setTime(100);
		alice.add(10);
		assertEquals(Sets.of(10),otherView.items());
		second.favourites().setTime(200);
		otherView.add(20);
		assertEquals(Sets.of(10,20),alice.items());
		assertTrue(host.cursor().get().isEmpty(),"sharing the workspace does not publish edits");
		second.sync();
		var published=host.cursor().get();
		assertEquals(first.favourites().cursor().get(),published.get(FAVOURITES));
		assertEquals(CVMLong.create(200),first.favourites().record(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		host.merge(published); // Both registered applications deliver to the same region.
		first.sync();
		assertSame(published,host.cursor().get(),"duplicate delivery and publication leave the value unchanged");
	}

	/** Serving one region grants no merge or publication responsibility for its host siblings. */
	@Test void favouritesPublicationPreservesUnrelatedHostChanges() {
		Keyword other=Keyword.intern("other");
		var host=LatticeHost.create(Index.<Keyword,ACell>of(other,Sets.of(1)),null);
		var app=new FavouritesApp(host);
		host.configureApplications(List.of(app));
		app.favourites().setTime(100);
		app.favourites().user(ALICE).add(10);
		host.cursor().update(value -> value.assoc(other,Sets.of(2)));
		host.merge(Index.of(other,Sets.of(3))); // Not one of this application's interests.
		app.sync();
		assertEquals(Sets.of(2),host.cursor().get().get(other));
		assertEquals(app.favourites().record(ALICE.getAccountKey()),host.cursor().get(FAVOURITES,ALICE.getAccountKey()));
		app.favourites().user(ALICE).add(20);
		host.cursor().update(value -> value.dissoc(other));
		app.sync();
		assertFalse(host.cursor().get().containsKey(other),"publishing favourites cannot resurrect a removed sibling");
	}

	/** Root routing and owner-path delivery enforce the same complete-envelope merge boundary. */
	@Test void aSignedOwnerRecordMeansTheSameAtARootOrAtItsPath() {
		var sourceHost=LatticeHost.create();
		var source=new FavouritesApp(sourceHost);
		source.favourites().setTime(100);
		source.favourites().user(CHARLIE).add(30);
		source.sync();
		var rootHost=LatticeHost.create();
		var pathHost=LatticeHost.create();
		var rootApp=new FavouritesApp(rootHost);
		var pathApp=new FavouritesApp(pathHost);
		rootHost.configureApplications(List.of(rootApp));
		pathHost.configureApplications(List.of(pathApp));
		rootHost.merge(sourceHost.cursor().get());
		pathHost.mergeAt(source.favourites().record(CHARLIE.getAccountKey()),FAVOURITES,CHARLIE.getAccountKey());
		assertEquals(rootApp.favourites().cursor().get(),pathApp.favourites().cursor().get());
		assertTrue(rootHost.cursor().get().isEmpty());
		assertTrue(pathHost.cursor().get().isEmpty());
		rootApp.sync();
		pathApp.sync();
		assertEquals(sourceHost.cursor().get(),rootHost.cursor().get());
		assertEquals(rootHost.cursor().get(),pathHost.cursor().get());
	}
}
