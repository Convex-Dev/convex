package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
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
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.StampingLattice;
import convex.lattice.cursor.ALatticeCursor;

public class ALatticeApplicationTest {

	private static final Keyword ITEMS=Keyword.intern("items");
	private static final Keyword UNKNOWN=Keyword.intern("unknown");
	private static final KeyedLattice LATTICE=KeyedLattice.create(ITEMS,SetLattice.create());

	/** No component definitions or factories are needed for root merging. */
	private static final class Application<V extends ACell> extends ALatticeApplication<V> {
		Application(RootComponent<V> host) {
			super(host);
		}
	}

	private static final class Items extends ALatticeComponent<ASet<CVMLong>> {
		Items(ALatticeApplication<Index<Keyword,ACell>> application) {
			super(application.cursor().path(ITEMS));
		}

		void add(CVMLong value) {
			cursor.updateAndGet(items -> items.include(value));
		}
	}

	@Test
	public void testOpenMergeAndPublicationPreserveRetainedRootWithoutComponents() throws Exception {
		MemoryStore store=new MemoryStore();
		Index<Keyword,ACell> retained=LATTICE.zero()
			.assoc(ITEMS,Sets.of(2)).assoc(UNKNOWN,CVMLong.ONE);
		store.setRootData(retained);
		Application<Index<Keyword,ACell>> app=new Application<>(RootComponent.open(LATTICE,store));
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
		assertEquals(merged,store.getRootData());
		Application<Index<Keyword,ACell>> reopened=new Application<>(RootComponent.open(LATTICE,store));
		assertEquals(merged,reopened.cursor().get());
	}

	@Test
	public void testHostedApplicationsShareStateAndPreserveUnknownOwnRegions() {
		Index<Keyword,ACell> initial=LATTICE.zero().assoc(UNKNOWN,CVMLong.ONE);
		RootComponent<Index<Keyword,ACell>> host=RootComponent.create(LATTICE,initial,new MemoryStore());
		Application<Index<Keyword,ACell>> first=new Application<>(host);
		Application<Index<Keyword,ACell>> second=new Application<>(host);
		AtomicInteger publications=new AtomicInteger();
		host.setPublicationPolicy(value -> {
			publications.incrementAndGet();
			return value;
		});

		first.merge(LATTICE.zero().assoc(ITEMS,Sets.of(1)).assoc(UNKNOWN,CVMLong.TWO));
		second.merge(LATTICE.zero().assoc(ITEMS,Sets.of(2)));
		assertNotSame(host.cursor(),first.cursor());
		assertNotSame(first.cursor(),second.cursor());
		assertSame(host.cursor().get(),first.cursor().get());
		assertSame(first.cursor().get(),second.cursor().get());
		assertEquals(initial.assoc(ITEMS,Sets.of(1,2)),host.cursor().get());
		assertEquals(0,publications.get());
		second.sync();
		assertEquals(1,publications.get());
	}

	@Test
	public void testContextInheritanceAndIsolation() {
		var host=RootComponent.create(LATTICE,new MemoryStore());
		var first=new Application<>(host);
		var second=new Application<>(host);
		var child=first.cursor().path(ITEMS);
		LatticeContext hostPolicy=LatticeContext.create(CVMLong.ONE,null);
		LatticeContext appPolicy=LatticeContext.create(CVMLong.TWO,null);
		host.cursor().setContext(hostPolicy);
		assertSame(hostPolicy,child.getContext());
		first.cursor().setContext(appPolicy);
		assertSame(appPolicy,child.getContext());
		assertSame(hostPolicy,second.cursor().getContext());
		assertSame(hostPolicy,host.cursor().getContext());
		first.cursor().setContext(null);
		assertSame(hostPolicy,child.getContext());
		host.cursor().setContext(LatticeContext.EMPTY);
		assertSame(LatticeContext.EMPTY,child.getContext());
	}

