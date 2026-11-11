package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/**
 * Transactions over a region container that manages its regions: isolated to one
 * region, or spanning several, with forked and live regions side by side.
 */
class RegionTransactionsTest {
	private static final Keyword FORKED=Keyword.intern("forked");
	private static final Keyword LIVE=Keyword.intern("live");
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

	/** Regions with one forked, one live and one signed region, transacting as a whole. */
	private static final class App extends Regions
			implements TransactionalComponent<Index<Keyword,ACell>,App> {
		App(AForkableCursor<Index<Keyword,ACell>> cursor) {
			super(cursor);
			create(FORKED,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c.fork()));
			create(LIVE,(AForkableCursor<ASet<CVMLong>> c) -> new Members(c));
			create(SIGNED,(AForkableCursor<SignedData<ASet<CVMLong>>> c) -> new SignedComponent<>(SIGNING,c,OWNER::signData));
		}
		private App(AForkableCursor<Index<Keyword,ACell>> cursor,App source) { super(cursor,source); }
		@Override public App rebind(AForkableCursor<Index<Keyword,ACell>> cursor) { return new App(cursor,this); }
		Members forked() { return registered(FORKED); }
		Members live() { return registered(LIVE); }
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

	@Test void aRetriedAttemptLeavesNothingBehind() {
		var attempts=new AtomicInteger();
		var root=emptyRoot();
		var app=new App(root);
		app.execute(view -> {
			int attempt=attempts.incrementAndGet();
			view.forked().add(attempt);
			view.live().add(attempt);
			// A concurrent change to the container's value forces another attempt
			if (attempt==1) root.set(root.get().assoc(OTHER,CVMLong.ONE));
		});
		assertEquals(2,attempts.get());
		assertEquals(Sets.of(2),at(root,FORKED));
		assertEquals(Sets.of(2),at(root,LIVE));
		assertEquals(CVMLong.ONE,at(root,OTHER));
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

	@Test void aForkedContainerRefusesARegionBothSidesChangedWithoutARule() {
		var root=emptyRoot();
		var app=new App(root);
		var fork=(App)app.fork();
		fork.live().add(1);
		app.live().add(2);
		var before=root.get();
		assertThrows(UnsupportedOperationException.class,fork::sync);
		assertSame(before,root.get(),"a refused reconcile changes nothing");
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
