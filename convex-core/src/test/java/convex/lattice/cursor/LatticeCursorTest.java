package convex.lattice.cursor;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.MaxLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.kv.KVDatabase;

/**
 * Tests for lattice cursor functionality.
 *
 * These tests serve as usage examples for the lattice cursor API.
 */
public class LatticeCursorTest {

	@Test
	public void testPathCursorOwnsPathArray() {
		KeyedLattice lattice=KeyedLattice.create(
			Keywords.FOO,MaxLattice.INSTANCE,
			Keywords.BAR,MaxLattice.INSTANCE);
		Root<Index<Keyword,ACell>> root=Cursors.root(lattice.zero());
		ACell[] path={Keywords.FOO};
		AForkableCursor<AInteger> cursor=root.path(path);
		path[0]=Keywords.BAR;

		cursor.set(CVMLong.create(3));

		assertEquals(CVMLong.create(3),root.get().get(Keywords.FOO));
		assertNull(root.get().get(Keywords.BAR));
	}

	// ===== Standard cursor operation tests =====

	@Test
	public void testRootCursorOperations() {
		MaxLattice lattice = MaxLattice.INSTANCE;
		Root<AInteger> root = Cursors.root(null);
		doIntCursorTest(root);
	}

	@Test
	public void testWorkingStateCursorOperations() {
		MaxLattice lattice = MaxLattice.INSTANCE;
		Root<AInteger> root = Cursors.root(null);
		var forkComponent=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AInteger> fork=forkComponent.cursor();
		doIntCursorTest(fork);
	}

	/**
	 * A completed update on a long-lived fork must be visible when another thread
	 * creates a fresh cursor on that same fork. The futures provide the
	 * cross-thread hand-off without relying on timing.
	 */
	@Test
	public void testForkPathReadAfterCommitAcrossThreads() throws Exception {
		SetLattice<CVMLong> setLattice = SetLattice.create();
		MapLattice<Keyword,ASet<CVMLong>> lattice = MapLattice.create(setLattice);
		Root<AHashMap<Keyword,ASet<CVMLong>>> root =
				Cursors.root(Maps.empty());
		var forkComponent=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword,ASet<CVMLong>>> fork=forkComponent.cursor();

		try (ExecutorService writer = Executors.newSingleThreadExecutor();
				ExecutorService reader = Executors.newSingleThreadExecutor()) {
			for (long i = 1; i <= 1_000; i++) {
				CVMLong committed = CVMLong.create(i);
				ASet<CVMLong> written=writer.submit(() -> fork.<ASet<CVMLong>>path(Keywords.FOO)
						.updateAndGet(values -> (values==null?Sets.<CVMLong>empty():values).include(committed))).get();

				ASet<CVMLong> observed = reader.submit(() ->
						fork.<ASet<CVMLong>>path(Keywords.FOO).get()).get();
				assertSame(written,observed,"Fresh cursor must read the published set");
				assertTrue(observed.contains(committed),
						() -> "Published set is missing " + committed + "; count=" + observed.count()
							+ "; value hash=" + committed.getHash() + "; set=" + observed);
			}
		}
	}

	/**
	 * Tests all standard ACursor operations on an integer cursor.
	 * Adapted from CursorTest.doIntCursorTest for lattice cursors.
	 */
	private void doIntCursorTest(ACursor<? extends AInteger> cursor) {
		@SuppressWarnings("unchecked")
		ACursor<AInteger> root = (ACursor<AInteger>) cursor;

		assertEquals("nil", root.toString());

		CVMLong TWO = CVMLong.create(2);

		root.set(1);
		assertCVMEquals(1, root.get());

		assertFalse(root.compareAndSet(CVMLong.ZERO, CVMLong.ZERO));
		assertTrue(root.compareAndSet(CVMLong.ONE, CVMLong.ZERO));
		assertCVMEquals(0, root.get());

		assertCVMEquals(0, root.getAndSet(TWO));
		assertSame(TWO, root.get());

		assertEquals(TWO, root.getAndUpdate(a -> a.inc()));
		assertCVMEquals(4, root.updateAndGet(a -> a.inc()));

		assertCVMEquals(4, root.getAndAccumulate(CVMLong.ONE, (a, b) -> a.add(b)));
		assertCVMEquals(7, root.accumulateAndGet(TWO, (a, b) -> a.add(b)));

		assertEquals("7", root.toString());
	}

	// ===== Fork/Sync pattern tests =====