	@Test
	public void testIndependentSigningAndStampingThroughExistingChildren() {
		AKeyPair alice=AKeyPair.createSeeded(6201);
		AKeyPair bob=AKeyPair.createSeeded(6202);
		Keyword timestamp=LWWLattice.KEY_TIMESTAMP;
		ALattice<Index<Keyword,ACell>> record=StampingLattice.create(
			LWWLattice.create(LATTICE,v -> ((CVMLong)v.get(timestamp)).longValue()),
			(v,ts) -> v.assoc(timestamp,ts));
		var owners=OwnerKeyLattice.create(record);
		var host=RootComponent.create(owners,new MemoryStore());
		var first=new Application<>(host);
		var second=new Application<>(host);
		ALatticeCursor<ASet<CVMLong>> aliceItems=first.cursor().path(alice.getAccountKey(),Keywords.VALUE,ITEMS);
		ALatticeCursor<ASet<CVMLong>> bobItems=second.cursor().path(bob.getAccountKey(),Keywords.VALUE,ITEMS);
		first.cursor().setContext(LatticeContext.create(CVMLong.create(100),alice));
		second.cursor().setContext(LatticeContext.create(CVMLong.create(200),bob));
		aliceItems.updateAndGet(v -> v.include(CVMLong.ONE));
		bobItems.updateAndGet(v -> v.include(CVMLong.TWO));
		var state=host.cursor().get();
		assertEquals(CVMLong.create(100),state.get(alice.getAccountKey()).getValue().get(timestamp));
		assertEquals(CVMLong.create(200),state.get(bob.getAccountKey()).getValue().get(timestamp));
		assertTrue(state.get(alice.getAccountKey()).checkSignature());
		assertTrue(state.get(bob.getAccountKey()).checkSignature());
		assertSame(LatticeContext.EMPTY,host.cursor().getContext());
		assertThrows(IllegalStateException.class,() -> second.cursor()
			.<ASet<CVMLong>>path(alice.getAccountKey(),Keywords.VALUE,ITEMS).set(Sets.of(3)));
		assertSame(state,host.cursor().get());
		first.cursor().setContext(LatticeContext.create(CVMLong.create(300),alice));
		aliceItems.updateAndGet(v -> v.include(CVMLong.TWO));
		assertEquals(CVMLong.create(300),host.cursor().get().get(alice.getAccountKey()).getValue().get(timestamp));
		assertEquals(Sets.of(1,2),aliceItems.get());
	}

	@Test
	public void testRootForkMergeUsesApplicationSignerWithoutLendingItToHost() {
		AKeyPair alice=AKeyPair.createSeeded(6201);
		OwnerKeyLattice<ASet<CVMLong>> lattice=OwnerKeyLattice.create(SetLattice.create());
		var host=RootComponent.create(lattice,new MemoryStore());
		var app=new Application<>(host);
		app.cursor().setContext(LatticeContext.create(null,alice));
		app.merge(lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(1))));
		var fork=app.cursor().fork();
		fork.merge(lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(2))));
		assertEquals(Sets.of(1),host.cursor().get().get(alice.getAccountKey()).getValue());
		app.merge(lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(3))));
		fork.sync();
		var signed=host.cursor().get().get(alice.getAccountKey());
		assertEquals(Sets.of(1,2,3),signed.getValue());
		assertTrue(signed.checkSignature());
		var incoming=lattice.zero().assoc(alice.getAccountKey(),alice.signData(Sets.of(4)));
		host.cursor().merge(incoming);
		assertSame(signed,host.cursor().get().get(alice.getAccountKey()),
			"Host cannot synthesise a signed union using an application's signer");
		app.merge(incoming);
		assertEquals(Sets.of(1,2,3,4),host.cursor().get().get(alice.getAccountKey()).getValue());
	}

	@Test
	public void testPublicationRetryUsesHostContextAndPreservesOtherApplicationWrite() {
		AtomicReference<LatticeContext> mergeContext=new AtomicReference<>();
		ALattice<Index<Keyword,ACell>> lattice=new ADelegatingLattice<>(LATTICE) {
			@Override
			public Index<Keyword,ACell> merge(LatticeContext context,
					Index<Keyword,ACell> own, Index<Keyword,ACell> other) {
				mergeContext.set(context);
				return super.merge(context,own,other);
			}
		};
		var host=RootComponent.create(lattice,new MemoryStore());
		var first=new Application<>(host);
		var second=new Application<>(host);
		LatticeContext appPolicy=LatticeContext.create(CVMLong.ONE,null);
		first.cursor().setContext(appPolicy);
		first.merge(LATTICE.zero().assoc(ITEMS,Sets.of(1)));
		assertSame(appPolicy,mergeContext.get());
		var published=first.cursor().get();
		host.setPublicationPolicy(value -> {
			new Items(second).add(CVMLong.TWO);
			return value;
		});
		assertSame(published,first.cursor().sync());
		assertSame(LatticeContext.EMPTY,mergeContext.get());
		assertEquals(Sets.of(1,2),new Items(first).cursor().get());
	}

	@Test
	public void testArbitraryLatticeKeepsOwnValueOnTie() {
		var lattice=CompareLattice.create((AString a, AString b)
			-> Long.compare(a.count(),b.count()));
		var app=new Application<>(RootComponent.create(lattice,new MemoryStore()));
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
		var app=new Application<>(RootComponent.create(lattice,new MemoryStore()));
		app.cursor().setContext(LatticeContext.create(null,alice));
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
		var app=new Application<>(RootComponent.create(lattice,new MemoryStore()));
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
		var host=RootComponent.create(lattice,new MemoryStore());
		var app=new Application<>(host);
		Items items=new Items(new Application<>(host));
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
