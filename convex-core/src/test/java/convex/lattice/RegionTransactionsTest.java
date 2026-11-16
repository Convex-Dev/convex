package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.BoundaryCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Transactions over a region container that manages its regions: isolated to one
 * region, or spanning several, with forked and live regions side by side. Each
 * region's cursor is a transactional boundary, so exclusive work in one region
 * leaves the others alone.
 */
class RegionTransactionsTest {
	private static final Keyword FORKED=Keyword.intern("forked");
	private static final Keyword LIVE=Keyword.intern("live");
	private static final Keyword MORE=Keyword.intern("more");
	private static final Keyword STRICT=Keyword.intern("strict");
	private static final Keyword SIGNED=Keyword.intern("signed");
	private static final Keyword OTHER=Keyword.intern("other");

	private static final AKeyPair OWNER=AKeyPair.createSeeded(1201);
	private static final SignedLattice<ASet<CVMLong>> SIGNING=
		OwnerKeyLattice.create(SetLattice.<CVMLong>create()).signingFor(OWNER.getAccountKey());

	/** A set of numbers that transacts on its own storage. */
	private static final class Members extends LatticeComponent<ASet<CVMLong>>
			implements TransactionalComponent<ASet<CVMLong>,Members> {
		Members(AForkableCursor<ASet<CVMLong>> cursor) { super(SetLattice.create(),cursor); }
		@Override public Members rebind(AForkableCursor<ASet<CVMLong>> cursor) { return new Members(cursor); }
		void add(long n) { cursor().updateAndGet(s -> union(s,n)); }
	}

	/** A transacting component that cannot reconcile changes on both sides. */
	private static final class Unmerged extends ALatticeComponent<ASet<CVMLong>>
			implements TransactionalComponent<ASet<CVMLong>,Unmerged> {
		Unmerged(AForkableCursor<ASet<CVMLong>> cursor) { super(cursor); }
		@Override public Unmerged rebind(AForkableCursor<ASet<CVMLong>> cursor) { return new Unmerged(cursor); }
		void add(long n) { cursor().updateAndGet(s -> union(s,n)); }
	}

