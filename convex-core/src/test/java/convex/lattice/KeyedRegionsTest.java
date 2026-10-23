package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.TransactionalRootCursor;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.OwnerLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

/** Properties of composition: selection, isolation, convergence and shared state. */
class KeyedRegionsTest {
	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword UNKNOWN=Keyword.intern("unknown");
	private static final Keyword NESTED=Keyword.intern("nested");

	private static class TestRegions extends Regions {
		TestRegions(LatticeHost<Index<Keyword,ACell>> host) { super(host); }
		TestRegions(AForkableCursor<Index<Keyword,ACell>> cursor) { super(cursor); }
		Counter counter(Keyword key) { return create(key,(AForkableCursor<AInteger> cursor) -> new Counter(cursor).fork()); }
		Members members(Keyword key) { return create(key,(AForkableCursor<ASet<CVMLong>> cursor) -> new Members(cursor).fork()); }
		TestRegions nested(Keyword key) { return this.<Index<Keyword,ACell>,TestRegions>createRegion(key,TestRegions::new); }
	}

	private static class Counter extends LatticeComponent<AInteger> {
		Counter(AForkableCursor<AInteger> cursor) {
			super(MaxLattice.INSTANCE,cursor);
		}
		@Override protected Counter rebind(AForkableCursor<AInteger> cursor) { return new Counter(cursor); }
		@Override public Counter fork() { return rebind(storage.fork()); }
	}

	private static class Members extends LatticeComponent<ASet<CVMLong>> {
		Members(AForkableCursor<ASet<CVMLong>> cursor) {
			super(SetLattice.create(),cursor);
		}
		@Override protected Members rebind(AForkableCursor<ASet<CVMLong>> cursor) { return new Members(cursor); }
		@Override public Members fork() { return rebind(storage.fork()); }
	}

	@Test void singleAndMultipleRegionsShareTheSameWorkspaceAndPolicy() {
		var host=LatticeHost.create();
		var single=SingleRegion.create(host,A,(AForkableCursor<AInteger> cursor) -> new Counter(cursor).fork());
		var shared=single.component();
		shared.setContext(LatticeContext.EMPTY.withSigningKey(AKeyPair.createSeeded(42)));
		var context=shared.getContext();
		var multiple=new Regions(host,Map.of(A,shared));
		var another=new SingleRegion<>(host,A,shared);
		assertSame(shared,another.component());
		assertSame(context,shared.getContext());
		host.addApplication(single);
		host.addApplication(multiple);
		host.addApplication(another);
		host.merge(Index.of(A,CVMLong.TWO,UNKNOWN,CVMLong.ONE));
		assertTrue(host.cursor().get().isEmpty());
		assertEquals(CVMLong.TWO,shared.cursor().get());
		assertEquals(Index.of(A,CVMLong.TWO),multiple.sync());
		assertSame(host.cursor().get(),single.sync());
		assertSame(host.cursor().get(),another.sync());
	}

	@Test void concreteRegionsComposeAtNestedPathsWithoutCreatingUnusedState() {
		var host=LatticeHost.create();
		var root=new Regions(host);
		var nested=root.<Index<Keyword,ACell>,Regions>create(NESTED,Regions::new);
		var child=nested.create(A,(AForkableCursor<AInteger> cursor) -> new Counter(cursor).fork());
		var alias=new Regions(nested.cursor());
		assertSame(child,alias.add(A,child));
		assertTrue(host.cursor().get().isEmpty());
		root.mergeAt(CVMLong.ONE,NESTED,A);
		assertTrue(host.cursor().get().isEmpty());
		alias.sync();
		assertEquals(Index.of(NESTED,Index.of(A,CVMLong.ONE)),root.sync());
		assertThrows(IllegalStateException.class,() -> root.add(B,child));
	}

	@Test void emptyApplicationIsIdentityForRootAndPathDeliveries() throws Exception {
		var store=new MemoryStore();
		var initial=Index.<Keyword,ACell>of(A,CVMLong.ONE,NESTED,Index.of(B,Strings.create("keep")));
		var host=LatticeHost.create(initial,store);
		var app=new Regions(host);
		host.addApplication(app);
		for (int i=0;i<12;i++) {
			var incoming=Index.<Keyword,ACell>of(A,CVMLong.create(i),UNKNOWN,Sets.of(i));
			assertSame(initial,host.merge(incoming));
			assertSame(initial,host.mergeAt(incoming));
			assertSame(initial,host.mergeAt(CVMLong.create(i),A));
			assertSame(initial,host.mergeAt(incoming,NESTED,B));
			assertSame(initial,app.sync());
		}
		assertSame(initial,host.merge(null));
		assertNull(store.getRootData(),"application sync never persists the host");
	}

