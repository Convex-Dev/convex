package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ASet;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;
import convex.lattice.generic.SetLattice;

/**
 * Tests for lattice cursor functionality.
 *
 * These tests serve as usage examples for the lattice cursor API.
 */
public class LatticeCursorTest {

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
	public void testDescendEmptyReturnsThis() {
		SetLattice<CVMLong> lattice = SetLattice.create();
		RootLatticeCursor<ASet<CVMLong>> root = Cursors.createLattice(lattice, Sets.empty());

		// descend() with no keys should return this
		ALatticeCursor<ASet<CVMLong>> descended = root.descend();
		assertSame(root, descended);
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
}
