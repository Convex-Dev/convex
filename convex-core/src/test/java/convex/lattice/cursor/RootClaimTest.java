package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchStore;
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.generic.MaxLattice;

/** What a root's claim holds back, with writers on real threads ordered by latches. */
class RootClaimTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword X=Keyword.intern("x");
	private static final Keyword Y=Keyword.intern("y");
	private static final AKeyPair KEY=AKeyPair.createSeeded(2101);

	private static Arguments writer(String name,Consumer<Root<AInteger>> action) {
		return Arguments.of(name,action);
	}

	static Stream<Arguments> writers() {
		return Stream.of(
			writer("set",r -> r.set(CVMLong.TWO)),
			writer("getAndSet",r -> r.getAndSet(CVMLong.TWO)),
			writer("compareAndSet",r -> r.compareAndSet(CVMLong.ZERO,CVMLong.TWO)),
			writer("updateAndGet",r -> r.updateAndGet(v -> CVMLong.TWO)),
			writer("getAndUpdate",r -> r.getAndUpdate(v -> CVMLong.TWO)),
			writer("accumulateAndGet",r -> r.accumulateAndGet(CVMLong.TWO,(a,b) -> b)),
			writer("getAndAccumulate",r -> r.getAndAccumulate(CVMLong.TWO,(a,b) -> b)),
			writer("incoming merge",r -> convex.lattice.ComponentTestSupport.merge(r,MaxLattice.INSTANCE,CVMLong.TWO)),
			writer("component publication",r -> {
				var working=new LatticeComponent<>(MaxLattice.INSTANCE,r.fork(),convex.lattice.LatticeContext.EMPTY);
				working.cursor().set(CVMLong.TWO);
				working.sync();
			}));
	}

	@ParameterizedTest(name="{0}")
	@MethodSource("writers")
	void everyWriterWaitsForAClaimOnWhatItChanges(String name,Consumer<Root<AInteger>> action) throws Exception {
		var root=new Root<AInteger>(CVMLong.ZERO);
		CountDownLatch inCallback=new CountDownLatch(1), writerStarted=new CountDownLatch(1);
		AtomicBoolean callbackRunning=new AtomicBoolean();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var writer=executor.submit(() -> {
				await(inCallback);
				writerStarted.countDown();
				action.accept(root);
				assertFalse(callbackRunning.get(),name+" completed during the exclusive callback");
			});
			Transactions.executeExclusively(root,draft -> {
				callbackRunning.set(true);
				try {
					inCallback.countDown();
					await(writerStarted);
					draft.set(CVMLong.ONE);
					assertEquals(CVMLong.ZERO,root.get(),"Staged changes and competing writes stay invisible");
				} finally { callbackRunning.set(false); }
			});
			writer.get(10,TimeUnit.SECONDS);
		}
		assertEquals(name.equals("compareAndSet")?CVMLong.ONE:CVMLong.TWO,root.get());
	}

	private static Root<AHashMap<Keyword,ACell>> tree() {
		return new Root<>(Maps.of(A,Maps.of(X,CVMLong.ZERO,Y,CVMLong.ZERO),B,Maps.of(X,CVMLong.ZERO)));
	}

	@Test void writesThatDoNotPassThroughAClaimedBoundaryCarryOn() {
		var root=tree();
		AForkableCursor<AInteger> claimed=BoundaryCursor.over(root.<AInteger>path(A,X));
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(claimed,draft -> {
				// Each completes inside the callback, so neither waited for the claim.
				complete(other.submit(() -> root.<AInteger>path(A,Y).set(CVMLong.ONE)));
				complete(other.submit(() -> root.<AInteger>path(B,X).set(CVMLong.ONE)));
				draft.set(CVMLong.TWO);
			});
		}
		assertEquals(CVMLong.TWO,claimed.get());
		assertEquals(CVMLong.ONE,root.get(A,Y));
		assertEquals(CVMLong.ONE,root.get(B,X));
	}

	@Test void aClaimFollowsViewsDownToTheRootAndCoversAllOfIt() throws InterruptedException {
		AHashMap<Keyword,ACell> record=Maps.of(X,CVMLong.ZERO,Y,CVMLong.ZERO);
		var root=new Root<AHashMap<Keyword,ACell>>(Maps.of(A,KEY.signData(record),B,CVMLong.ZERO));
		AForkableCursor<AHashMap<Keyword,ACell>> signed=SignedCursor.create(root.<SignedData<AHashMap<Keyword,ACell>>>path(A),KEY);
		AForkableCursor<AInteger> claimed=signed.withDefault(Maps.empty()).path(X);
		Thread[] writers=new Thread[2];
		Transactions.executeExclusively(claimed,draft -> {
			writers[0]=Thread.ofPlatform().start(() -> signed.<AInteger>path(Y).set(CVMLong.ONE));
			writers[1]=Thread.ofPlatform().start(() -> root.<AInteger>path(B).set(CVMLong.ONE));
			for (Thread writer:writers) {
				awaitParked(writer);
				assertTrue(writer.isAlive(),"every write to the root waits for its claim");
			}
			draft.set(CVMLong.TWO);
		});
		for (Thread writer:writers) writer.join();
		assertEquals(Maps.of(X,CVMLong.TWO,Y,CVMLong.ONE),signed.get());
		assertEquals(CVMLong.ONE,root.get(B));
	}

	/** Waits until a thread is parked, as one waiting for a claim is, or has finished. */
	private static void awaitParked(Thread thread) {
		while (thread.isAlive() && thread.getState()!=Thread.State.WAITING) Thread.onSpinWait();
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<Keyword,ACell> copyOfBranch(AHashMap<Keyword,ACell> tree,Keyword key) {
		AHashMap<Keyword,ACell> branch=(AHashMap<Keyword,ACell>)tree.get(key);
		AHashMap<Keyword,ACell> copy=Maps.empty();
		for (var entry:branch.entrySet()) copy=copy.assoc(entry.getKey(),entry.getValue());
		assertEquals(branch,copy);
		assertNotSame(branch,copy);
		return copy;
	}

	/** An equal tree in new cells, as persisting can give. Assoc would keep the existing map. */
	private static AHashMap<Keyword,ACell> inNewCells(AHashMap<Keyword,ACell> tree) {
		return Maps.of(A,copyOfBranch(tree,A),B,copyOfBranch(tree,B));
	}

	@Test void anEqualValueDoesNotWaitAndIsTheValueKept() {
		var root=tree();
		var written=new AtomicReference<AHashMap<Keyword,ACell>>();
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(root.<AHashMap<Keyword,ACell>>path(A),draft -> {
				written.set(result(other.submit(() -> root.updateAndGet(RootClaimTest::inNewCells))));
				assertNotNull(draft.get());
			});
		}
		assertSame(written.get(),root.get(),"Neither commit nor release put the earlier cells back");
	}

	@Test void aNoOpTransactionDoesNotWriteItsInputOverAnEqualValue() {
		var root=tree();
		var written=new AtomicReference<AHashMap<Keyword,ACell>>();
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(root,
				draft -> written.set(result(other.submit(() -> root.updateAndGet(RootClaimTest::inNewCells)))));
		}
		assertSame(written.get(),root.get(),"Exclusive");
		Transactions.execute(root,draft -> written.set(root.updateAndGet(RootClaimTest::inNewCells)));
		assertSame(written.get(),root.get(),"Optimistic");
	}

	@Test void aCommitOverAnEqualValueChangesOnlyItsOwnBranch() {
		var root=tree();
		AForkableCursor<AHashMap<Keyword,ACell>> claimed=root.path(A);
		AHashMap<Keyword,ACell> committed=Maps.of(X,CVMLong.TWO);
		var persisted=new AtomicReference<AHashMap<Keyword,ACell>>();
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(claimed,draft -> {
				persisted.set(result(other.submit(() -> root.persist(RootClaimTest::inNewCells))));
				draft.set(committed);
			});
		}
		assertEquals(committed,claimed.get(),"An equal value is no conflict, so the run commits");
		assertSame(persisted.get().get(B),root.get().get(B),"The persisted cells elsewhere are kept");
	}

	@Test void hostSyncDuringAClaimKeepsThePersistedRoot() throws Exception {
		AHashMap<Keyword,ACell> record=Maps.of(X,Strings.create("not yet stored ".repeat(50)));
		AHashMap<Keyword,ACell> value=Maps.of(A,record);
		var published=new AtomicReference<AHashMap<Keyword,ACell>>();
		try (var store=EtchStore.createTemp("root-claim-kept");var other=Executors.newVirtualThreadPerTaskExecutor()) {
			var host=LatticeHost.create(value,store);
			Transactions.executeExclusively(host.cursor().<AHashMap<Keyword,ACell>>path(A),
				draft -> published.set(result(other.submit(() -> host.sync()))));
			assertEquals(value,published.get());
			assertNotSame(value,published.get(),"Persisting replaced the cells");
			assertSame(published.get(),host.cursor().get(),"The root holds the persisted cells, not the ones the claim saw");
		}
	}

	@Test void hostSyncDoesNotWaitForAClaim() throws Exception {
		AHashMap<Keyword,ACell> record=Maps.of(X,Strings.create("not yet stored ".repeat(50)));
		AHashMap<Keyword,ACell> value=Maps.of(A,record);
		try (var store=EtchStore.createTemp("root-claim-sync");var other=Executors.newVirtualThreadPerTaskExecutor()) {
			var host=LatticeHost.create(value,store);
			Transactions.executeExclusively(host.cursor().<AHashMap<Keyword,ACell>>path(A),draft -> {
				complete(other.submit(() -> host.sync()));
				draft.set(Maps.empty());
			});
			assertEquals(Maps.empty(),host.cursor().get().get(A));
			assertEquals(value,store.getRootData(),"The sync saved the value from before the commit");
		}
	}

	@Test void writerToTheClaimedBranchRecalculatesFromTheCommittedValue() {
		var root=tree();
		AForkableCursor<AInteger> claimed=root.path(A,X);
		CountDownLatch calculated=new CountDownLatch(1);
		var writer=new AtomicReference<Future<?>>();
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(claimed,draft -> {
				writer.set(other.submit(() -> claimed.updateAndGet(value -> {
					calculated.countDown();
					return CVMLong.create(value.longValue()+1);
				})));
				await(calculated);
				draft.set(CVMLong.create(10));
			});
			complete(writer.get());
		}
		assertEquals(CVMLong.create(11),claimed.get(),"Neither change is lost");
	}

	@Test void aRootHoldsOneClaimEvenAcrossUnrelatedBranches() throws Exception {
		var root=tree();
		CountDownLatch inFirst=new CountDownLatch(1), secondStarting=new CountDownLatch(1);
		AtomicBoolean firstRunning=new AtomicBoolean();
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var second=executor.submit(() -> {
				await(inFirst);
				secondStarting.countDown();
				Transactions.executeExclusively(root.<AInteger>path(B,X),draft -> {
					assertFalse(firstRunning.get(),"The second claim waited for the first");
					draft.set(CVMLong.TWO);
				});
			});
			Transactions.executeExclusively(root.<AInteger>path(A,X),draft -> {
				firstRunning.set(true);
				try {
					inFirst.countDown();
					await(secondStarting);
					draft.set(CVMLong.ONE);
				} finally { firstRunning.set(false); }
			});
			second.get(10,TimeUnit.SECONDS);
		}
		assertEquals(CVMLong.ONE,root.get(A,X));
		assertEquals(CVMLong.TWO,root.get(B,X));
	}

	@Test void aFailedCallbackReleasesTheClaimAndChangesNothing() {
		var root=tree();
		var before=root.get();
		var abort=new IllegalArgumentException();
		assertSame(abort,assertThrows(IllegalArgumentException.class,() -> Transactions.executeExclusively(root,draft -> {
			throw abort;
		})));
		assertSame(before,root.get());
		Transactions.executeExclusively(root,draft -> draft.set(Maps.empty()));
		assertEquals(Maps.empty(),root.get(),"The claim was free to take again");
	}

	@Test void aThreadCannotTakeARootsClaimTwice() {
		var root=tree();
		Transactions.executeExclusively(root,draft -> assertThrows(IllegalStateException.class,
			() -> Transactions.executeExclusively(root,inner -> {})));
	}

	@Test void aWriteThroughACapturedLiveCursorDuringTheCallbackIsRefusedAtCommit() {
		var root=tree();
		AForkableCursor<AInteger> claimed=root.path(A,X);
		assertThrows(IllegalStateException.class,() -> Transactions.executeExclusively(claimed,draft -> {
			claimed.set(CVMLong.ONE);
			draft.set(CVMLong.TWO);
		}));
		assertEquals(CVMLong.ONE,claimed.get(),"The callback's own draft was not committed over it");
	}

	@Test void aWriteTheClaimingViewCannotReadWaitsThenLands() throws Exception {
		var root=new Root<ACell>(CVMLong.ZERO);
		ACell unreadable=Strings.create("not a number");
		AForkableCursor<AInteger> claimed=ProjectionCursor.create(root,stored -> {
			if (stored instanceof AInteger number) return number;
			throw new IllegalStateException("Not a number");
		},(stored,value) -> value);
		Thread[] writer=new Thread[1];
		Transactions.executeExclusively(claimed,draft -> {
			writer[0]=Thread.ofPlatform().start(() -> root.set(unreadable));
			awaitParked(writer[0]);
			assertTrue(writer[0].isAlive(),"The write waits for the release");
			draft.set(CVMLong.ONE);
		});
		writer[0].join();
		assertSame(unreadable,root.get(),"After the release the write lands as if unclaimed");
	}

	@Test void persistingAForkCarriesOnDuringAClaim() throws Exception {
		var root=tree();
		var original=root.get();
		var fork=root.fork();
		fork.<AInteger>path(A,Y).set(CVMLong.ONE);
		var persisted=new CopyOnWriteArrayList<ACell>();
		Persister<AHashMap<Keyword,ACell>> persister=value -> {
			persisted.add(value);
			return value;
		};
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(fork.<AInteger>path(A,X),draft -> {
				// Completes inside the callback, so it did not wait for the claim.
				complete(other.submit(() -> fork.persist(persister)));
				assertDoesNotThrow(() -> fork.persist(persister),"The claim's owner can persist too");
				draft.set(CVMLong.TWO);
			});
		}
		assertTrue(persisted.contains(original),"The reconciled baseline was persisted");
		fork.sync((mine,theirs) -> mine);
		assertEquals(CVMLong.TWO,root.get(A,X));
		assertEquals(CVMLong.ONE,root.get(A,Y));
	}

	private static void complete(Future<?> work) {
		result(work);
	}

	private static <T> T result(Future<T> work) {
		try { return work.get(10,TimeUnit.SECONDS); }
		catch (Exception e) { throw new AssertionError(e); }
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