	@Test void emptyAndUnusedNestedApplicationsDoNotCreateContainers() {
		var host=LatticeHost.<Index<Keyword,ACell>>create(null,null);
		var app=new TestRegions(host);
		var nested=app.nested(NESTED);
		var counter=nested.counter(A);
		assertNull(host.cursor().get());
		assertNull(counter.cursor().get());
		app.merge(Index.of(UNKNOWN,CVMLong.ONE));
		app.mergeAt(CVMLong.ONE,NESTED,UNKNOWN);
		app.sync();
		assertNull(host.cursor().get(),"construction, reads and unchanged syncs are inert");
		app.mergeAt(CVMLong.TWO,NESTED,A);
		assertNull(host.cursor().get(),"incoming changes remain staged");
		assertEquals(CVMLong.TWO,counter.cursor().get());
		app.sync();
		assertEquals(Index.of(NESTED,Index.of(A,CVMLong.TWO)),host.cursor().get());
	}

	@Test void projectingIncomingDataToInterestsDoesNotChangeTheResult() {
		for (int i=0;i<8;i++) {
			var fullHost=LatticeHost.create();
			var projectedHost=LatticeHost.create();
			var full=new TestRegions(fullHost);
			var projected=new TestRegions(projectedHost);
			full.counter(A);
			full.members(B);
			projected.counter(A);
			projected.members(B);
			var incoming=Index.<Keyword,ACell>of(A,CVMLong.create(i),B,Sets.of(i));
			full.merge(incoming.assoc(UNKNOWN,Strings.create("unrelated")));
			projected.merge(incoming);
			assertEquals(incoming,full.sync());
			assertEquals(projected.sync(),fullHost.cursor().get());
		}
	}

	@Test void rootAndPathDeliveriesHaveTheSameMeaningIncludingRepeatedExchange() {
		var rootHost=LatticeHost.create();
		var pathHost=LatticeHost.create();
		var root=new TestRegions(rootHost);
		var paths=new TestRegions(pathHost);
		root.nested(NESTED).members(A);
		paths.nested(NESTED).members(A);
		rootHost.addApplication(root);
		pathHost.addApplication(paths);
		for (int i=0;i<8;i++) {
			var members=Sets.<CVMLong>of(i);
			var incoming=Index.<Keyword,ACell>of(NESTED,Index.of(A,members));
			rootHost.merge(incoming);
			rootHost.merge(incoming);
			// Index keys have blob equality, including string path aliases.
			pathHost.mergeAt(members,Strings.create("nested"),Strings.create("a"));
			assertEquals(root.sync(),paths.sync());
			var before=rootHost.cursor().get();
			rootHost.merge(incoming);
			assertEquals(before,root.sync(),"repeating an accepted value is idempotent");
		}
	}

	@Test void independentRegionsCommuteAndPreserveEveryUnregisteredValue() {
		for (boolean reverse:List.of(false,true)) {
			var opaque=Index.of(UNKNOWN,Strings.create("opaque"));
			var host=LatticeHost.create(Index.<Keyword,ACell>of(UNKNOWN,opaque),null);
			var first=new TestRegions(host);
			var second=new TestRegions(host);
			first.counter(A).cursor().set(CVMLong.TWO);
			second.members(B).cursor().set(Sets.of(1,2));
			if (reverse) { second.sync(); first.sync(); }
			else { first.sync(); second.sync(); }
			var expected=Index.of(UNKNOWN,opaque,A,CVMLong.TWO,B,Sets.of(1,2));
			assertEquals(expected,host.cursor().get());
			assertSame(opaque,host.cursor().get().get(UNKNOWN));
			assertEquals(expected,first.sync());
			assertEquals(expected,second.sync());
		}
	}

	@Test void nestedPublicationNeverRestoresAStaleSiblingOrADeletedRegion() {
		var host=LatticeHost.create(Index.<Keyword,ACell>of(NESTED,Index.of(A,CVMLong.ONE,B,CVMLong.ONE),
			UNKNOWN,Strings.create("removed later")),null);
		var app=new TestRegions(host);
		var nested=app.nested(NESTED);
		nested.counter(A).cursor().set(CVMLong.TWO);
		// Another application replaces B and removes an unrelated root region.
		host.cursor().set(Index.of(NESTED,Index.of(A,CVMLong.ONE,B,CVMLong.create(9))));
		assertEquals(Index.of(NESTED,Index.of(A,CVMLong.TWO,B,CVMLong.create(9))),app.sync());
		assertNull(host.cursor().get().get(UNKNOWN));
	}