	/** Regions with one forked, two live, one strict and one signed region, transacting as a whole. */
	private static final class App extends Regions
			implements TransactionalComponent<Index<Keyword,ACell>,App> {
		App(AForkableCursor<Index<Keyword,ACell>> cursor) {
			super(cursor);
			create(FORKED,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c.fork()));
			create(LIVE,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c));
			create(MORE,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c));
			create(STRICT,(AForkableCursor<ASet<CVMLong>> c) -> new Unmerged(c));
			create(SIGNED,(AForkableCursor<SignedData<ASet<CVMLong>>> c) -> new SignedSetComponent<>(SIGNING,c,OWNER::signData));
		}
		private App(AForkableCursor<Index<Keyword,ACell>> cursor,App source) { super(cursor,source); }
		@Override public App rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new App(cursor,this); }
		Members forked() { return registered(FORKED); }
		Members live() { return registered(LIVE); }
		Members more() { return registered(MORE); }
		Unmerged strict() { return registered(STRICT); }
		SignedComponent<ASet<CVMLong>> signed() { return registered(SIGNED); }
	}

	private static ASet<CVMLong> union(ASet<CVMLong> set,long n) {
		ASet<CVMLong> one=Sets.of(n);
		return (set==null)?one:set.includeAll(one);
	}

	private static Root<Index<Keyword,ACell>> emptyRoot() { return new Root<>(Index.<Keyword,ACell>none()); }

	private static ACell at(Root<Index<Keyword,ACell>> root,Keyword key) { return root.get().get(key); }

	private static ACell signedPayload(Root<Index<Keyword,ACell>> root) {
		SignedData<?> envelope=(SignedData<?>)at(root,SIGNED);
		assertTrue(envelope.checkSignature());
		return envelope.getValue();
	}

	@Test void aTransactionOnOneRegionIsIsolatedToIt() {
		// A transaction needs its place to exist
		var root=new Root<>(Index.<Keyword,ACell>of(FORKED,Sets.empty(),LIVE,Sets.empty()));
		var app=new App(root);
		app.forked().execute(m -> m.add(1));
		assertEquals(Sets.of(1),app.forked().value(),"committed to the region's own working copy");
		assertEquals(Sets.empty(),at(root,FORKED),"not in the container until the region syncs");
		app.live().execute(m -> m.add(2));
		assertEquals(Sets.of(2),at(root,LIVE),"a live region commits straight into the container");
		app.sync();
		assertEquals(Sets.of(1),at(root,FORKED));
	}

	@Test void aSpanningTransactionCommitsEveryRegionTogether() {
		var root=emptyRoot();
		var app=new App(root);
		app.execute(view -> {
			view.forked().add(1);
			view.live().add(2);
			view.signed().cursor().updateAndGet(s -> union(s,3));
			assertTrue(root.get().isEmpty(),"private until commit");
		});
		assertEquals(Sets.of(1),at(root,FORKED));
		assertEquals(Sets.of(2),at(root,LIVE));
		assertEquals(Sets.of(3),signedPayload(root),"signed once, as the draft commits");
		assertNull(app.forked().value(),"this instance's regions took no part");
	}

	@Test void aFailedSpanningTransactionLeavesNothingAnywhere() {
		var root=emptyRoot();
		var app=new App(root);
		app.forked().add(9);
		assertThrows(IllegalStateException.class,() -> app.execute(view -> {
			view.forked().add(1);
			view.live().add(2);
			throw new IllegalStateException();
		}));
		assertTrue(root.get().isEmpty());
		assertEquals(Sets.of(9),app.forked().value(),"pending region work is untouched");
	}

	@Test void pendingRegionWorkMergesWithASpanningCommitAtItsNextSync() {
		var root=emptyRoot();
		var app=new App(root);
		app.forked().add(1);
		app.signed().cursor().updateAndGet(s -> union(s,1));
		app.execute(view -> {
			assertNull(view.forked().value(),"a spanning transaction sees what regions have synced");
			view.forked().add(2);
			view.signed().cursor().updateAndGet(s -> union(s,2));
		});
		assertEquals(Sets.of(2),at(root,FORKED));
		app.sync();
		assertEquals(Sets.of(1,2),at(root,FORKED),"both survive");
		assertEquals(Sets.of(1,2),signedPayload(root),"both survive a signing boundary");
	}

	@Test void nestedTransactionsShareLiveChildCommitsWithoutSync() {
		var root=new Root<>(Index.<Keyword,ACell>of(LIVE,Sets.empty()));
		var app=new App(root);
		var before=root.get();
		app.execute(outer -> {
			Members child=outer.live();
			child.execute(m -> m.add(1));
			assertEquals(Sets.of(1),outer.value().get(LIVE),"the child commits directly into the parent's draft");
			assertEquals(Sets.of(1),child.value(),"the existing child sees its commit through the boundary cursor");
			assertSame(before,root.get(),"the child commit remains private to the outer transaction");

			outer.execute(inner -> {
				assertEquals(Sets.of(1),inner.live().value(),"a nested parent sees the preceding child commit without sync");
				inner.live().execute(m -> m.add(2));
				assertEquals(Sets.of(1,2),inner.value().get(LIVE),"the nested child commits into its immediate parent's draft");
				assertEquals(Sets.of(1),child.value(),"the nested parent remains isolated until it commits");
				assertSame(before,root.get());
			});
			assertEquals(Sets.of(1,2),outer.value().get(LIVE),"the nested parent commits into the outer draft");
			assertEquals(Sets.of(1,2),child.value(),"the retained child immediately sees the nested parent's commit");
			child.execute(m -> {
				assertEquals(Sets.of(1,2),m.value(),"a subsequent child transaction starts from the updated parent");
				m.add(3);
			});
			assertEquals(Sets.of(1,2,3),outer.value().get(LIVE));
			assertSame(before,root.get(),"no nested commit publishes outside the outer transaction");
		});
		assertEquals(Sets.of(1,2,3),at(root,LIVE),"the outer commit publishes all nested changes together");
	}

	@Test void nestedParentTransactionsSeeForkedChildCommitsOnlyAfterSync() {
		var root=new Root<>(Index.<Keyword,ACell>of(FORKED,Sets.empty()));
		var app=new App(root);
		var before=root.get();
		app.execute(outer -> {
			Members child=outer.forked();
			child.execute(m -> m.add(1));
			assertEquals(Sets.of(1),child.value(),"committed into the child's fork");
			assertEquals(Sets.empty(),outer.value().get(FORKED),"not yet published into the outer transaction");
			outer.execute(inner -> {
				assertEquals(Sets.empty(),inner.forked().value(),"a nested parent sees only the child's published state");
			});

			child.sync();
			assertEquals(Sets.of(1),outer.value().get(FORKED));
			outer.execute(inner -> {
				assertEquals(Sets.of(1),inner.forked().value(),"the child's sync makes its commit visible");
				inner.forked().execute(m -> m.add(2));
			});
			assertEquals(Sets.of(1,2),outer.value().get(FORKED),"nested completion syncs its managed child into the outer transaction");
			assertEquals(Sets.of(1),child.value(),"the existing child fork remains independent of its changed destination");

			child.sync();
			assertEquals(Sets.of(1,2),child.value(),"sync adopts the nested parent's commit");
			assertSame(before,root.get(),"child syncs and nested commits remain private until the outer commit");
		});
		assertEquals(Sets.of(1,2),at(root,FORKED),"outer completion commits the combined state");
	}

	@Test void aRetriedAttemptLeavesNothingBehind() {
		var attempts=new AtomicInteger();
		var root=emptyRoot();
		var app=new App(root);
		app.execute(view -> {
			int attempt=attempts.incrementAndGet();
			view.forked().add(attempt);
			view.strict().add(attempt);
			// A concurrent change to a region this attempt also changed, which cannot merge, forces another attempt
			if (attempt==1) app.strict().add(7);
		});
		assertEquals(2,attempts.get());
		assertEquals(Sets.of(2),at(root,FORKED));
		assertEquals(Sets.of(2,7),at(root,STRICT));
	}

	@Test void aConcurrentChangeToAnotherRegionCommitsWithoutARerun() {
		var attempts=new AtomicInteger();
		var root=liveRoot();
		var app=new App(root);
		app.execute(view -> {
			attempts.incrementAndGet();
			view.live().add(1);
			app.more().add(2);
			root.set(root.get().assoc(OTHER,CVMLong.ONE));
		});
		assertEquals(1,attempts.get(),"disjoint changes merge");
		assertEquals(Sets.of(1),at(root,LIVE));
		assertEquals(Sets.of(2),at(root,MORE),"a region changed meanwhile keeps that change");
		assertEquals(CVMLong.ONE,at(root,OTHER),"so does a key the container does not serve");
	}

	@Test void anExclusiveTransactionBringsInManagedRegionsToo() {
		var root=emptyRoot();
		var app=new App(root);
		app.exclusive().execute(view -> {
			view.forked().add(4);
			view.signed().cursor().updateAndGet(s -> union(s,4));
		});
		assertEquals(Sets.of(4),at(root,FORKED));
		assertEquals(Sets.of(4),signedPayload(root));
	}

	@Test void aForkedContainerReconcilesRegionByRegion() {
		var root=emptyRoot();
		root.set(Index.of(OTHER,CVMLong.ONE));
		var app=new App(root);
		var fork=(App)app.fork();
		fork.forked().add(1);
		fork.live().add(2);
		// Meanwhile the destination changes a region the fork leaves alone, and one it does not serve
		app.signed().cursor().updateAndGet(s -> union(s,5));
		app.sync();
		root.set(root.get().assoc(OTHER,CVMLong.TWO));
		assertNull(at(root,FORKED),"nothing reaches the destination before the fork syncs");
		fork.sync();
		assertEquals(Sets.of(1),at(root,FORKED));
		assertEquals(Sets.of(2),at(root,LIVE));
		assertEquals(Sets.of(5),signedPayload(root),"a region changed only at the destination keeps its value");
		assertEquals(CVMLong.TWO,at(root,OTHER),"a region the container does not serve keeps the destination's value");
	}

	@Test void aForkedContainerHasARegionBothSidesChangedReconciledByItsComponent() {
		var root=emptyRoot();
		var app=new App(root);
		var fork=(App)app.fork();
		fork.live().add(1);
		app.live().add(2);
		fork.sync();
		assertEquals(Sets.of(1,2),at(root,LIVE),"a set's merge takes both changes");
	}

	@Test void aForkedContainerRefusesARegionWhoseComponentCannotReconcile() {
		var root=emptyRoot();
		var app=new App(root);
		var fork=(App)app.fork();
		fork.strict().add(1);
		app.strict().add(2);
		var before=root.get();
		var failure=assertThrows(MergeConflictException.class,fork::sync);
		assertTrue(failure.getMessage().contains("Unmerged"),"the region's own refusal");
		assertSame(before,root.get(),"a refused reconcile changes nothing");
	}

	@Test void aForkedContainerLeavesASignedRegionBothSidesChangedToItsComponent() {
		var root=emptyRoot();
		var app=new App(root);
		var fork=(App)app.fork();
		fork.signed().cursor().updateAndGet(s -> union(s,1));
		app.signed().cursor().updateAndGet(s -> union(s,2));
		app.sync();
		var before=root.get();
		var failure=assertThrows(MergeConflictException.class,fork::sync);
		assertTrue(failure.getMessage().contains("SignedSetComponent"),"the signed region decides, and refuses envelopes changed on both sides");
		assertSame(before,root.get());
	}

	@Test void aForkedContainerReconcilesANestedContainerRegionByRegion() {
		var nestedKey=Keyword.intern("nested");
		var root=emptyRoot();
		var app=new Regions(root);
		var nested=app.<Index<Keyword,ACell>,Regions>create(nestedKey,Regions::new);
		Members live=nested.create(LIVE,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c));
		nested.create(MORE,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c));
		var fork=app.fork();
		ALatticeComponent<?> forkNested=fork.child(nestedKey);
		Members forkLive=((Regions)forkNested).registered(LIVE);
		Members forkMore=((Regions)forkNested).registered(MORE);
		forkLive.add(1);
		forkMore.add(3);
		live.add(2);
		fork.sync();
		assertEquals(Index.of(nestedKey,Index.of(LIVE,Sets.of(1,2),MORE,Sets.of(3))),root.get());
	}

	@Test void aForkedContainerRefusesAnExplicitRegionItCannotMake() {
		var root=emptyRoot();
		var app=new Regions(root);
		app.add(LIVE,new Members(BoundaryCursor.over(root.<ASet<CVMLong>>path(LIVE))));
		var fork=app.fork();
		fork.cursor().updateAndGet(m -> m.assoc(LIVE,Sets.of(1)));
		root.set(root.get().assoc(LIVE,Sets.of(2)));
		var failure=assertThrows(MergeConflictException.class,fork::sync);
		assertTrue(failure.getMessage().contains("explicit"));
	}

	@Test void aSpanningTransactionSeesWhatALiveRegionCommitted() {
		var root=new Root<>(Index.<Keyword,ACell>of(LIVE,Sets.empty()));
		var app=new App(root);
		app.live().execute(m -> m.add(1));
		app.execute(view -> {
			assertEquals(Sets.of(1),view.live().value(),"a live region's commit is already in the container");
			view.live().add(2);
		});
		assertEquals(Sets.of(1,2),at(root,LIVE));
	}

	/** A root where both live regions exist, as a transaction on a region needs. */
	private static Root<Index<Keyword,ACell>> liveRoot() {
		return new Root<>(Index.<Keyword,ACell>of(LIVE,Sets.empty(),MORE,Sets.empty()));
	}

	/** Waits until a thread is parked, as one waiting for a claim is, or has finished. */
	private static void awaitParked(Thread thread) {
		while (thread.isAlive() && thread.getState()!=Thread.State.WAITING) Thread.onSpinWait();
	}

	@Test void regionsTransactExclusivelyAtTheSameTime() throws InterruptedException {
		var root=liveRoot();
		var app=new App(root);
		Thread[] other=new Thread[1];
		app.live().exclusive().execute(m -> {
			other[0]=Thread.ofPlatform().start(() -> app.more().exclusive().execute(n -> n.add(2)));
			awaitParked(other[0]);
			assertFalse(other[0].isAlive(),"the other region's exclusive transaction did not wait");
			assertEquals(Sets.of(2),at(root,MORE),"and is already in the container");
			m.add(1);
		});
		other[0].join();
		assertEquals(Sets.of(1),at(root,LIVE));
	}

	@Test void aWriteThroughARegionWaitsForItsExclusiveTransaction() throws InterruptedException {
		var root=liveRoot();
		var app=new App(root);
		Thread[] writer=new Thread[1];
		app.live().exclusive().execute(m -> {
			writer[0]=Thread.ofPlatform().start(() -> app.live().add(5));
			awaitParked(writer[0]);
			assertTrue(writer[0].isAlive(),"waits for the region's claim");
			m.add(1);
		});
		writer[0].join();
		assertEquals(Sets.of(1,5),at(root,LIVE),"and lands on top of its commit");
	}

	@Test void regionWritesWaitForAnExclusiveTransactionOnTheContainer() throws InterruptedException {
		var root=liveRoot();
		var app=new App(root);
		Thread[] writers=new Thread[2];
		app.exclusive().execute(view -> {
			writers[0]=Thread.ofPlatform().start(() -> app.live().add(5));
			writers[1]=Thread.ofPlatform().start(() -> app.more().exclusive().execute(m -> m.add(6)));
			for (Thread writer:writers) {
				awaitParked(writer);
				assertTrue(writer.isAlive(),"every region waits for the container's claim");
			}
			view.live().add(1);
		});
		for (Thread writer:writers) writer.join();
		assertEquals(Sets.of(1,5),at(root,LIVE),"a region write lands on top of the container's commit");
		assertEquals(Sets.of(6),at(root,MORE),"a region the container left alone commits its exclusive transaction");
	}

	@Test void aContainerTransactionSeesWhatARegionCommitted() {
		var root=liveRoot();
		var app=new App(root);
		app.live().exclusive().execute(m -> m.add(1));
		app.more().add(2);
		app.exclusive().execute(view -> {
			assertEquals(Sets.of(1),view.live().value());
			assertEquals(Sets.of(2),view.more().value());
			view.live().add(3);
		});
		assertEquals(Sets.of(1,3),at(root,LIVE));
	}

	@Test void aRegionCommitDuringAContainerTransactionMergesByTheRegion() {
		var attempts=new AtomicInteger();
		var root=liveRoot();
		var app=new App(root);
		app.execute(view -> {
			// A region commit lands while the first attempt runs
			if (attempts.incrementAndGet()==1) app.live().add(7);
			view.live().add(1);
		});
		assertEquals(1,attempts.get(),"a set's merge takes both changes");
		assertEquals(Sets.of(1,7),at(root,LIVE));
	}

	@Test void aRegionCommitDuringAContainerTransactionMakesItRetryOnTopWhereTheRegionCannotMerge() {
		var attempts=new AtomicInteger();
		var root=emptyRoot();
		var app=new App(root);
		app.execute(view -> {
			if (attempts.incrementAndGet()==1) app.strict().add(7);
			view.strict().add(1);
		});
		assertEquals(2,attempts.get());
		assertEquals(Sets.of(1,7),at(root,STRICT));
	}

	@Test void aContainerTransactionChangingAClaimedRegionMergesWithItsCommit() {
		var runs=new AtomicInteger();
		var root=liveRoot();
		var app=new App(root);
		app.live().exclusive().execute(m -> {
			runs.incrementAndGet();
			// The container's own commit does not pass through the region's boundary
			app.execute(view -> view.live().add(9));
			m.add(1);
		});
		assertEquals(1,runs.get(),"merged, not run again");
		assertEquals(Sets.of(1,9),at(root,LIVE),"both survive");
	}

	@Test void aRegionThatCanMergeCommitsWithAChangeToItsOwnValue() {
		var attempts=new AtomicInteger();
		var root=liveRoot();
		var app=new App(root);
		app.live().execute(m -> {
			attempts.incrementAndGet();
			root.set(root.get().assoc(LIVE,Sets.of(101)));
			m.add(1);
		});
		assertEquals(1,attempts.get(),"a set's merge takes both changes");
		assertEquals(Sets.of(1,101),at(root,LIVE));
	}

	@Test void aRegionThatCannotMergeRerunsUnderItsClaim() {
		var attempts=new AtomicInteger();
		var root=liveRoot();
		var region=new Unmerged(BoundaryCursor.over(root.<ASet<CVMLong>>path(LIVE)));
		region.execute(m -> {
			if (attempts.incrementAndGet()==1) root.set(root.get().assoc(LIVE,Sets.of(9)));
			m.cursor().updateAndGet(s -> union(s,1));
		});
		assertEquals(2,attempts.get(),"a change on both sides is a conflict");
		assertEquals(Sets.of(1,9),at(root,LIVE),"the rerun starts from the change");
	}

	@Test void aRegionThatCannotReconcileFailsRatherThanLoseAContainerChange() {
		var root=liveRoot();
		var region=new Unmerged(BoundaryCursor.over(root.<ASet<CVMLong>>path(LIVE)));
		var failure=assertThrows(MergeConflictException.class,() -> region.exclusive().execute(m -> {
			root.set(root.get().assoc(LIVE,Sets.of(9)));
			m.cursor().set(Sets.of(1));
		}));
		assertTrue(failure.getMessage().contains("Unmerged"),"the component's own refusal names it");
		assertEquals(Sets.of(9),at(root,LIVE),"never overwritten");
	}

	@Test void aForkedContainerCarriesARemovalOnEitherSide() {
		var root=new Root<>(Index.<Keyword,ACell>of(LIVE,Sets.of(1),OTHER,CVMLong.ONE));
		var app=new App(root);
		var fork=(App)app.fork();
		fork.cursor().updateAndGet(m -> m.dissoc(LIVE));
		fork.forked().add(3);
		root.set(root.get().dissoc(OTHER));
		fork.sync();
		assertEquals(Index.of(FORKED,Sets.of(3)),root.get(),"the fork removed one region and the destination another");
	}

	@Test void aForkedContainerSyncsIntoAnAbsentOrMovedDestination() {
		var root=new Root<Index<Keyword,ACell>>();
		var app=new App(root);
		var fork=(App)app.fork();
		fork.forked().add(1);
		root.set(Index.of(OTHER,CVMLong.ONE));
		fork.sync();
		assertEquals(Index.of(OTHER,CVMLong.ONE,FORKED,Sets.of(1)),root.get());
	}

	@Test void aNestedContainerInsideAForkedOneSyncsItsRegions() {
		var nestedKey=Keyword.intern("nested");
		var root=emptyRoot();
		var app=new Regions(root);
		var nested=app.<Index<Keyword,ACell>,Regions>create(nestedKey,Regions::new);
		nested.create(FORKED,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c.fork()));
		var fork=app.fork();
		ALatticeComponent<?> forkNested=fork.child(nestedKey);
		Members region=((Regions)forkNested).registered(FORKED);
		region.add(7);
		root.set(root.get().assoc(OTHER,CVMLong.ONE));
		fork.sync();
		assertEquals(Index.of(OTHER,CVMLong.ONE,nestedKey,Index.of(FORKED,Sets.of(7))),root.get());
	}

	@Test void aForkedContainerKeepsEntriesWhoseKeysAreNotKeywords() {
		var note=Strings.create("note");
		var root=emptyRoot();
		var app=new App(root);
		var fork=(App)app.fork();
		fork.cursor().updateAndGet(m -> withKey(m,note,CVMLong.ONE));
		root.set(root.get().assoc(OTHER,CVMLong.TWO));
		fork.sync();
		assertEquals(CVMLong.ONE,root.get().get(note));
		assertEquals(CVMLong.TWO,at(root,OTHER));
	}

	@SuppressWarnings("unchecked")
	private static Index<Keyword,ACell> withKey(Index<Keyword,ACell> index,ABlobLike<?> key,ACell value) {
		return (Index<Keyword,ACell>)(Index<?,?>)((Index<ABlobLike<?>,ACell>)(Index<?,?>)index).assoc(key,value);
	}

	@Test void explicitChildrenAreSyncedByWhoeverMadeThem() {
		var root=emptyRoot();
		var mine=new Members(new IndexComponent<Keyword,ACell>(root).<ASet<CVMLong>>path(OTHER).fork());
		var app=new Regions(root);
		app.add(OTHER,mine);
		mine.add(1);
		app.sync();
		assertNull(at(root,OTHER),"the container does not sync a child it did not make");
		mine.sync();
		assertEquals(Sets.of(1),at(root,OTHER));
	}
}