	@Test
	public void testBasicForkSync() {
		// Create root lattice cursor with SetLattice
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// Fork for modifications
		var forkComponent=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<ASet<CVMLong>> fork=forkComponent.cursor();
		assertNotNull(fork);
		assertEquals(Sets.empty(), fork.get());

		// Make changes to fork
		CVMLong ONE = CVMLong.ONE;
		CVMLong TWO = CVMLong.create(2);
		fork.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(ONE));
		fork.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(TWO));

		// Root should still be empty
		assertEquals(Sets.empty(), root.get());

		// Fork should have both items
		ASet<CVMLong> forkSet = fork.get();
		assertTrue(forkSet.contains(ONE));
		assertTrue(forkSet.contains(TWO));

		// Sync back to root
		ASet<CVMLong> synced = forkComponent.sync();
		assertEquals(forkSet, synced);

		// Root should now have both items
		ASet<CVMLong> rootSet = root.get();
		assertTrue(rootSet.contains(ONE));
		assertTrue(rootSet.contains(TWO));
	}

	@Test
	public void testConcurrentForks() {
		// Create root with SetLattice
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// Create two concurrent forks
		var fork1Component=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<ASet<CVMLong>> fork1=fork1Component.cursor();
		var fork2Component=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<ASet<CVMLong>> fork2=fork2Component.cursor();

		// Independent modifications
		CVMLong A = CVMLong.create(10);
		CVMLong B = CVMLong.create(20);
		fork1.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(A));
		fork2.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(B));

		// Sync fork1
		fork1Component.sync();
		assertTrue(root.get().contains(A));

		// Sync fork2 - should merge with A
		fork2Component.sync();
		ASet<CVMLong> result = root.get();
		assertTrue(result.contains(A), "Root should contain A after both syncs");
		assertTrue(result.contains(B), "Root should contain B after both syncs");
	}

	@Test
	public void testNestedForks() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// Outer fork
		var outerComponent=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<ASet<CVMLong>> outer=outerComponent.cursor();
		outer.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));

		// Inner fork (nested)
		var innerComponent=new LatticeComponent<>(lattice,outer,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<ASet<CVMLong>> inner=innerComponent.cursor();
		inner.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(2)));

		// Sync inner to outer
		innerComponent.sync();
		assertTrue(outer.get().contains(CVMLong.ONE));
		assertTrue(outer.get().contains(CVMLong.create(2)));

		// More changes to outer
		outer.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(3)));

		// Sync outer to root
		outerComponent.sync();
		ASet<CVMLong> result = root.get();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.create(2)));
		assertTrue(result.contains(CVMLong.create(3)));
	}

	@Test
	public void testMergeExternal() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// Set initial value
		root.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));

		// Merge external value
		ASet<CVMLong> external = Sets.of(CVMLong.create(2), CVMLong.create(3));
		convex.lattice.ComponentTestSupport.merge(root,lattice,external);

		// Should have all values
		ASet<CVMLong> result = root.get();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.create(2)));
		assertTrue(result.contains(CVMLong.create(3)));
	}

	@Test
	public void testSyncUpdatesLocalState() {
		// This tests the important caveat that sync() updates the fork's local state
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// Fork and add A
		var fork1Component=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<ASet<CVMLong>> fork1=fork1Component.cursor();
		fork1.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));

		// Meanwhile, add B directly to root
		root.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(2)));

		// Sync fork1 - it should now have BOTH A and B
		fork1Component.sync();

		ASet<CVMLong> forkValue = fork1.get();
		assertTrue(forkValue.contains(CVMLong.ONE), "Fork should have A");
		assertTrue(forkValue.contains(CVMLong.create(2)), "Fork should have B after sync");
	}

	/**
	 * LWW merge positions are not equivalent: "own" is the value being
	 * applied by a local edit, and ties prefer it. When a fork syncs, its
	 * local edits must take the own position against a concurrent parent
	 * advance — otherwise an edit re-stamped in the same millisecond as the
	 * value it replaces (e.g. a deletion under a whole-value LWW wrapper)
	 * loses the tie to a stale snapshot and silently reverts.
	 */
	@Test
	public void testForkSyncLocalEditWinsTimestampTie() {
		Keyword TS = Keyword.intern("timestamp");
		Keyword V = Keyword.intern("v");
		ALattice<ACell> lattice = convex.lattice.generic.LWWLattice.INSTANCE;

		AHashMap<Keyword, ACell> original = Maps.of(TS, CVMLong.create(100), V, Strings.create("original"));
		Root<ACell> root = Cursors.root(original);

		var forkComponent=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);

		AForkableCursor<ACell> fork=forkComponent.cursor();

		// Parent advances concurrently to a DIFFERENT value with the SAME
		// timestamp — e.g. a propagator merge-back re-applying a snapshot.
		// (A distinct object, so sync cannot take its fast path.)
		AHashMap<Keyword, ACell> stale = Maps.of(TS, CVMLong.create(100), V, Strings.create("stale"));
		root.set(stale);

		// The fork applies a local edit at the same (tying) timestamp
		AHashMap<Keyword, ACell> edited = Maps.of(TS, CVMLong.create(100), V, Strings.create("edited"));
		fork.set(edited);

		forkComponent.sync();

		assertSame(edited, root.get(),
			"the fork's local edit must win a timestamp tie against a concurrent parent advance");
	}

	@Test
	public void testFastPathOptimization() {
		// When parent hasn't changed, sync should use fast path
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		var forkComponent=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);

		AForkableCursor<ASet<CVMLong>> fork=forkComponent.cursor();
		ASet<CVMLong> newValue = Sets.of(CVMLong.ONE);
		fork.set(newValue);

		// Sync should just adopt our value (fast path)
		ASet<CVMLong> synced = forkComponent.sync();
		assertSame(newValue, synced);
		assertSame(newValue, root.get());
	}



	// ===== Host publication layering tests =====
	//
	// These tests lock in the contract that persistence layers depend on: cursor
	// writes and component publication never run the host's publication policy.
	// Only an explicit host sync does, and it publishes exactly one snapshot.

	@Test
	public void testHostSyncRunsPublicationPolicy() {
		LatticeHost<ASet<CVMLong>> host = LatticeHost.create(Sets.<CVMLong>empty(), null);

		AtomicInteger callCount = new AtomicInteger();
		AtomicReference<ASet<CVMLong>> received = new AtomicReference<>();
		host.attachPublisher(value -> {
			callCount.incrementAndGet();
			received.set(value);
			return value;
		});

		// Make a write directly on the host cursor
		host.cursor().updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));

		// Publication must NOT run on writes — only on sync()
		assertEquals(0, callCount.get(), "publication must not run on cursor writes");

		// Now sync explicitly — publication runs exactly once with the current value
		host.sync();
		assertEquals(1, callCount.get(), "publication should run once on host.sync()");
		assertNotNull(received.get());
		assertTrue(received.get().contains(CVMLong.ONE));
	}

	@Test
	public void testConcurrentRootSyncCallbacksAreSerialised() throws Exception {
		assertRootSyncCallbacksAreSerialised(false);
	}

	@Test
	public void testPerCallPublicationSharesRootSyncOrdering() throws Exception {
		assertRootSyncCallbacksAreSerialised(true);
	}

	private void assertRootSyncCallbacksAreSerialised(boolean perCallPublication) throws Exception {
		LatticeHost<ASet<CVMLong>> root = LatticeHost.create(Sets.of(CVMLong.ONE), null);

		CVMLong concurrentValue = CVMLong.create(2);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		AtomicInteger callCount = new AtomicInteger();
		AtomicInteger activeCallbacks = new AtomicInteger();
		AtomicInteger maxActiveCallbacks = new AtomicInteger();
		AtomicReference<ASet<CVMLong>> firstSnapshot = new AtomicReference<>();
		AtomicReference<ASet<CVMLong>> secondSnapshot = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Function<ASet<CVMLong>,ASet<CVMLong>> callback = snapshot -> {
			int call = callCount.incrementAndGet();
			int active = activeCallbacks.incrementAndGet();
			maxActiveCallbacks.updateAndGet(previous -> Math.max(previous, active));
			try {
				if (call == 1) {
					firstSnapshot.set(snapshot);
					firstEntered.countDown();
					if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
						throw new AssertionError("Timed out waiting to release first sync callback");
					}
				} else if (call == 2) {
					secondSnapshot.set(snapshot);
					secondEntered.countDown();
				} else {
					throw new AssertionError("Unexpected sync callback " + call);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while coordinating sync callbacks", e);
			} finally {
				activeCallbacks.decrementAndGet();
			}
			return snapshot;
		};
		root.attachPublisher(callback);

		Runnable sync = () -> {
			try {
				root.sync();
			} catch (Throwable t) {
				failure.compareAndSet(null, t);
			}
		};

		Thread first = new Thread(sync, "root-sync-first");
		first.start();
		assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "First sync callback did not start");

		// Advance the host root while the first callback is blocked on its older
		// snapshot. The second caller must wait, then capture this newer root.
		root.cursor().updateAndGet(values -> values.include(concurrentValue));

		Thread second = new Thread(() -> {
			secondStarted.countDown();
			if (perCallPublication) {
				try {
					root.sync(callback);
				} catch (Throwable t) {
					failure.compareAndSet(null,t);
				}
			} else {
				sync.run();
			}
		}, "root-sync-second");
		second.start();
		assertTrue(secondStarted.await(5, TimeUnit.SECONDS), "Second sync caller did not start");

		boolean overlapped;
		try {
			// The first callback remains deliberately blocked throughout this window.
			overlapped = secondEntered.await(250, TimeUnit.MILLISECONDS);
		} finally {
			releaseFirst.countDown();
			first.join(5_000);
			second.join(5_000);
		}

		assertFalse(first.isAlive(), "First sync caller did not finish");
		assertFalse(second.isAlive(), "Second sync caller did not finish");
		assertNull(failure.get(), () -> "Sync caller failed: " + failure.get());
		assertFalse(overlapped, "Second sync callback entered while the first callback was active");
		assertEquals(1, maxActiveCallbacks.get(), "Root sync callbacks must not overlap");
		assertEquals(2, callCount.get());

		assertTrue(firstSnapshot.get().contains(CVMLong.ONE));
		assertFalse(firstSnapshot.get().contains(concurrentValue));
		assertTrue(secondSnapshot.get().contains(CVMLong.ONE));
		assertTrue(secondSnapshot.get().contains(concurrentValue),
			"Waiting sync caller must capture the root after the preceding sync completes");
		assertTrue(root.cursor().get().contains(concurrentValue), "Concurrent root update must survive both syncs");
	}

	/**
	 * A sync publishes one immutable snapshot. A write racing that publication belongs
	 * to the next snapshot: it must survive in the cursor, but must not be included in
	 * the store-backed value returned by this sync.
	 */
	@Test
	public void testConcurrentRootWriteDoesNotChangePublishedSyncResult() {
		ASet<CVMLong> published = Sets.of(CVMLong.ONE);
		ASet<CVMLong> concurrent = published.include(CVMLong.TWO);
		LatticeHost<ASet<CVMLong>> host = LatticeHost.create(published, null);

		host.attachPublisher(snapshot -> {
			assertSame(published, snapshot);
			host.cursor().set(concurrent);
			return snapshot;
		});

		ASet<CVMLong> result = host.sync();

		assertSame(published, result,
			"sync must return the exact snapshot completed by its publication callback");
		assertSame(concurrent, host.cursor().get(),
			"the concurrent local write must remain pending in the host cursor");
	}

	/**
	 * When publication returns an equivalent value backed by another store, a
	 * concurrent local value remains the merge's own argument. This preserves its
	 * exact object/ref identity instead of importing equivalent foreign refs.
	 */
	@Test
	public void testConcurrentRootWriteRetainsOwnRefIdentity() throws Exception {
		Blob source = Blobs.createRandom(400);
		try (MemoryStore localStore = new MemoryStore();
				MemoryStore foreignStore = new MemoryStore()) {
			ASet<Blob> local = Cells.announce(Sets.of(source), r -> {}, localStore);
			Blob foreignBlob = foreignStore.decode(source.getEncoding());
			ASet<Blob> foreign = Cells.announce(Sets.of(foreignBlob), r -> {}, foreignStore);

			assertEquals(local, foreign);
			assertNotSame(local, foreign);

			LatticeHost<ASet<Blob>> host = LatticeHost.create(Sets.<Blob>empty(), null);
			host.attachPublisher(snapshot -> {
				host.cursor().set(local);
				return foreign;
			});

			ASet<Blob> result = host.sync();

			assertSame(foreign, result, "sync returns the value actually published");
			assertSame(local, host.cursor().get(),
				"current local state must be own so an equivalent foreign value cannot replace its refs");
		}
	}

	@Test
	public void testComponentSyncDoesNotRunHostPublication() {
		// CONTRACT: component publication writes into the host cursor by CAS and
		// never runs the host's publication policy. Persistence layers call
		// host.sync() explicitly after component syncs to persist.
		SetLattice<CVMLong> lattice = SetLattice.create();
		LatticeHost<ASet<CVMLong>> host = LatticeHost.create(Sets.<CVMLong>empty(), null);

		AtomicInteger hostSyncCount = new AtomicInteger();
		host.attachPublisher(value -> {
			hostSyncCount.incrementAndGet();
			return value;
		});

		// Create working state, write to it, sync the component
		var forkComponent=new LatticeComponent<>(lattice,host.cursor(),convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<ASet<CVMLong>> fork=forkComponent.cursor();
		fork.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));
		forkComponent.sync();

		// Component sync must NOT have run host publication
		assertEquals(0, hostSyncCount.get(),
			"forkComponent.sync() must not run the host's publication policy");

		// But the write IS visible at the host cursor after forkComponent.sync()
		assertTrue(host.cursor().get().contains(CVMLong.ONE),
			"forkComponent.sync() must make writes visible in the host cursor");

		// Calling host.sync() explicitly DOES run publication
		host.sync();
		assertEquals(1, hostSyncCount.get(),
			"host.sync() called explicitly runs publication — this is the persistence trigger");
	}

	@Test
	public void testPathDerivedCursorWritesPropagateToParentRoot() {
		// CONTRACT: cursor.path(...) returns a view, not a fork. Writes through
		// the path-derived cursor go directly to the parent's storage.
		// Persistence layers rely on this: a single onSync registration on the
		// root captures all path-derived writes (e.g. DLFS subtree writes).
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// path() with empty keys returns this — that's already covered by testPathEmptyReturnsThis.
		// What we want to test is that a non-trivial path-derived cursor's writes
		// land at the root. SetLattice doesn't have nested paths, so we use
		// a MapLattice for this test.
		SetLattice<CVMLong> values = SetLattice.create();
		MapLattice<AString, ASet<CVMLong>> mapLattice = MapLattice.create(values);
		Root<AHashMap<AString, ASet<CVMLong>>> mapRoot =
			Cursors.root(Maps.empty());

		// Derive a path cursor for key "users"
		AString key = Strings.create("users");
		AForkableCursor<ASet<CVMLong>> userCursor = mapRoot.path(key);

		// Write through the path cursor
		userCursor.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));

		// The write must be visible at the root via the same key
		AHashMap<AString, ASet<CVMLong>> rootMap = mapRoot.get();
		assertNotNull(rootMap);
		ASet<CVMLong> users = rootMap.get(key);
		assertNotNull(users, "path-derived write must land at the parent root");
		assertTrue(users.contains(CVMLong.ONE));
	}

	@Test
	public void testPathDerivedWriteIsPublishedByExplicitHostSync() {
		// Combination test: path-derived write, then explicit host.sync() — publication runs.
		LatticeHost<AHashMap<AString, ASet<CVMLong>>> host =
			LatticeHost.<AHashMap<AString, ASet<CVMLong>>>create(Maps.empty(), null);

		AtomicInteger callCount = new AtomicInteger();
		host.attachPublisher(value -> { callCount.incrementAndGet(); return value; });

		// Write through a path-derived view
		AForkableCursor<ASet<CVMLong>> pathCursor = host.cursor().path(Strings.create("k"));
		pathCursor.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));

		// Path write should not run publication (writes never do)
		assertEquals(0, callCount.get());

		// Explicit host.sync() runs publication
		host.sync();
		assertEquals(1, callCount.get());

		// And the write is in the value the policy saw
		assertTrue(host.cursor().get().get(Strings.create("k")).contains(CVMLong.ONE));
	}

	@Test
	public void testForkIsIndependentOfParentUntilSynced() {
		// CONTRACT: writes to a fork are invisible at the parent until forkComponent.sync().
		// Persistence layers rely on this asymmetry — a fork can accumulate writes
		// without triggering persistence on every individual write.
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		var forkComponent=new LatticeComponent<>(lattice,root,convex.lattice.LatticeContext.EMPTY);

		AForkableCursor<ASet<CVMLong>> fork=forkComponent.cursor();

		// Write to fork
		fork.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.ONE));

		// Parent must NOT see the write yet
		assertFalse(root.get().contains(CVMLong.ONE),
			"fork writes must be invisible at parent until sync");

		// After sync, parent sees it
		forkComponent.sync();
		assertTrue(root.get().contains(CVMLong.ONE),
			"after forkComponent.sync(), parent sees the write");
	}

	@Test
	public void testCreateWithZeroValue() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(lattice.zero());

		// Should use lattice's zero value (empty set)
		assertEquals(Sets.empty(), root.get());
	}

	@Test
	public void testPathEmptyReturnsThis() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// path() with no keys should return this
		AForkableCursor<ASet<CVMLong>> navigated = root.path();
		assertSame(root, navigated);
	}

	@Test
	public void testStandardCursorOperations() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		Root<ASet<CVMLong>> root = Cursors.root(Sets.empty());

		// Test set/get
		ASet<CVMLong> val1 = Sets.of(CVMLong.ONE);
		root.set(val1);
		assertEquals(val1, root.get());

		// Test getAndSet
		ASet<CVMLong> val2 = Sets.of(CVMLong.create(2));
		ASet<CVMLong> old = root.getAndSet(val2);
		assertEquals(val1, old);
		assertEquals(val2, root.get());

		// Test compareAndSet
		ASet<CVMLong> val3 = Sets.of(CVMLong.create(3));
		assertTrue(root.compareAndSet(val2, val3));
		assertEquals(val3, root.get());

		// Test updateAndGet
		ASet<CVMLong> updated = root.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(4)));
		assertTrue(updated.contains(CVMLong.create(3)));
		assertTrue(updated.contains(CVMLong.create(4)));
	}

	// ===== Complex concurrent modification tests =====

	/**
	 * Tests concurrent modifications from multiple sources:
	 * - Direct root modifications
	 * - PathCursor modifications
	 * - Component working-state modifications with sync
	 *
	 * Uses a MapLattice<Keyword, SetLattice<CVMLong>> to test that modifications
	 * to one key don't overwrite modifications to other keys.
	 */
	@Test
	public void testConcurrentPathAndForkModifications() {
		// Create a map lattice where values are sets (grow-only)
		SetLattice<CVMLong> valueLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(valueLattice);

		// Initial state: {:a #{1}, :b #{10}}
		AHashMap<Keyword, ASet<CVMLong>> initial = Maps.of(
			Keywords.FOO, Sets.of(CVMLong.ONE),
			Keywords.BAR, Sets.of(CVMLong.create(10))
		);
		Root<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.root(initial);

		// Create a PathCursor to modify :a directly on root
		PathCursor<ASet<CVMLong>> pathA = new PathCursor<>(root, Keywords.FOO);

		// Fork the root for isolated modifications
		var forkComponent=new LatticeComponent<>(mapLattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork=forkComponent.cursor();

		// Create a PathCursor on the fork to modify :b
		PathCursor<ASet<CVMLong>> forkPathB = new PathCursor<>(fork, Keywords.BAR);

		// === Concurrent modifications ===

		// 1. Modify :a via pathA on root - add 2
		pathA.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(2)));

		// 2. Modify :b via forkPathB on fork - add 20
		forkPathB.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(20)));

		// 3. Also modify :a on fork directly - add 3
		fork.updateAndGet(map -> map.assoc(Keywords.FOO,
			((ASet<CVMLong>) map.get(Keywords.FOO)).include(CVMLong.create(3))));

		// Check intermediate states
		// Root :a should have {1, 2}
		ASet<CVMLong> rootA = (ASet<CVMLong>) root.get().get(Keywords.FOO);
		assertTrue(rootA.contains(CVMLong.ONE), "Root :a should have 1");
		assertTrue(rootA.contains(CVMLong.create(2)), "Root :a should have 2");
		assertFalse(rootA.contains(CVMLong.create(3)), "Root :a should NOT have 3 yet (fork hasn't synced)");

		// Root :b should still be {10} (fork hasn't synced)
		ASet<CVMLong> rootB = (ASet<CVMLong>) root.get().get(Keywords.BAR);
		assertTrue(rootB.contains(CVMLong.create(10)), "Root :b should have 10");
		assertFalse(rootB.contains(CVMLong.create(20)), "Root :b should NOT have 20 yet");

		// Fork :a should have {1, 3} (doesn't see root's 2)
		ASet<CVMLong> forkA = (ASet<CVMLong>) fork.get().get(Keywords.FOO);
		assertTrue(forkA.contains(CVMLong.ONE), "Fork :a should have 1");
		assertTrue(forkA.contains(CVMLong.create(3)), "Fork :a should have 3");
		assertFalse(forkA.contains(CVMLong.create(2)), "Fork :a should NOT have 2 yet");

		// Fork :b should have {10, 20}
		ASet<CVMLong> forkB = (ASet<CVMLong>) fork.get().get(Keywords.BAR);
		assertTrue(forkB.contains(CVMLong.create(10)), "Fork :b should have 10");
		assertTrue(forkB.contains(CVMLong.create(20)), "Fork :b should have 20");

		// === Sync fork back to root ===
		forkComponent.sync();

		// After sync, root should have merged values:
		// :a = {1, 2, 3} (1 from initial, 2 from pathA, 3 from fork)
		// :b = {10, 20} (10 from initial, 20 from fork)
		AHashMap<Keyword, ASet<CVMLong>> finalRoot = root.get();

		ASet<CVMLong> finalA = (ASet<CVMLong>) finalRoot.get(Keywords.FOO);
		assertTrue(finalA.contains(CVMLong.ONE), "Final :a should have 1");
		assertTrue(finalA.contains(CVMLong.create(2)), "Final :a should have 2 (from pathA)");
		assertTrue(finalA.contains(CVMLong.create(3)), "Final :a should have 3 (from fork)");

		ASet<CVMLong> finalB = (ASet<CVMLong>) finalRoot.get(Keywords.BAR);
		assertTrue(finalB.contains(CVMLong.create(10)), "Final :b should have 10");
		assertTrue(finalB.contains(CVMLong.create(20)), "Final :b should have 20 (from fork)");
	}

	/**
	 * Tests that forking a fork and syncing in sequence preserves all modifications
	 * through the chain, including modifications at different paths.
	 */
	@Test
	public void testNestedForksWithPaths() {
		SetLattice<CVMLong> valueLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(valueLattice);

		Root<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.root(Maps.empty());

		// Level 1 fork - add :a => #{1}
		var fork1Component=new LatticeComponent<>(mapLattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork1=fork1Component.cursor();
		fork1.updateAndGet(map -> map.assoc(Keywords.FOO, Sets.of(CVMLong.ONE)));

		// Level 2 fork (from fork1) - add :b => #{2}
		var fork2Component=new LatticeComponent<>(mapLattice,fork1,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork2=fork2Component.cursor();
		fork2.updateAndGet(map -> map.assoc(Keywords.BAR, Sets.of(CVMLong.create(2))));

		// Level 3 fork (from fork2) - modify :a to add 3
		var fork3Component=new LatticeComponent<>(mapLattice,fork2,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork3=fork3Component.cursor();
		PathCursor<ASet<CVMLong>> fork3PathA = new PathCursor<>(fork3, Keywords.FOO);
		fork3PathA.updateAndGet(set -> (set == null) ? Sets.of(CVMLong.create(3)) : set.include(CVMLong.create(3)));

		// Meanwhile, modify fork1 directly - add :c => #{100}
		fork1.updateAndGet(map -> map.assoc(Keywords.BAZ, Sets.of(CVMLong.create(100))));

		// Root should still be empty
		assertEquals(Maps.empty(), root.get());

		// Sync chain: fork3 -> fork2 -> fork1 -> root
		fork3Component.sync();
		fork2Component.sync();
		fork1Component.sync();

		// Verify all modifications made it to root
		AHashMap<Keyword, ASet<CVMLong>> finalRoot = root.get();

		// :a should have {1, 3}
		ASet<CVMLong> finalA = (ASet<CVMLong>) finalRoot.get(Keywords.FOO);
		assertNotNull(finalA, ":a should exist");
		assertTrue(finalA.contains(CVMLong.ONE), ":a should have 1 (from fork1)");
		assertTrue(finalA.contains(CVMLong.create(3)), ":a should have 3 (from fork3)");

		// :b should have {2}
		ASet<CVMLong> finalB = (ASet<CVMLong>) finalRoot.get(Keywords.BAR);
		assertNotNull(finalB, ":b should exist");
		assertTrue(finalB.contains(CVMLong.create(2)), ":b should have 2 (from fork2)");

		// :c should have {100}
		ASet<CVMLong> finalC = (ASet<CVMLong>) finalRoot.get(Keywords.BAZ);
		assertNotNull(finalC, ":c should exist");
		assertTrue(finalC.contains(CVMLong.create(100)), ":c should have 100 (from fork1)");
	}

	/**
	 * Tests multiple forks modifying the same path and ensures lattice merge
	 * combines all values correctly.
	 */
	@Test
	public void testConcurrentForksModifyingSamePath() {
		SetLattice<CVMLong> valueLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(valueLattice);

		AHashMap<Keyword, ASet<CVMLong>> initial = Maps.of(Keywords.FOO, Sets.empty());
		Root<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.root(initial);

		// Create 3 concurrent forks, each modifying :a via path
		var fork1Component=new LatticeComponent<>(mapLattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork1=fork1Component.cursor();
		var fork2Component=new LatticeComponent<>(mapLattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork2=fork2Component.cursor();
		var fork3Component=new LatticeComponent<>(mapLattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork3=fork3Component.cursor();

		PathCursor<ASet<CVMLong>> path1 = new PathCursor<>(fork1, Keywords.FOO);
		PathCursor<ASet<CVMLong>> path2 = new PathCursor<>(fork2, Keywords.FOO);
		PathCursor<ASet<CVMLong>> path3 = new PathCursor<>(fork3, Keywords.FOO);

		// Each fork adds different values to :a
		path1.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(1)).include(CVMLong.create(2)));
		path2.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(2)).include(CVMLong.create(3)));
		path3.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(3)).include(CVMLong.create(4)));

		// Sync all forks in arbitrary order
		fork2Component.sync();
		fork1Component.sync();
		fork3Component.sync();

		// All values should be merged: {1, 2, 3, 4}
		ASet<CVMLong> result = (ASet<CVMLong>) root.get().get(Keywords.FOO);
		assertTrue(result.contains(CVMLong.create(1)), "Should have 1");
		assertTrue(result.contains(CVMLong.create(2)), "Should have 2");
		assertTrue(result.contains(CVMLong.create(3)), "Should have 3");
		assertTrue(result.contains(CVMLong.create(4)), "Should have 4");
		assertEquals(4L, result.count(), "Should have exactly 4 elements");
	}

	/**
	 * Tests that direct path modifications on root interleaved with fork syncs
	 * correctly merge without losing data.
	 */
	@Test
	public void testInterleavedPathModificationsAndSyncs() {
		SetLattice<CVMLong> valueLattice = SetLattice.create();
		MapLattice<Keyword, ASet<CVMLong>> mapLattice = MapLattice.create(valueLattice);

		AHashMap<Keyword, ASet<CVMLong>> initial = Maps.of(
			Keywords.FOO, Sets.empty(),
			Keywords.BAR, Sets.empty()
		);
		Root<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.root(initial);

		// Fork and modify :a
		var fork1Component=new LatticeComponent<>(mapLattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork1=fork1Component.cursor();
		PathCursor<ASet<CVMLong>> fork1PathA = new PathCursor<>(fork1, Keywords.FOO);
		fork1PathA.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(1)));

		// Direct path modification on root - modify :b
		PathCursor<ASet<CVMLong>> rootPathB = new PathCursor<>(root, Keywords.BAR);
		rootPathB.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(10)));

		// Sync fork1
		fork1Component.sync();

		// Create another fork and modify :a again
		var fork2Component=new LatticeComponent<>(mapLattice,root,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, ASet<CVMLong>>> fork2=fork2Component.cursor();
		PathCursor<ASet<CVMLong>> fork2PathA = new PathCursor<>(fork2, Keywords.FOO);
		fork2PathA.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(2)));

		// Meanwhile, modify :b on root again
		rootPathB.updateAndGet(set -> (set==null?Sets.<CVMLong>empty():set).include(CVMLong.create(20)));

		// Sync fork2
		fork2Component.sync();

		// Final state should have:
		// :a = {1, 2}
		// :b = {10, 20}
		AHashMap<Keyword, ASet<CVMLong>> finalRoot = root.get();

		ASet<CVMLong> finalA = (ASet<CVMLong>) finalRoot.get(Keywords.FOO);
		assertTrue(finalA.contains(CVMLong.create(1)), ":a should have 1");
		assertTrue(finalA.contains(CVMLong.create(2)), ":a should have 2");

		ASet<CVMLong> finalB = (ASet<CVMLong>) finalRoot.get(Keywords.BAR);
		assertTrue(finalB.contains(CVMLong.create(10)), ":b should have 10");
		assertTrue(finalB.contains(CVMLong.create(20)), ":b should have 20");
	}

	// ===== Child component fork/sync tests =====

	/**
	 * Tests that component working state over descended cursors at different
	 * positions, each synced, preserves all changes. The sync goes through assocIn
	 * on the parent, so modifications at one key must not clobber modifications at
	 * another.
	 */
	@Test
	public void testDescendedForkSyncPreservesSiblings() {
		MapLattice<Keyword, AInteger> mapLattice = MapLattice.create(MaxLattice.INSTANCE);
		AHashMap<Keyword, AInteger> initial = Maps.of(
			Keywords.FOO, CVMLong.create(1),
			Keywords.BAR, CVMLong.create(10),
			Keywords.BAZ, CVMLong.create(100)
		);
		Root<AHashMap<Keyword, AInteger>> root = Cursors.root(initial);

		// Descend to :foo and :bar via path(), fork each
		AForkableCursor<AInteger> fooCursor = root.path(Keywords.FOO);
		AForkableCursor<AInteger> barCursor = root.path(Keywords.BAR);
		var fooForkComponent=new LatticeComponent<>(MaxLattice.INSTANCE,fooCursor,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AInteger> fooFork=fooForkComponent.cursor();
		var barForkComponent=new LatticeComponent<>(MaxLattice.INSTANCE,barCursor,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AInteger> barFork=barForkComponent.cursor();

		// Modify each fork independently
		fooFork.set(CVMLong.create(5));
		barFork.set(CVMLong.create(50));

		// Also modify :baz directly on root while forks are outstanding
		root.updateAndGet(m -> m.assoc(Keywords.BAZ, CVMLong.create(200)));

		// Sync both forks
		fooForkComponent.sync();
		barForkComponent.sync();

		// All three keys should reflect their respective updates
		AHashMap<Keyword, AInteger> result = root.get();
		assertCVMEquals(5, result.get(Keywords.FOO));
		assertCVMEquals(50, result.get(Keywords.BAR));
		assertCVMEquals(200, result.get(Keywords.BAZ));
	}

	/**
	 * Tests component fork/sync over a descended cursor: sync should write back
	 * only at the descended path, preserving sibling changes in the parent.
	 *
	 * Uses a two-level map where the inner map has no sub-lattice. Navigating
	 * into an inner map key gives a null-lattice cursor since MaxLattice.path()
	 * returns null.
	 */
	@Test
	public void testNullLatticeForkSyncPreservesSiblings() {
		// Outer: MapLattice<Keyword, AHashMap<Keyword, CVMLong>>
		// Inner: MapLattice<Keyword, CVMLong> with MaxLattice children
		MapLattice<Keyword, AInteger> innerLattice = MapLattice.create(MaxLattice.INSTANCE);
		MapLattice<Keyword, AHashMap<Keyword, AInteger>> outerLattice = MapLattice.create(innerLattice);

		AHashMap<Keyword, AInteger> innerMap = Maps.of(
			Keywords.FOO, CVMLong.create(1),
			Keywords.BAR, CVMLong.create(10)
		);
		AHashMap<Keyword, AHashMap<Keyword, AInteger>> initial = Maps.of(Keywords.BAZ, innerMap);
		Root<AHashMap<Keyword, AHashMap<Keyword, AInteger>>> root =
			Cursors.root(initial);

		// path(:baz, :foo) → child cursor with MaxLattice (leaf, non-null)
		// path(:baz, :foo, :x) would give null lattice, but MaxLattice values
		// are integers, not maps — can't navigate deeper meaningfully.
		//
		// Instead: path(:baz) gives MapLattice, then fork two cursors at that
		// level and modify different inner keys. This tests assocIn preservation
		// through the child cursor → PathCursor → assocIn chain.

		// Navigate to :baz (inner map level, has MapLattice)
		AForkableCursor<AHashMap<Keyword, AInteger>> bazCursor = root.path(Keywords.BAZ);

		// Fork and modify :foo within the inner map
		var forkComponent=new LatticeComponent<>(innerLattice,bazCursor,convex.lattice.LatticeContext.EMPTY);
		AForkableCursor<AHashMap<Keyword, AInteger>> fork=forkComponent.cursor();
		fork.updateAndGet(m -> m.assoc(Keywords.FOO, CVMLong.create(99)));

		// Meanwhile, modify :bar in the inner map via a separate path
		AForkableCursor<AInteger> barCursor = root.path(Keywords.BAZ, Keywords.BAR);
		barCursor.set(CVMLong.create(77));

		// Sync the fork — lattice merge should combine both changes
		forkComponent.sync();

		// Both :foo and :bar should reflect their updates
		AHashMap<Keyword, AInteger> result = (AHashMap<Keyword, AInteger>) root.get().get(Keywords.BAZ);
		assertCVMEquals(99, result.get(Keywords.FOO));
		assertCVMEquals(77, result.get(Keywords.BAR));
	}

	// ===== KV Database with lattice path resolution =====

	/**
	 * Demonstrates the full lifecycle of a KV database accessed through
	 * JSON path resolution and lattice cursors:
	 *
	 * 1. Resolve a JSON path to canonical CVM keys
	 * 2. Descend a root cursor to the :kv level (OwnerLattice)
	 * 3. Populate a KVDatabase using its high-level API
	 * 4. Merge the database's owner-map replica into the cursor
	 * 5. A second node reads the data back from the root cursor
	 *
	 * Lattice path: :kv → OwnerLattice(MapLattice(KVStoreLattice))
	 *   owner-key → signed(db-name → KV store)
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testKVDatabaseWithLatticePath() {
		// Create root lattice cursor for the full lattice state
		Root<Index<Keyword, ACell>> root = Cursors.root(Lattice.ROOT.zero());

		// === Step 1: Resolve JSON path to :kv level ===
		// :kv is OwnerLattice(MapLattice(KVStoreLattice))
		// Database names are per-owner (inside the signed value), not global keys
		ACell[] kvPath = Lattice.ROOT.resolvePath(Strings.create("kv"));
		assertNotNull(kvPath, "Path ['kv'] should resolve");
		assertEquals(Keywords.KV, kvPath[0]);

		// Round-trip: CVM key back to JSON
		assertEquals(Strings.create("kv"), ALattice.toJSONKey(kvPath[0]));

		// === Step 2: Descend root cursor to the :kv level (OwnerLattice) ===
		ALattice<ACell> kvLattice = Lattice.ROOT.path(kvPath);
		AForkableCursor<ACell> kvCursor = root.path(kvPath);
		assertNotNull(kvCursor);

		// === Step 3: Create and populate a KV database via the high-level API ===
		AKeyPair nodeA = AKeyPair.generate();
		KVDatabase dbA = KVDatabase.create("mydb", nodeA);
		dbA.kv().set("greeting", Strings.create("hello"));
		dbA.kv().set("count", CVMLong.create(42));
		dbA.kv().hset("user", "name", Strings.create("Alice"));

		// Verify data via the database API
		assertEquals(Strings.create("hello"), dbA.kv().get("greeting"));
		assertEquals(CVMLong.create(42), dbA.kv().get("count"));
		assertEquals(Strings.create("Alice"), dbA.kv().hget("user", "name"));

		// === Step 4: Merge database owner-map replica into cursor at :kv ===
		// exportReplica returns {accountKey → signed({dbName → kvStore})}
		// This is the OwnerLattice value shape
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> replicaA =
			dbA.exportReplica();
		convex.lattice.ComponentTestSupport.merge(kvCursor,kvLattice, replicaA);

		// === Step 5: Verify changes reflected in the root cursor ===
		Index<Keyword, ACell> rootState = root.get();
		assertNotNull(rootState.get(Keywords.KV), "Root should have :kv section");

		// The :kv value is the owner map: {accountKey → signed({dbName → kvStore})}
		ACell kvValue = rootState.get(Keywords.KV);
		assertNotNull(kvValue, "Owner map should exist at :kv");

		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> ownerMap =
			(AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>>) kvValue;
		SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>> signedA =
			ownerMap.get(nodeA.getAccountKey());
		assertNotNull(signedA, "Owner map should contain node A's signed state");
		assertTrue(signedA.checkSignature(), "Signed state should have valid signature");

		// The signed value contains {dbName → kvStore}
		AHashMap<AString, Index<AString, AVector<ACell>>> dbMapA = signedA.getValue();
		assertNotNull(dbMapA.get(Strings.create("mydb")), "Signed value should contain 'mydb' database");

		// === Step 6: A second node reads data from the root cursor ===
		AKeyPair nodeB = AKeyPair.generate();
		KVDatabase dbB = KVDatabase.create("mydb", nodeB);

		// Node B merges replicas from the owner map
		long merged = dbB.mergeReplicas(ownerMap);
		assertEquals(1, merged, "Should merge one replica from node A");

		// Node B now sees all of node A's data
		assertEquals(Strings.create("hello"), dbB.kv().get("greeting"));
		assertEquals(CVMLong.create(42), dbB.kv().get("count"));
		assertEquals(Strings.create("Alice"), dbB.kv().hget("user", "name"));

		// === Step 7: Node B adds data and merges back into root ===
		dbB.kv().set("source", Strings.create("nodeB"));
		AHashMap<ACell, SignedData<AHashMap<AString, Index<AString, AVector<ACell>>>>> replicaB =
			dbB.exportReplica();
		convex.lattice.ComponentTestSupport.merge(kvCursor,kvLattice, replicaB);

		// Root cursor now has both nodes' signed replicas
		ACell updatedKvValue = root.get().get(Keywords.KV);
		AHashMap<ACell, ?> finalOwners = (AHashMap<ACell, ?>) updatedKvValue;
		assertNotNull(finalOwners.get(nodeA.getAccountKey()), "Should have node A's replica");
		assertNotNull(finalOwners.get(nodeB.getAccountKey()), "Should have node B's replica");
	}
}