	@Test void overlappingIndependentComponentsConvergeInEitherPublicationOrder() {
		for (boolean reverse:List.of(false,true)) {
			var host=LatticeHost.create();
			var first=new TestRegions(host);
			var second=new TestRegions(host);
			first.members(A).merge(Sets.of(1,2));
			second.members(A).merge(Sets.of(2,3));
			if (reverse) { second.sync(); first.sync(); }
			else { first.sync(); second.sync(); }
			assertEquals(Index.of(A,Sets.of(1,2,3)),host.cursor().get());
		}
	}

	@Test void publicationRetriesPreserveConcurrentUnregisteredWrites() {
		AtomicInteger attempts=new AtomicInteger();
		// This root's atomic updates use CAS, allowing a race at the commit boundary.
		var root=new TransactionalRootCursor<>(Index.<Keyword,ACell>none()) {
			@Override public boolean compareAndSet(Index<Keyword,ACell> expected,Index<Keyword,ACell> value) {
				if (attempts.incrementAndGet()==1) set(get().assoc(UNKNOWN,CVMLong.TWO));
				return super.compareAndSet(expected,value);
			}
		};
		var host=new LatticeHost<>(root,null);
		var app=new TestRegions(host);
		app.counter(A).cursor().set(CVMLong.ONE);
		assertEquals(Index.of(A,CVMLong.ONE,UNKNOWN,CVMLong.TWO),app.sync());
		assertEquals(2,attempts.get(),"the write was retried against the racing root");
	}

	@Test void sharedComponentsRetainWorkingStatePolicyAndDestination() {
		var host=LatticeHost.create();
		var first=new TestRegions(host);
		var shared=first.members(A);
		var policy=LatticeContext.create(null,AKeyPair.createSeeded(615));
		shared.setContext(policy);
		shared.merge(Sets.of(1));
		var second=new TestRegions(host);
		assertSame(shared,second.region(A,shared));
		assertSame(policy,shared.getContext(),"registration must not configure shared children");
		assertEquals(Sets.of(1),shared.cursor().get());
		host.addApplication(first);
		host.addApplication(second);
		host.merge(Index.of(A,Sets.of(2)));
		assertEquals(Sets.of(1,2),shared.cursor().get(),"duplicate delivery is harmless for a lattice merge");
		assertEquals(Index.none(),host.cursor().get());
		assertEquals(Index.of(A,Sets.of(1,2)),second.sync(),"either application can publish shared edits");
		assertSame(host.cursor().get(),first.sync(),"repeated publication is a no-op");
		assertSame(policy,shared.getContext());
	}

	@Test void factoriesBindPolicyExplicitlyToTheirAdapters() {
		var host=LatticeHost.create();
		var policy=LatticeContext.create(null,AKeyPair.createSeeded(616));
		var app=new TestRegions(host);
		var child=app.nested(NESTED).create(A,(AForkableCursor<AInteger> cursor) -> {
			var counter=new Counter(cursor);
			counter.setContext(policy);
			return counter;
		});
		assertSame(policy,child.getContext());
		assertSame(LatticeContext.EMPTY,app.counter(B).getContext());
	}

	@Test void absenceDoesNotMeanNullAndCustomChildBehaviourIsUsedForBothRoutes() {
		var host=LatticeHost.create();
		var app=new TestRegions(host);
		AtomicInteger deliveries=new AtomicInteger();
		app.createRegion(A,(AForkableCursor<ACell> destination) -> new ALatticeComponent<ACell>(destination) {
			@Override public ACell merge(ACell incoming) {
				deliveries.incrementAndGet();
				// A domain rule, deliberately different from any inferred lattice merge.
				if (incoming==null || CVMLong.TWO.equals(incoming)) cursor().set(incoming);
				return cursor().get();
			}
		});
		app.merge(Index.of(A,CVMLong.ONE));
		assertNull(host.cursor().get().get(A));
		app.mergeAt(CVMLong.TWO,A);
		assertEquals(CVMLong.TWO,host.cursor().get().get(A));
		app.merge(Index.none());
		assertEquals(2,deliveries.get());
		assertEquals(CVMLong.TWO,host.cursor().get().get(A));
		app.merge(Index.of(A,null));
		assertEquals(3,deliveries.get());
		assertNull(host.cursor().get().get(A),"explicit null reaches the custom child");
	}

	@Test void invalidRootIsRejectedBeforeAnyChildReceivesIt() {
		var host=LatticeHost.create();
		var app=new TestRegions(host);
		var child=app.counter(A);
		assertThrows(ClassCastException.class,() -> app.mergeAt(CVMLong.ONE));
		assertNull(child.cursor().get());
		assertEquals(Index.none(),host.cursor().get());
	}

