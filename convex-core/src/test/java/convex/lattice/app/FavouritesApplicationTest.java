package convex.lattice.app;

import static org.junit.jupiter.api.Assertions.*;
import static convex.lattice.app.FavouritesExample.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeHost;

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

	/** Relay policy understands ordering and authorship, not every field in the signed payload. */
	@Test void unfamiliarPayloadsRelayIntactThroughRootAndOwnerDeliveries() {
		var items=Keyword.intern("items");
		var extension=Keyword.intern("future-field");
		for (ACell value:List.of(CVMLong.ONE,Maps.of("future","representation"),Sets.of(1,"future-id"))) {
			for (boolean atOwner:new boolean[] {false,true}) {
				var host=LatticeHost.create();
				var appClock=new AtomicReference<>(CVMLong.ZERO);
				var app=new FavouritesApp(host,appClock::get);
				host.addApplication(app);
				AHashMap<Keyword,ACell> payload=Maps.of(TIMESTAMP,CVMLong.ONE,items,value,extension,Maps.of("version",2));
				var envelope=CHARLIE.signData(payload);
				if (atOwner) host.mergeAt(envelope,FAVOURITES,CHARLIE.getAccountKey());
				else host.merge(Index.of(FAVOURITES,Index.of(CHARLIE.getAccountKey(),envelope)));
				assertSame(envelope,app.component().get(CHARLIE.getAccountKey()));
				assertSame(value,app.items(CHARLIE.getAccountKey()));
				app.sync();

				var nextHost=LatticeHost.create();
				var nextClock=new AtomicReference<>(CVMLong.ZERO);
				var next=new FavouritesApp(nextHost,nextClock::get);
				nextHost.addApplication(next);
				nextHost.merge(host.cursor().get());
				next.sync();
				assertSame(envelope,next.component().get(CHARLIE.getAccountKey()));
				assertEquals(host.cursor().get(),nextHost.cursor().get());
			}
		}
	}

	/** A local edit interprets only the collection operation and retains unfamiliar members and fields. */
	@Test void numericItemEditsPreserveUnfamiliarContent() {
		var host=LatticeHost.create();
		var appClock=new AtomicReference<>(CVMLong.ZERO);
		var app=new FavouritesApp(host,appClock::get);
		var region=app.component();
		var items=Keyword.intern("items");
		var extension=Keyword.intern("future-field");
		var extra=Maps.of("version",2);
		var members=Sets.<ACell>of(1,"future-id").include(extra);
		AHashMap<Keyword,ACell> payload=Maps.of(TIMESTAMP,CVMLong.ONE,items,members,extension,extra);
		region.mergeAt(ALICE.signData(payload),ALICE.getAccountKey());
		var alice=app.user(ALICE);
		appClock.set(CVMLong.TWO);
		alice.add(2);
		alice.remove(1);
		assertEquals(members.include(CVMLong.TWO).exclude(CVMLong.ONE),alice.items());
		assertTrue(alice.items().contains(Strings.create("future-id")));
		for (ACell member:alice.items()) assertNotNull(member);
		var edited=region.get(ALICE.getAccountKey());
		assertSame(extra,edited.getValue().get(extension));
		assertEquals(CVMLong.TWO,edited.getValue().get(TIMESTAMP));
		assertTrue(edited.checkSignature());
	}

	/** Inability to edit a field must neither discard nor re-sign the relayed record. */
	@Test void setOperationsLeaveAnUnfamiliarFieldIntact() {
		var host=LatticeHost.create();
		var appClock=new AtomicReference<>(CVMLong.ZERO);
		var app=new FavouritesApp(host,appClock::get);
		var region=app.component();
		var items=Keyword.intern("items");
		AHashMap<Keyword,ACell> payload=Maps.of(TIMESTAMP,CVMLong.ONE,items,Maps.of("future","items"));
		var envelope=ALICE.signData(payload);
		region.mergeAt(envelope,ALICE.getAccountKey());
		var alice=app.user(ALICE);
		assertThrows(IllegalStateException.class,alice::items);
		assertThrows(IllegalStateException.class,() -> alice.add(2));
		assertThrows(IllegalStateException.class,() -> alice.remove(1));
		assertThrows(IllegalStateException.class,alice.itemSet::clear);
		assertSame(envelope,region.get(ALICE.getAccountKey()));
		assertEquals(payload.get(items),app.items(ALICE.getAccountKey()));
		app.sync();
		assertSame(envelope,region.get(ALICE.getAccountKey()));
	}

	@Test void eachSignedComponentUsesOnlyItsExplicitUserKey() {
		var host=LatticeHost.create();
		var appClock=new AtomicReference<>(CVMLong.ZERO);
		var app=new FavouritesApp(host,appClock::get);
		var alice=app.user(ALICE);
		var bob=app.user(BOB);
		alice.add(10);
		bob.add(20);
		alice.add(30);
		var aliceRecord=app.component().get(ALICE.getAccountKey());
		var bobRecord=app.component().get(BOB.getAccountKey());
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
		var appClock=new AtomicReference<>(CVMLong.ZERO);
		var app=new FavouritesApp(host,appClock::get);
		var alice=app.user(ALICE);
		assertTrue(alice.itemSet.isEmpty());
		assertFalse(alice.itemSet.remove(CVMLong.ONE));
		alice.itemSet.clear();
		app.sync();
		assertTrue(host.cursor().get().isEmpty(),"opening or clearing an absent set creates no record");
		// The first changed item creates the record, timestamp and signature together.
		appClock.set(CVMLong.create(100));
		assertTrue(alice.itemSet.add(CVMLong.ONE));
		var signed=app.component().get(ALICE.getAccountKey());
		assertTrue(signed.checkSignature());
		assertEquals(CVMLong.create(100),signed.getValue().get(TIMESTAMP));
		appClock.set(CVMLong.create(200));
		assertFalse(alice.itemSet.add(CVMLong.ONE));
		assertFalse(alice.itemSet.remove(CVMLong.TWO));
		assertSame(signed,app.component().get(ALICE.getAccountKey()),"no-op edits do not re-stamp or re-sign");
		// An empty set remains inside a newer record, preserving the fact that it was cleared.
		alice.itemSet.clear();
		var cleared=app.component().get(ALICE.getAccountKey());
		assertTrue(cleared.checkSignature());
		assertEquals(CVMLong.create(200),cleared.getValue().get(TIMESTAMP));
		assertTrue(alice.itemSet.isEmpty());
		alice.itemSet.sync();
		assertTrue(host.cursor().get().isEmpty(),"the embedded set cannot publish its containing application");
		app.sync();
		assertEquals(cleared,host.cursor().get(FAVOURITES,ALICE.getAccountKey()));
	}

	/** Adding a new domain field must not accidentally bypass the record's timestamp wrapper. */
	@Test void directDomainCursorEditsAlsoStampBeforeSigning() {
		var host=LatticeHost.create();
		var appClock=new AtomicReference<>(CVMLong.ZERO);
		var app=new FavouritesApp(host,appClock::get);
		var alice=app.user(ALICE);
		appClock.set(CVMLong.create(123));
		assertNull(alice.sync(),"Sync returns the absent bound value without materialising editing defaults");
		assertEquals(Maps.empty(),alice.cursor().get());
		assertNull(app.component().get(ALICE.getAccountKey()),"Reading the default view creates no envelope");
		alice.cursor().path(Keyword.intern("note")).set(CVMLong.ONE);
		var record=app.component().get(ALICE.getAccountKey());
		assertEquals(CVMLong.create(123),record.getValue().get(TIMESTAMP));
		assertTrue(record.checkSignature());
		appClock.set(CVMLong.create(456));
		alice.cursor().set(alice.cursor().get());
		assertSame(record,app.component().get(ALICE.getAccountKey()));
	}

	/** Signed delivery accepts a payload the domain view cannot interpret, without authoring a timestamp. */
	@Test void signedDeliveryPreservesUnfamiliarPayloadWithoutRunningTheClock() {
		var host=LatticeHost.create();
		var app=new FavouritesApp(host,() -> {
			throw new AssertionError("Incoming delivery must not author a timestamp");
		});
		var region=app.component();
		var alice=app.user(ALICE);
		AHashMap<Keyword,ACell> payload=Maps.of(TIMESTAMP,CVMLong.ONE,
			Keyword.intern("items"),Maps.of("future","representation"));
		var envelope=ALICE.signData(payload);
		assertSame(payload,alice.merge(envelope));
		assertSame(envelope,region.get(ALICE.getAccountKey()));
		assertSame(payload,alice.sync());
		assertTrue(host.cursor().get().isEmpty());
	}

	/** A user handle is a path view, so opening another handle needs neither a cache nor reconciliation. */
	@Test void openingTheSameUserTwiceSharesSignedEdits() {
		var host=LatticeHost.create();
		var appClock=new AtomicReference<>(CVMLong.ZERO);
		var app=new FavouritesApp(host,appClock::get);
		host.addApplication(app);
		var first=app.user(ALICE);
		var second=app.user(ALICE);
		assertNotSame(first,second,"User handles are distinct, not cached components");
		appClock.set(CVMLong.create(100));
		first.add(10);
		assertEquals(Sets.of(10),second.items(),"Repeated user views share edits immediately");
		assertTrue(app.component().get(ALICE.getAccountKey()).checkSignature(),"The first edit is already signed");
		appClock.set(CVMLong.create(200));
		second.add(20);
		second.remove(10);
		assertEquals(Sets.of(20),first.items());
		assertEquals(Sets.of(20),app.user(ALICE).items());
		assertEquals(Index.<Keyword,ACell>none(),host.cursor().get(),"Only the application decides when to publish to the host");
		assertEquals(Sets.of(20),app.items(ALICE.getAccountKey()));
		assertEquals(CVMLong.create(200),app.component().get(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		assertTrue(app.component().get(ALICE.getAccountKey()).checkSignature());
		app.sync();
		assertEquals(app.component().cursor().get(),host.cursor().get().get(FAVOURITES));
	}

	/**
	 * Writers share one atomic record: retries preserve the other writer's fields.
	 * This tests local contention, not a union merge between independent replicas.
	 */
	@Test void concurrentUserViewsPreserveEveryAddition() throws Exception {
		try (var executor=Executors.newFixedThreadPool(2)) {
			var host=LatticeHost.create();
			var appClock=new AtomicReference<>(CVMLong.ZERO);
			var app=new FavouritesApp(host,appClock::get);
			host.addApplication(app);
			appClock.set(CVMLong.create(100));
			var first=app.user(ALICE);
			var second=app.user(ALICE);
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
			assertEquals(first.items(),app.items(ALICE.getAccountKey()));
			assertTrue(app.component().get(ALICE.getAccountKey()).checkSignature());
			assertEquals(CVMLong.create(100),app.component().get(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
			assertEquals(Index.<Keyword,ACell>none(),host.cursor().get());
			app.sync();
			assertEquals(app.component().cursor().get(),host.cursor().get().get(FAVOURITES));
		}
	}

	/** Distinct timestamps converge despite reordering and replay, without Charlie's key at the receiver. */
	@Test void charliesNewerRecordWinsRegardlessOfDeliveryOrder() {
		var localHost=LatticeHost.create();
		var localClock=new AtomicReference<>(CVMLong.ZERO);
		var local=new FavouritesApp(localHost,localClock::get);
		localHost.addApplication(local);
		var remoteHost=LatticeHost.create();
		var remoteClock=new AtomicReference<>(CVMLong.ZERO);
		var remote=new FavouritesApp(remoteHost,remoteClock::get);
		remoteHost.addApplication(remote);
		var replicaHost=LatticeHost.create();
		var replicaClock=new AtomicReference<>(CVMLong.ZERO);
		var replica=new FavouritesApp(replicaHost,replicaClock::get);
		replicaHost.addApplication(replica);
		localClock.set(CVMLong.create(500));
		var alice=local.user(ALICE);
		var bob=local.user(BOB);
		alice.add(10);
		bob.add(20);
		local.sync();
		replicaHost.merge(localHost.cursor().get());
		replica.sync();

		var charlie=remote.user(CHARLIE);
		remoteClock.set(CVMLong.create(100));
		charlie.add(30);
		charlie.add(40);
		remote.sync();
		var older=remoteHost.cursor().get();
		remoteClock.set(CVMLong.create(200));
		charlie.remove(30);
		remote.sync();
		var newer=remoteHost.cursor().get();

		// Opposite receiver clocks expose accidental re-stamping of incoming records.
		localClock.set(CVMLong.create(10000));
		localHost.merge(older);
		localHost.merge(newer);
		replicaClock.set(CVMLong.create(1));
		replicaHost.merge(newer);
		replicaHost.merge(older);
		localHost.merge(older); // Replays cannot resurrect the removed item.
		assertNull(localHost.cursor().get(FAVOURITES,CHARLIE.getAccountKey()),
			"Incoming merges wait for application publication");
		assertEquals(local.component().cursor().get(),replica.component().cursor().get());
		assertEquals(Sets.of(40),local.items(CHARLIE.getAccountKey()));
		assertEquals(remote.component().get(CHARLIE.getAccountKey()),local.component().get(CHARLIE.getAccountKey()),
			"The application adopts Charlie's original signed record without his private key");
		assertEquals(CVMLong.create(200),local.component().get(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		assertEquals(Sets.of(10),local.items(ALICE.getAccountKey()));
		assertEquals(Sets.of(20),local.items(BOB.getAccountKey()));
		assertTrue(local.component().get(CHARLIE.getAccountKey()).checkSignature());
		local.sync();
		replica.sync();
		assertEquals(local.component().cursor().get(),localHost.cursor().get().get(FAVOURITES));
		assertEquals(localHost.cursor().get(),replicaHost.cursor().get());
	}

	/** Retaining the signed empty record prevents an old non-empty snapshot resurrecting favourites. */
	@Test void clearingFavouritesIsANewerRecordRatherThanAnAbsentOwner() {
		var localHost=LatticeHost.create();
		var localClock=new AtomicReference<>(CVMLong.ZERO);
		var local=new FavouritesApp(localHost,localClock::get);
		localHost.addApplication(local);
		var remoteHost=LatticeHost.create();
		var remoteClock=new AtomicReference<>(CVMLong.ZERO);
		var remote=new FavouritesApp(remoteHost,remoteClock::get);
		remoteHost.addApplication(remote);
		var charlie=remote.user(CHARLIE);
		remoteClock.set(CVMLong.create(100));
		charlie.add(30);
		remote.sync();
		var older=remoteHost.cursor().get();
		remoteClock.set(CVMLong.create(200));
		charlie.remove(30);
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		localHost.merge(older);
		assertEquals(Sets.empty(),local.items(CHARLIE.getAccountKey()));
		assertNotNull(local.component().get(CHARLIE.getAccountKey()));
		assertEquals(CVMLong.create(200),local.component().get(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get());
	}

	/**
	 * Equal timestamps deliberately prefer each receiver's established value.
	 * A local edit can still publish, but replica convergence requires a newer timestamp.
	 */
	@Test void incomingTiesKeepOwnButLocalSameTimeEditsCanPublish() {
		var localHost=LatticeHost.create();
		var localClock=new AtomicReference<>(CVMLong.ZERO);
		var local=new FavouritesApp(localHost,localClock::get);
		localHost.addApplication(local);
		var remoteHost=LatticeHost.create();
		var remoteClock=new AtomicReference<>(CVMLong.ZERO);
		var remote=new FavouritesApp(remoteHost,remoteClock::get);
		remoteHost.addApplication(remote);
		var charlie=remote.user(CHARLIE);
		remoteClock.set(CVMLong.create(100));
		charlie.add(30);
		remote.sync();
		var first=remoteHost.cursor().get();
		localHost.merge(first);
		charlie.add(40); // Deliberate second edit at the same timestamp.
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		remoteHost.merge(first);
		assertEquals(Sets.of(30),local.items(CHARLIE.getAccountKey()),"Incoming ties keep the established value");
		assertEquals(Sets.of(30,40),remote.items(CHARLIE.getAccountKey()),"An intentional local edit replaces its predecessor");

		remoteClock.set(CVMLong.create(101));
		charlie.remove(30);
		remote.sync();
		localHost.merge(remoteHost.cursor().get());
		assertEquals(Sets.of(40),local.items(CHARLIE.getAccountKey()));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get(),"A strictly newer record resolves the tie");
	}

	/** Long-lived handles read accepted incoming state and base subsequent edits on that same record. */
	@Test void existingUserViewsSeeIncomingApplicationMerges() {
		var localHost=LatticeHost.create();
		var localClock=new AtomicReference<>(CVMLong.ZERO);
		var local=new FavouritesApp(localHost,localClock::get);
		localHost.addApplication(local);
		var alice=local.user(ALICE);
		// A separate region instance models another session with its own staged copy.
		var anotherAppClock=new AtomicReference<>(CVMLong.ZERO);
		var anotherApp=new FavouritesApp(localHost,anotherAppClock::get);
		var anotherSession=anotherApp.user(ALICE);
		localClock.set(CVMLong.create(100));
		alice.add(10);
		anotherAppClock.set(CVMLong.create(200));
		anotherSession.add(20);
		anotherApp.sync();
		local.merge(localHost.cursor().get());
		var published=localHost.cursor().get();
		assertEquals(Sets.of(20),alice.items(),"Existing user views see the newer whole record");
		localClock.set(CVMLong.create(300));
		alice.add(30);
		localHost.merge(published);
		assertEquals(Sets.of(20,30),alice.items(),"A stale incoming record cannot overwrite a newer local edit");
		assertEquals(Sets.of(20,30),local.items(ALICE.getAccountKey()));
		assertEquals(CVMLong.create(300),local.component().get(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(local.component().cursor().get(),localHost.cursor().get().get(FAVOURITES));
	}

	/** Owner authorisation and payload integrity are separate checks, both enforced on incoming state. */
	@Test void anIncomingRecordCannotForgeItsOwnerOrItsTimestamp() {
		var localHost=LatticeHost.create();
		var localClock=new AtomicReference<>(CVMLong.ZERO);
		var local=new FavouritesApp(localHost,localClock::get);
		localHost.addApplication(local);
		var remoteHost=LatticeHost.create();
		var remoteClock=new AtomicReference<>(CVMLong.ZERO);
		var remote=new FavouritesApp(remoteHost,remoteClock::get);
		remoteHost.addApplication(remote);
		var charlie=remote.user(CHARLIE);
		remoteClock.set(CVMLong.create(100));
		charlie.add(30);
		remote.sync();
		var valid=remote.component().get(CHARLIE.getAccountKey());
		localHost.merge(remoteHost.cursor().get());

		// A valid Charlie signature does not authorise a value in Alice's slot.
		localHost.merge(Index.of(FAVOURITES,Index.of(ALICE.getAccountKey(),valid)));
		assertNull(local.component().get(ALICE.getAccountKey()));

		// The timestamp is inside the signed payload, so changing it invalidates the envelope.
		var changed=valid.getValue().assoc(TIMESTAMP,CVMLong.create(1000));
		var forged=SignedData.create(CHARLIE.getAccountKey(),valid.getSignature(),Ref.get(changed));
		localHost.merge(Index.of(FAVOURITES,Index.of(CHARLIE.getAccountKey(),forged)));
		assertEquals(valid,local.component().get(CHARLIE.getAccountKey()));
		assertEquals(CVMLong.create(100),local.component().get(CHARLIE.getAccountKey()).getValue().get(TIMESTAMP));
		local.sync();
		assertEquals(remoteHost.cursor().get(),localHost.cursor().get());
	}

	/** Explicitly sharing the region shares edits; both applications use the supplied clock; duplicate delivery remains idempotent. */
	@Test void twoApplicationsCanShareOneFavouritesWorkspace() {
		var host=LatticeHost.create();
		var firstClock=new AtomicReference<>(CVMLong.ZERO);
		var first=new FavouritesApp(host,firstClock::get);
		var second=new FavouritesApp(host,first.component(),firstClock::get);
		host.addApplication(first);
		host.addApplication(second);
		assertSame(first.component(),second.component());
		var alice=first.user(ALICE);
		var otherView=second.user(ALICE);
		firstClock.set(CVMLong.create(100));
		alice.add(10);
		assertEquals(Sets.of(10),otherView.items());
		firstClock.set(CVMLong.create(200));
		otherView.add(20);
		assertEquals(Sets.of(10,20),alice.items());
		assertTrue(host.cursor().get().isEmpty(),"sharing the workspace does not publish edits");
		second.sync();
		var published=host.cursor().get();
		assertEquals(first.component().cursor().get(),published.get(FAVOURITES));
		assertEquals(CVMLong.create(200),first.component().get(ALICE.getAccountKey()).getValue().get(TIMESTAMP));
		host.merge(published); // Both registered applications deliver to the same region.
		first.sync();
		assertSame(published,host.cursor().get(),"duplicate delivery and publication leave the value unchanged");
	}

	/** Serving one region grants no merge or publication responsibility for its host siblings. */
	@Test void favouritesPublicationPreservesUnrelatedHostChanges() {
		Keyword other=Keyword.intern("other");
		var host=LatticeHost.create(Index.<Keyword,ACell>of(other,Sets.of(1)),null);
		var appClock=new AtomicReference<>(CVMLong.ZERO);
		var app=new FavouritesApp(host,appClock::get);
		host.addApplication(app);
		appClock.set(CVMLong.create(100));
		app.user(ALICE).add(10);
		host.cursor().update(value -> value.assoc(other,Sets.of(2)));
		host.merge(Index.of(other,Sets.of(3))); // Not one of this application's interests.
		app.sync();
		assertEquals(Sets.of(2),host.cursor().get().get(other));
		assertEquals(app.component().get(ALICE.getAccountKey()),host.cursor().get(FAVOURITES,ALICE.getAccountKey()));
		app.user(ALICE).add(20);
		host.cursor().update(value -> value.dissoc(other));
		app.sync();
		assertFalse(host.cursor().get().containsKey(other),"publishing favourites cannot resurrect a removed sibling");
	}

	/** Root routing and owner-path delivery enforce the same complete-envelope merge boundary. */
	@Test void aSignedOwnerRecordMeansTheSameAtARootOrAtItsPath() {
		var sourceHost=LatticeHost.create();
		var sourceClock=new AtomicReference<>(CVMLong.ZERO);
		var source=new FavouritesApp(sourceHost,sourceClock::get);
		sourceClock.set(CVMLong.create(100));
		source.user(CHARLIE).add(30);
		source.sync();
		var rootHost=LatticeHost.create();
		var pathHost=LatticeHost.create();
		var rootAppClock=new AtomicReference<>(CVMLong.ZERO);
		var rootApp=new FavouritesApp(rootHost,rootAppClock::get);
		var pathAppClock=new AtomicReference<>(CVMLong.ZERO);
		var pathApp=new FavouritesApp(pathHost,pathAppClock::get);
		rootHost.addApplication(rootApp);
		pathHost.addApplication(pathApp);
		rootHost.merge(sourceHost.cursor().get());
		pathHost.mergeAt(source.component().get(CHARLIE.getAccountKey()),FAVOURITES,CHARLIE.getAccountKey());
		assertEquals(rootApp.component().cursor().get(),pathApp.component().cursor().get());
		assertTrue(rootHost.cursor().get().isEmpty());
		assertTrue(pathHost.cursor().get().isEmpty());
		rootApp.sync();
		pathApp.sync();
		assertEquals(sourceHost.cursor().get(),rootHost.cursor().get());
		assertEquals(rootHost.cursor().get(),pathHost.cursor().get());
	}
}
