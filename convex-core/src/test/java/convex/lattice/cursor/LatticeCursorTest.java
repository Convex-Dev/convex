package convex.lattice.cursor;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.Lattice;
import convex.lattice.LatticeContext;
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

	// ===== Standard cursor operation tests =====

	@Test
	public void testRootCursorOperations() {
		MaxLattice lattice = MaxLattice.INSTANCE;
		RootLatticeCursor<AInteger> root = Cursors.createLattice(lattice, null);
		doIntCursorTest(root);
	}

	@Test
	public void testForkedCursorOperations() {
		MaxLattice lattice = MaxLattice.INSTANCE;
		RootLatticeCursor<AInteger> root = Cursors.createLattice(lattice, null);
		ALatticeCursor<AInteger> fork = root.fork();
		doIntCursorTest(fork);
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
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// Fork for modifications
		ALatticeCursor<ASet<CVMLong>> fork = root.fork();
		assertNotNull(fork);
		assertEquals(Sets.empty(), fork.get());

		// Make changes to fork
		CVMLong ONE = CVMLong.ONE;
		CVMLong TWO = CVMLong.create(2);
		fork.updateAndGet(set -> set.include(ONE));
		fork.updateAndGet(set -> set.include(TWO));

		// Root should still be empty
		assertEquals(Sets.empty(), root.get());

		// Fork should have both items
		ASet<CVMLong> forkSet = fork.get();
		assertTrue(forkSet.contains(ONE));
		assertTrue(forkSet.contains(TWO));

		// Sync back to root
		ASet<CVMLong> synced = fork.sync();
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
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// Create two concurrent forks
		ALatticeCursor<ASet<CVMLong>> fork1 = root.fork();
		ALatticeCursor<ASet<CVMLong>> fork2 = root.fork();

		// Independent modifications
		CVMLong A = CVMLong.create(10);
		CVMLong B = CVMLong.create(20);
		fork1.updateAndGet(set -> set.include(A));
		fork2.updateAndGet(set -> set.include(B));

		// Sync fork1
		fork1.sync();
		assertTrue(root.get().contains(A));

		// Sync fork2 - should merge with A
		fork2.sync();
		ASet<CVMLong> result = root.get();
		assertTrue(result.contains(A), "Root should contain A after both syncs");
		assertTrue(result.contains(B), "Root should contain B after both syncs");
	}

	@Test
	public void testNestedForks() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// Outer fork
		ALatticeCursor<ASet<CVMLong>> outer = root.fork();
		outer.updateAndGet(set -> set.include(CVMLong.ONE));

		// Inner fork (nested)
		ALatticeCursor<ASet<CVMLong>> inner = outer.fork();
		inner.updateAndGet(set -> set.include(CVMLong.create(2)));

		// Sync inner to outer
		inner.sync();
		assertTrue(outer.get().contains(CVMLong.ONE));
		assertTrue(outer.get().contains(CVMLong.create(2)));

		// More changes to outer
		outer.updateAndGet(set -> set.include(CVMLong.create(3)));

		// Sync outer to root
		outer.sync();
		ASet<CVMLong> result = root.get();
		assertTrue(result.contains(CVMLong.ONE));
		assertTrue(result.contains(CVMLong.create(2)));
		assertTrue(result.contains(CVMLong.create(3)));
	}

	@Test
	public void testMergeExternal() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// Set initial value
		root.updateAndGet(set -> set.include(CVMLong.ONE));

		// Merge external value
		ASet<CVMLong> external = Sets.of(CVMLong.create(2), CVMLong.create(3));
		root.merge(external);

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
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// Fork and add A
		ALatticeCursor<ASet<CVMLong>> fork1 = root.fork();
		fork1.updateAndGet(set -> set.include(CVMLong.ONE));

		// Meanwhile, add B directly to root
		root.updateAndGet(set -> set.include(CVMLong.create(2)));

		// Sync fork1 - it should now have BOTH A and B
		fork1.sync();

		ASet<CVMLong> forkValue = fork1.get();
		assertTrue(forkValue.contains(CVMLong.ONE), "Fork should have A");
		assertTrue(forkValue.contains(CVMLong.create(2)), "Fork should have B after sync");
	}

	@Test
	public void testSyncPreservesConcurrentWrites() throws InterruptedException {
		// Regression for a race where sync() was unconditionally overwriting the
		// local cursor with its own (possibly stale) snapshot, clobbering writes
		// made by other threads during sync. The fix uses CAS-first-then-merge.
		//
		// Reproduction strategy: N writer threads continuously add unique values
		// while M syncer threads continuously sync. With the buggy code, writes
		// landing between sync's read and sync's set are clobbered. The larger
		// the thread count and iteration count, the higher the probability a
		// write falls in the vulnerable window.
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());
		ALatticeCursor<ASet<CVMLong>> fork = root.fork();

		final int writerCount = 4;
		final int perWriter = 2000;
		final int syncerCount = 2;
		final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.atomic.AtomicReference<Throwable> error =
			new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.atomic.AtomicBoolean writersDone =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		java.util.List<Thread> threads = new java.util.ArrayList<>();

		// Writers: each adds [writerId*perWriter, writerId*perWriter+perWriter)
		for (int w = 0; w < writerCount; w++) {
			final int writerId = w;
			Thread t = new Thread(() -> {
				try {
					start.await();
					for (int i = 0; i < perWriter; i++) {
						final long v = writerId * perWriter + i;
						fork.updateAndGet(set -> set.include(CVMLong.create(v)));
					}
				} catch (Throwable ex) { error.set(ex); }
			});
			threads.add(t);
		}

		// Syncers: call sync() in a tight loop until writers finish
		for (int s = 0; s < syncerCount; s++) {
			Thread t = new Thread(() -> {
				try {
					start.await();
					while (!writersDone.get()) {
						fork.sync();
					}
				} catch (Throwable ex) { error.set(ex); }
			});
			threads.add(t);
		}

		for (Thread t : threads) t.start();
		start.countDown();

		// Wait for writers only
		for (int i = 0; i < writerCount; i++) threads.get(i).join();
		writersDone.set(true);

		// Wait for syncers
		for (int i = writerCount; i < threads.size(); i++) threads.get(i).join();

		// Final sync to propagate anything still in-flight
		fork.sync();

		if (error.get() != null) throw new AssertionError("Thread failure", error.get());

		// Every value any writer added must be present
		ASet<CVMLong> finalFork = fork.get();
		ASet<CVMLong> finalRoot = root.get();
		int totalValues = writerCount * perWriter;
		int forkMissing = 0, rootMissing = 0;
		for (int i = 0; i < totalValues; i++) {
			CVMLong v = CVMLong.create(i);
			if (!finalFork.contains(v)) forkMissing++;
			if (!finalRoot.contains(v)) rootMissing++;
		}
		assertEquals(0, forkMissing, "Fork lost " + forkMissing + "/" + totalValues + " writes");
		assertEquals(0, rootMissing, "Root lost " + rootMissing + "/" + totalValues + " writes");
	}

	@Test
	public void testFastPathOptimization() {
		// When parent hasn't changed, sync should use fast path
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		ALatticeCursor<ASet<CVMLong>> fork = root.fork();
		ASet<CVMLong> newValue = Sets.of(CVMLong.ONE);
		fork.set(newValue);

		// Sync should just adopt our value (fast path)
		ASet<CVMLong> synced = fork.sync();
		assertSame(newValue, synced);
		assertSame(newValue, root.get());
	}

	@Test
	public void testContext() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// Default context
		assertEquals(LatticeContext.EMPTY, root.getContext());

		// With context
		LatticeContext ctx = LatticeContext.create(CVMLong.create(1000), null);
		ALatticeCursor<ASet<CVMLong>> withCtx = root.withContext(ctx);
		assertEquals(ctx, withCtx.getContext());

		// Fork inherits context
		ALatticeCursor<ASet<CVMLong>> fork = withCtx.fork();
		assertEquals(ctx, fork.getContext());
	}

	@Test
	public void testRootSyncIsNoop() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		ASet<CVMLong> initial = Sets.of(CVMLong.ONE);
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, initial);

		// Sync on root should be no-op, just return current value
		ASet<CVMLong> synced = root.sync();
		assertSame(initial, synced);
	}

	@Test
	public void testCreateWithZeroValue() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice);

		// Should use lattice's zero value (empty set)
		assertEquals(Sets.empty(), root.get());
	}

	@Test
	public void testPathEmptyReturnsThis() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// path() with no keys should return this
		ALatticeCursor<ASet<CVMLong>> navigated = root.path();
		assertSame(root, navigated);
	}

	@Test
	public void testStandardCursorOperations() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

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
		ASet<CVMLong> updated = root.updateAndGet(set -> set.include(CVMLong.create(4)));
		assertTrue(updated.contains(CVMLong.create(3)));
		assertTrue(updated.contains(CVMLong.create(4)));
	}

	// ===== Complex concurrent modification tests =====

	/**
	 * Tests concurrent modifications from multiple sources:
	 * - Direct root modifications
	 * - PathCursor modifications
	 * - Forked cursor modifications with sync
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
		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.createLattice(mapLattice, initial);

		// Create a PathCursor to modify :a directly on root
		PathCursor<ASet<CVMLong>> pathA = new PathCursor<>(root, Keywords.FOO);

		// Fork the root for isolated modifications
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork = root.fork();

		// Create a PathCursor on the fork to modify :b
		PathCursor<ASet<CVMLong>> forkPathB = new PathCursor<>(fork, Keywords.BAR);

		// === Concurrent modifications ===

		// 1. Modify :a via pathA on root - add 2
		pathA.updateAndGet(set -> set.include(CVMLong.create(2)));

		// 2. Modify :b via forkPathB on fork - add 20
		forkPathB.updateAndGet(set -> set.include(CVMLong.create(20)));

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
		fork.sync();

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

		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.createLattice(mapLattice, Maps.empty());

		// Level 1 fork - add :a => #{1}
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork1 = root.fork();
		fork1.updateAndGet(map -> map.assoc(Keywords.FOO, Sets.of(CVMLong.ONE)));

		// Level 2 fork (from fork1) - add :b => #{2}
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork2 = fork1.fork();
		fork2.updateAndGet(map -> map.assoc(Keywords.BAR, Sets.of(CVMLong.create(2))));

		// Level 3 fork (from fork2) - modify :a to add 3
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork3 = fork2.fork();
		PathCursor<ASet<CVMLong>> fork3PathA = new PathCursor<>(fork3, Keywords.FOO);
		fork3PathA.updateAndGet(set -> (set == null) ? Sets.of(CVMLong.create(3)) : set.include(CVMLong.create(3)));

		// Meanwhile, modify fork1 directly - add :c => #{100}
		fork1.updateAndGet(map -> map.assoc(Keywords.BAZ, Sets.of(CVMLong.create(100))));

		// Root should still be empty
		assertEquals(Maps.empty(), root.get());

		// Sync chain: fork3 -> fork2 -> fork1 -> root
		fork3.sync();
		fork2.sync();
		fork1.sync();

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
		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.createLattice(mapLattice, initial);

		// Create 3 concurrent forks, each modifying :a via path
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork1 = root.fork();
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork2 = root.fork();
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork3 = root.fork();

		PathCursor<ASet<CVMLong>> path1 = new PathCursor<>(fork1, Keywords.FOO);
		PathCursor<ASet<CVMLong>> path2 = new PathCursor<>(fork2, Keywords.FOO);
		PathCursor<ASet<CVMLong>> path3 = new PathCursor<>(fork3, Keywords.FOO);

		// Each fork adds different values to :a
		path1.updateAndGet(set -> set.include(CVMLong.create(1)).include(CVMLong.create(2)));
		path2.updateAndGet(set -> set.include(CVMLong.create(2)).include(CVMLong.create(3)));
		path3.updateAndGet(set -> set.include(CVMLong.create(3)).include(CVMLong.create(4)));

		// Sync all forks in arbitrary order
		fork2.sync();
		fork1.sync();
		fork3.sync();

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
		RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root = Cursors.createLattice(mapLattice, initial);

		// Fork and modify :a
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork1 = root.fork();
		PathCursor<ASet<CVMLong>> fork1PathA = new PathCursor<>(fork1, Keywords.FOO);
		fork1PathA.updateAndGet(set -> set.include(CVMLong.create(1)));

		// Direct path modification on root - modify :b
		PathCursor<ASet<CVMLong>> rootPathB = new PathCursor<>(root, Keywords.BAR);
		rootPathB.updateAndGet(set -> set.include(CVMLong.create(10)));

		// Sync fork1
		fork1.sync();

		// Create another fork and modify :a again
		ALatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> fork2 = root.fork();
		PathCursor<ASet<CVMLong>> fork2PathA = new PathCursor<>(fork2, Keywords.FOO);
		fork2PathA.updateAndGet(set -> set.include(CVMLong.create(2)));

		// Meanwhile, modify :b on root again
		rootPathB.updateAndGet(set -> set.include(CVMLong.create(20)));

		// Sync fork2
		fork2.sync();

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

	// ===== Descended cursor fork/sync tests =====

	/**
	 * Tests that forking descended cursors at different positions and syncing
	 * each preserves all changes. The sync goes through assocIn on the parent,
	 * so modifications at one key must not clobber modifications at another.
	 */
	@Test
	public void testDescendedForkSyncPreservesSiblings() {
		MapLattice<Keyword, AInteger> mapLattice = MapLattice.create(MaxLattice.INSTANCE);
		AHashMap<Keyword, AInteger> initial = Maps.of(
			Keywords.FOO, CVMLong.create(1),
			Keywords.BAR, CVMLong.create(10),
			Keywords.BAZ, CVMLong.create(100)
		);
		RootLatticeCursor<AHashMap<Keyword, AInteger>> root = Cursors.createLattice(mapLattice, initial);

		// Descend to :foo and :bar via path(), fork each
		ALatticeCursor<AInteger> fooCursor = root.path(Keywords.FOO);
		ALatticeCursor<AInteger> barCursor = root.path(Keywords.BAR);
		ALatticeCursor<AInteger> fooFork = fooCursor.fork();
		ALatticeCursor<AInteger> barFork = barCursor.fork();

		// Modify each fork independently
		fooFork.set(CVMLong.create(5));
		barFork.set(CVMLong.create(50));

		// Also modify :baz directly on root while forks are outstanding
		root.updateAndGet(m -> m.assoc(Keywords.BAZ, CVMLong.create(200)));

		// Sync both forks
		fooFork.sync();
		barFork.sync();

		// All three keys should reflect their respective updates
		AHashMap<Keyword, AInteger> result = root.get();
		assertCVMEquals(5, result.get(Keywords.FOO));
		assertCVMEquals(50, result.get(Keywords.BAR));
		assertCVMEquals(200, result.get(Keywords.BAZ));
	}

	/**
	 * Tests null-lattice descended cursor fork/sync: sync should write back
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
		RootLatticeCursor<AHashMap<Keyword, AHashMap<Keyword, AInteger>>> root =
			Cursors.createLattice(outerLattice, initial);

		// path(:baz, :foo) → DescendedCursor with MaxLattice (leaf, non-null)
		// path(:baz, :foo, :x) would give null lattice, but MaxLattice values
		// are integers, not maps — can't navigate deeper meaningfully.
		//
		// Instead: path(:baz) gives MapLattice, then fork two cursors at that
		// level and modify different inner keys. This tests assocIn preservation
		// through the DescendedCursor → PathCursor → assocIn chain.

		// Navigate to :baz (inner map level, has MapLattice)
		ALatticeCursor<AHashMap<Keyword, AInteger>> bazCursor = root.path(Keywords.BAZ);

		// Fork and modify :foo within the inner map
		ALatticeCursor<AHashMap<Keyword, AInteger>> fork = bazCursor.fork();
		fork.updateAndGet(m -> m.assoc(Keywords.FOO, CVMLong.create(99)));

		// Meanwhile, modify :bar in the inner map via a separate path
		ALatticeCursor<AInteger> barCursor = root.path(Keywords.BAZ, Keywords.BAR);
		barCursor.set(CVMLong.create(77));

		// Sync the fork — lattice merge should combine both changes
		fork.sync();

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
		RootLatticeCursor<Index<Keyword, ACell>> root = Cursors.createLattice(Lattice.ROOT);

		// === Step 1: Resolve JSON path to :kv level ===
		// :kv is OwnerLattice(MapLattice(KVStoreLattice))
		// Database names are per-owner (inside the signed value), not global keys
		ACell[] kvPath = Lattice.ROOT.resolvePath(Strings.create("kv"));
		assertNotNull(kvPath, "Path ['kv'] should resolve");
		assertEquals(Keywords.KV, kvPath[0]);

		// Round-trip: CVM key back to JSON
		assertEquals(Strings.create("kv"), ALattice.toJSONKey(kvPath[0]));

		// === Step 2: Descend root cursor to the :kv level (OwnerLattice) ===
		ALatticeCursor<ACell> kvCursor = root.path(kvPath);
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
		kvCursor.merge(replicaA);

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
		kvCursor.merge(replicaB);

		// Root cursor now has both nodes' signed replicas
		ACell updatedKvValue = root.get().get(Keywords.KV);
		AHashMap<ACell, ?> finalOwners = (AHashMap<ACell, ?>) updatedKvValue;
		assertNotNull(finalOwners.get(nodeA.getAccountKey()), "Should have node A's replica");
		assertNotNull(finalOwners.get(nodeB.getAccountKey()), "Should have node B's replica");
	}
}