	@Test void configurationRejectsDuplicatesAndFreezesAtFirstUse() {
		for (int operation=0;operation<3;operation++) {
			var app=new TestRegions(LatticeHost.create());
			var child=app.counter(A);
			assertThrows(IllegalArgumentException.class,() -> app.region(A,child));
			switch (operation) {
				case 0 -> app.merge(null);
				case 1 -> app.mergeAt(CVMLong.ONE,UNKNOWN);
				default -> app.sync();
			}
			assertThrows(IllegalStateException.class,() -> app.region(B,child));
			assertThrows(IllegalStateException.class,() -> app.counter(B));
			var other=new TestRegions(LatticeHost.create());
			assertSame(child,other.region(A,child),"freezing one registry does not freeze the shared child");
		}
	}

	@Test void signedRegionsRequireAuthorisedEnvelopesForBothDeliveryForms() {
		var owner=AKeyPair.createSeeded(617);
		var stranger=AKeyPair.createSeeded(618);
		var payload=Sets.<CVMLong>of(1);
		List<SignedLattice<ASet<CVMLong>>> rules=List.of(
			OwnerKeyLattice.create(SetLattice.<CVMLong>create()).signingFor(owner.getAccountKey()),
			OwnerLattice.create(SetLattice.<CVMLong>create()).signingFor(owner.getAccountKey()));
		for (var rule:rules) {
			var host=LatticeHost.create();
			var app=new TestRegions(host);
			var child=app.region(A,new SignedComponent<>(rule,host.cursor().path(A),owner::signData));
			app.merge(Index.of(A,stranger.signData(payload)));
			app.mergeAt(stranger.signData(payload),A);
			assertNull(child.cursor().get());
			assertThrows(IllegalArgumentException.class,() -> app.mergeAt(payload,A));
			app.mergeAt(payload,A,Keywords.VALUE);
			assertNull(child.cursor().get(),"delivery cannot cross a signature boundary");
			app.merge(Index.of(A,owner.signData(payload)));
			app.mergeAt(owner.signData(Sets.<CVMLong>of(2)),A);
			assertEquals(Sets.of(1,2),child.cursor().get());
			assertEquals(Index.none(),host.cursor().get());
			app.sync();
			SignedData<?> result=(SignedData<?>)host.cursor().get().get(A);
			assertEquals(Sets.of(1,2),result.getValue());
			assertEquals(owner.getAccountKey(),result.getAccountKey());
			assertTrue(result.checkSignature());
		}
	}

	@Test void applicationPublicationDoesNotImplyHostPersistence() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(store);
		var app=new TestRegions(host);
		app.counter(A).merge(CVMLong.TWO);
		app.sync();
		assertNull(store.getRootData());
		host.sync();
		assertEquals(Index.of(A,CVMLong.TWO),store.getRootData());
	}

	@Test void mergeOrderAndPublicationFrequencyDoNotChangeConvergentResults() {
		int[][] orders={{1,2,3},{1,3,2},{2,1,3},{2,3,1},{3,1,2},{3,2,1}};
		for (var order:orders) {
			for (boolean publishEach:List.of(false,true)) {
				var host=LatticeHost.create();
				var app=new TestRegions(host);
				app.counter(A);
				app.members(B);
				for (int value:order) {
					app.merge(Index.of(A,CVMLong.create(value),B,Sets.of(value),UNKNOWN,CVMLong.ONE));
					if (publishEach) app.sync();
				}
				assertEquals(Index.of(A,CVMLong.create(3),B,Sets.of(1,2,3)),app.sync());
			}
		}
	}

	@Test void liveChildrenControlTheirOwnVisibility() {
		var host=LatticeHost.create();
		var app=new TestRegions(host);
		app.createRegion(A,(AForkableCursor<AInteger> destination) ->
			LatticeComponent.live(MaxLattice.INSTANCE,destination,LatticeContext.EMPTY));
		host.addApplication(app);
		host.merge(Index.of(A,CVMLong.TWO,UNKNOWN,CVMLong.ONE));
		assertEquals(Index.of(A,CVMLong.TWO),host.cursor().get());
		assertSame(host.cursor().get(),app.sync());
	}

	@Test void failedChildPublicationLeavesEarlierWorkAndLaterChildrenPending() {
		var host=LatticeHost.create();
		var app=new TestRegions(host);
		app.counter(A).cursor().set(CVMLong.ONE);
		app.createRegion(B,(AForkableCursor<ACell> destination) -> new ALatticeComponent<ACell>(destination) {
			@Override public ACell sync() { throw new IllegalStateException("publication failed"); }
		});
		var pending=app.counter(UNKNOWN);
		pending.cursor().set(CVMLong.TWO);
		assertThrows(IllegalStateException.class,app::sync);
		assertEquals(Index.of(A,CVMLong.ONE),host.cursor().get());
		assertEquals(CVMLong.TWO,pending.cursor().get());
	}
}
