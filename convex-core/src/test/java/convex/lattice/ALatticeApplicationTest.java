package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

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
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.generic.ADelegatingLattice;
import convex.lattice.generic.CompareLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.SetLattice;

public class ALatticeApplicationTest {

	private static final Keyword ITEMS=Keyword.intern("items");
	private static final Keyword UNKNOWN=Keyword.intern("unknown");
	private static final SetLattice<CVMLong> ITEMS_LATTICE=SetLattice.create();
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,ITEMS_LATTICE);

	@Test
	public void testConfiguredRootDelegatesWithoutInstallingIncomingState() {
		var host=LatticeHost.create(LATTICE.zero(),null);
		var first=new LatticeApplication<>(LATTICE,host);
		var second=new LatticeApplication<>(LATTICE,host);
		host.addApplication(first);
		host.addApplication(second);
		var incoming=LATTICE.zero().assoc(ITEMS,Sets.of(1));
		assertEquals(LATTICE.zero(),host.merge(incoming));
		assertEquals(incoming,first.cursor().get());
		assertEquals(incoming,second.cursor().get());
		assertEquals(LATTICE.zero(),host.cursor().get());
		first.sync();
		assertEquals(incoming,host.cursor().get());
	}

	@Test
	public void testEmptyHostIgnoresIncomingState() {
		var host=LatticeHost.create(LATTICE.zero(),null);
		host.merge(LATTICE.zero().assoc(ITEMS,Sets.of(1)));
		assertEquals(LATTICE.zero(),host.cursor().get());
	}

	@Test
	public void testApplicationCreatesWorkingStateAndChildrenNeedNoForks() throws Exception {
		var store=new MemoryStore();
		var host=LatticeHost.create(LATTICE.zero(),store);
		var first=new LatticeApplication<>(LATTICE,host);
		var second=new LatticeApplication<>(LATTICE,host);
		Items a=new Items(first);
		Items anotherView=new Items(first);
		a.add(CVMLong.ONE);
		assertEquals(Sets.of(1),anotherView.cursor().get());
		assertEquals(LATTICE.zero(),host.cursor().get());
		assertNull(new Items(second).cursor().get());
		new Items(second).add(CVMLong.TWO);
		first.sync();
		second.sync();
		assertEquals(Sets.of(1,2),host.cursor().get().get(ITEMS));
		assertEquals(Sets.of(1),a.cursor().get(),"Other publications are adopted at this application's sync boundary");
		first.sync();
		assertEquals(Sets.of(1,2),a.cursor().get());
		assertNull(store.getRootData(),"Application publication must stop at the host boundary");
		host.sync();
		assertEquals(host.cursor().get(),store.getRootData());
	}

	private static final class Items extends ALatticeComponent<ASet<CVMLong>> {
		Items(ALatticeApplication<Index<Keyword,ACell>> application) {
			super(application.cursor().withDefault(Index.none()).path(ITEMS));
		}

		void add(CVMLong value) {
			cursor().updateAndGet(items -> (items==null?ITEMS_LATTICE.zero():items).include(value));
		}
	}

	@Test
	public void testPreparationFailureLeavesCheckpointsLocalForRetry() {
		var host=LatticeHost.create(LATTICE.zero(),null);
		AtomicInteger calls=new AtomicInteger();
		var app=new ALatticeApplication<Index<Keyword,ACell>>(host) {
			@Override public Index<Keyword,ACell> merge(Index<Keyword,ACell> other) {
				return cursor().updateAndGet(own -> LATTICE.merge(own,other));
			}
			@Override protected void beforeSync() {
				new Items(this).add(CVMLong.ONE);
				if (calls.incrementAndGet()==1) throw new IllegalStateException("checkpoint failed");
				new Items(this).add(CVMLong.TWO);
			}
		};
		assertThrows(IllegalStateException.class,app::sync);
		assertEquals(LATTICE.zero(),host.cursor().get());
		assertEquals(Sets.of(1),app.cursor().get().get(ITEMS));
		app.sync();
		assertEquals(Sets.of(1,2),host.cursor().get().get(ITEMS));
		assertEquals(2,calls.get());
	}

	@Test
	public void testOpenMergeAndPublicationPreserveRetainedRootWithoutComponents() throws Exception {
		MemoryStore store=new MemoryStore();
		Index<Keyword,ACell> retained=LATTICE.zero()
			.assoc(ITEMS,Sets.of(2)).assoc(UNKNOWN,CVMLong.ONE);
		store.setRootData(retained);
		var host=LatticeHost.open(LATTICE.zero(),store);
		LatticeApplication<Index<Keyword,ACell>> app=LatticeApplication.live(LATTICE,host);
		assertSame(LATTICE,app.getLattice());
		assertEquals(retained,app.cursor().get());

		Index<Keyword,ACell> incoming=LATTICE.zero()
			.assoc(ITEMS,Sets.of(1)).assoc(UNKNOWN,CVMLong.TWO);
		Index<Keyword,ACell> merged=app.merge(incoming);
		assertEquals(retained.assoc(ITEMS,Sets.of(1,2)),merged);
		assertSame(merged,app.cursor().get());
		assertSame(merged,app.merge(incoming));
		assertSame(merged,app.merge(null));
		assertEquals(retained,store.getRootData(),"Merge must not publish the store root");

		app.sync();
		assertEquals(retained,store.getRootData(),"Application sync stops before host persistence");
		host.sync();
		assertEquals(merged,store.getRootData());
		LatticeApplication<Index<Keyword,ACell>> reopened=LatticeApplication.live(LATTICE,LatticeHost.open(LATTICE.zero(),store));
		assertEquals(merged,reopened.cursor().get());
	}

	@Test
	public void testHostedApplicationsShareStateAndPreserveUnknownOwnRegions() {
		Index<Keyword,ACell> initial=LATTICE.zero().assoc(UNKNOWN,CVMLong.ONE);
		LatticeHost<Index<Keyword,ACell>> host=LatticeHost.create(initial,null);
		LatticeApplication<Index<Keyword,ACell>> first=LatticeApplication.live(LATTICE,host);
		LatticeApplication<Index<Keyword,ACell>> second=LatticeApplication.live(LATTICE,host);
		AtomicInteger publications=new AtomicInteger();
		host.setPublicationPolicy(value -> {
			publications.incrementAndGet();
			return value;
		});

		first.merge(LATTICE.zero().assoc(ITEMS,Sets.of(1)).assoc(UNKNOWN,CVMLong.TWO));
		second.merge(LATTICE.zero().assoc(ITEMS,Sets.of(2)));
		assertSame(host.cursor(),first.cursor());
		assertSame(first.cursor(),second.cursor());
		assertSame(host.cursor().get(),first.cursor().get());
		assertSame(first.cursor().get(),second.cursor().get());
		assertEquals(initial.assoc(ITEMS,Sets.of(1,2)),host.cursor().get());
		assertEquals(0,publications.get());
		second.sync();
		assertEquals(0,publications.get());
		host.sync();
		assertEquals(1,publications.get());
	}

	@Test
	public void testAdapterContextIsExplicitAndLocal() {
		var host=LatticeHost.create(LATTICE.zero(),null);
		var hostPolicy=LatticeContext.create(CVMLong.ONE,null);
		var first=new LatticeApplication<>(LATTICE,host);
		first.setContext(hostPolicy);
		assertSame(hostPolicy,first.getContext());
		assertSame(LatticeContext.EMPTY,new LatticeApplication<>(LATTICE,host).getContext());
		first.setContext(null);
		assertSame(LatticeContext.EMPTY,first.getContext());
	}

	@Test
	public void testIndependentSigningAndStampingThroughExistingChildren() {
		AKeyPair alice=AKeyPair.createSeeded(6201);
		AKeyPair bob=AKeyPair.createSeeded(6202);
		Keyword timestamp=Timestamped.TIMESTAMP;
		var owners=OwnerKeyLattice.create(Timestamped.lattice());
		var host=LatticeHost.create(owners.zero(),null);
		var first=LatticeApplication.live(owners,host);
		var second=LatticeApplication.live(owners,host);
		first.setContext(LatticeContext.create(CVMLong.create(100),null));
		second.setContext(LatticeContext.create(CVMLong.create(200),null));
		// Each author receives an explicit signer. The timestamp wrapper captures the
		// application's current clock supplier; nested set views need neither policy.
		var aliceRecord=new ASignedComponent<>(first.cursor().path(alice.getAccountKey()),
			owners.signingFor(alice.getAccountKey()),alice::signData) {};
		var bobRecord=new ASignedComponent<>(second.cursor().path(bob.getAccountKey()),
			owners.signingFor(bob.getAccountKey()),bob::signData) {};
		var aliceEdits=Timestamped.create(aliceRecord.cursor().withDefault(Maps.empty()),first.getContext()::currentTimestamp);
		var bobEdits=Timestamped.create(bobRecord.cursor().withDefault(Maps.empty()),second.getContext()::currentTimestamp);
		var aliceItems=new SetComponent<CVMLong>(aliceEdits.cursor().path(ITEMS));
		var bobItems=new SetComponent<CVMLong>(bobEdits.cursor().path(ITEMS));
		aliceItems.add(CVMLong.ONE);
		bobItems.add(CVMLong.TWO);
		var state=host.cursor().get();
		assertEquals(CVMLong.create(100),state.get(alice.getAccountKey()).getValue().get(timestamp));
		assertEquals(CVMLong.create(200),state.get(bob.getAccountKey()).getValue().get(timestamp));
		assertTrue(state.get(alice.getAccountKey()).checkSignature());
		assertTrue(state.get(bob.getAccountKey()).checkSignature());
		var wrongSigner=new ASignedComponent<>(second.cursor().path(alice.getAccountKey()),
			owners.signingFor(alice.getAccountKey()),bob::signData) {};
		AHashMap<Keyword,ACell> attempted=Maps.of(timestamp,CVMLong.create(300),ITEMS,Sets.of(3));
		assertThrows(IllegalStateException.class,() -> wrongSigner.cursor().set(attempted));
		assertSame(state,host.cursor().get());
		first.setContext(LatticeContext.create(CVMLong.create(300),null));
		aliceItems.add(CVMLong.TWO);
		assertEquals(CVMLong.create(100),host.cursor().get().get(alice.getAccountKey()).getValue().get(timestamp));
		assertEquals(Sets.of(1,2),aliceItems.get());
	}

	@Test
	public void testRootForkMergeUsesApplicationSignerWithoutLendingItToHost() {
		AKeyPair alice=AKeyPair.createSeeded(6201);
		OwnerKeyLattice<ASet<CVMLong>> lattice=OwnerKeyLattice.create(SetLattice.create());
		var host=LatticeHost.create(lattice.zero(),null);
		var app=LatticeApplication.live(lattice,host);
		app.setContext(LatticeContext.create(null,alice));
		app.merge(lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(1))));
		var fork=new LatticeComponent<>(lattice,app.cursor(),app.getContext());
		fork.cursor().updateAndGet(own -> lattice.merge(app.getContext(),own,lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(2)))));
		assertEquals(Sets.of(1),host.cursor().get().get(alice.getAccountKey()).getValue());
		app.merge(lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(3))));
		fork.sync();
		var signed=host.cursor().get().get(alice.getAccountKey());
		assertEquals(Sets.of(1,2,3),signed.getValue());
		assertTrue(signed.checkSignature());
		var incoming=lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(4)));
		host.merge(incoming);
		assertSame(signed,host.cursor().get().get(alice.getAccountKey()),
			"An unconfigured host does not admit incoming data");
		app.merge(incoming);
		assertEquals(Sets.of(1,2,3,4),host.cursor().get().get(alice.getAccountKey()).getValue());
	}

	@Test
	public void testHostPersistencePreservesOtherApplicationWriteWithoutMerging() {
		AtomicReference<LatticeContext> mergeContext=new AtomicReference<>();
		ALattice<Index<Keyword,ACell>> lattice=new ADelegatingLattice<>(LATTICE) {
			@Override
			public Index<Keyword,ACell> merge(LatticeContext context,
					Index<Keyword,ACell> own, Index<Keyword,ACell> other) {
				mergeContext.set(context);
				return super.merge(context,own,other);
			}
		};
		var host=LatticeHost.create(lattice.zero(),null);
		var first=LatticeApplication.live(lattice,host);
		var second=LatticeApplication.live(lattice,host);
		LatticeContext appPolicy=LatticeContext.create(CVMLong.ONE,null);
		first.setContext(appPolicy);
		first.merge(LATTICE.zero().assoc(ITEMS,Sets.of(1)));
		assertSame(appPolicy,mergeContext.get());
		var published=first.cursor().get();
		mergeContext.set(null);
		host.setPublicationPolicy(value -> {
			new Items(second).add(CVMLong.TWO);
			return value;
		});
		assertSame(published,first.sync());
		assertSame(published,host.sync());
		assertNull(mergeContext.get(),"Host persistence must not invoke a lattice merge");
		assertEquals(Sets.of(1,2),new Items(first).cursor().get());
	}

	@Test
	public void testArbitraryLatticeKeepsOwnValueOnTie() {
		var lattice=CompareLattice.create((AString a, AString b)
			-> Long.compare(a.count(),b.count()));
		var app=LatticeApplication.live(lattice,LatticeHost.create(lattice.zero(),null));
		var own=Strings.create("own");
		assertSame(own,app.merge(own));
		assertSame(own,app.merge(Strings.create("new")));
		assertEquals(Strings.create("longer"),app.merge(Strings.create("longer")));
	}

	@Test
	public void testRootMergeUsesContextAndBoundOwnershipRules() {
		AKeyPair alice=AKeyPair.createSeeded(6201);
		AKeyPair bob=AKeyPair.createSeeded(6202);
		OwnerKeyLattice<ASet<CVMLong>> owners=OwnerKeyLattice.create(SetLattice.create());
		KeyedLattice lattice=KeyedLattice.create(ITEMS,owners);
		var app=LatticeApplication.live(lattice,LatticeHost.create(lattice.zero(),null));
		app.setContext(LatticeContext.create(null,alice));
		Index<AccountKey,SignedData<ASet<CVMLong>>> first=owners.zero()
			.assoc(alice.getAccountKey(),alice.signData(Sets.of(1)));
		app.merge(lattice.zero().assoc(ITEMS,first));
		Index<AccountKey,SignedData<ASet<CVMLong>>> other=owners.zero()
			.assoc(alice.getAccountKey(),alice.signData(Sets.of(2)));
		app.merge(lattice.zero().assoc(ITEMS,other));

		Index<AccountKey,SignedData<ASet<CVMLong>>> merged=app.cursor()
			.<Index<AccountKey,SignedData<ASet<CVMLong>>>>path(ITEMS).get();
		SignedData<ASet<CVMLong>> signed=merged.get(alice.getAccountKey());
		assertEquals(Sets.of(1,2),signed.getValue());
		assertEquals(alice.getAccountKey(),signed.getAccountKey());
		assertTrue(signed.checkSignature());

		var before=app.cursor().get();
		var forged=owners.zero().assoc(alice.getAccountKey(),bob.signData(Sets.of(3)));
		assertSame(before,app.merge(lattice.zero().assoc(ITEMS,forged)));
	}

	@Test
	public void testFailedRegionMergeInstallsNoPartialRoot() {
		KeyedLattice lattice=LATTICE.addLattice(UNKNOWN,SetLattice.create());
		var app=LatticeApplication.live(lattice,LatticeHost.create(lattice.zero(),null));
		var before=app.merge(lattice.zero().assoc(ITEMS,Sets.of(1)));
		var invalid=lattice.zero().assoc(ITEMS,Sets.of(2)).assoc(UNKNOWN,CVMLong.ONE);
		assertThrows(ClassCastException.class,() -> app.merge(invalid));
		assertSame(before,app.cursor().get());
	}

	@Test
	public void testMergeRetriesAfterConcurrentComponentUpdate() throws Exception {
		CountDownLatch merging=new CountDownLatch(1);
		CountDownLatch resume=new CountDownLatch(1);
		AtomicInteger attempts=new AtomicInteger();
		ALattice<Index<Keyword,ACell>> lattice=new ADelegatingLattice<>(LATTICE) {
			@Override
			public Index<Keyword,ACell> merge(LatticeContext context,
					Index<Keyword,ACell> own, Index<Keyword,ACell> other) {
				if (attempts.incrementAndGet()==1) {
					merging.countDown();
					try {
						assertTrue(resume.await(10,TimeUnit.SECONDS));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new AssertionError(e);
					}
				}
				return super.merge(context,own,other);
			}
		};
		var host=LatticeHost.create(lattice.zero(),null);
		var app=LatticeApplication.live(lattice,host);
		Items items=new Items(LatticeApplication.live(lattice,host));
		try (var executor=Executors.newSingleThreadExecutor()) {
			var future=executor.submit(() -> app.merge(LATTICE.zero().assoc(ITEMS,Sets.of(2))));
			try {
				assertTrue(merging.await(10,TimeUnit.SECONDS));
				items.add(CVMLong.ONE);
			} finally {
				resume.countDown();
			}
			assertEquals(LATTICE.zero().assoc(ITEMS,Sets.of(1,2)),future.get(10,TimeUnit.SECONDS));
		}
		assertTrue(attempts.get()>=2,"Merge must retry after the component changed the root");
		assertEquals(Sets.of(1,2),items.cursor().get());
	}
}
