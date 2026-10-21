package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ASet;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.lattice.LatticeContext;
import convex.lattice.generic.SetLattice;

/** A fork is an isolated working copy reconciled by the merge function it was created with. */
public class ForkedCursorTest {

	private static final SetLattice<CVMLong> SETS=SetLattice.create();

	private static ASet<CVMLong> union(ASet<CVMLong> own,ASet<CVMLong> other) {
		return SETS.merge(LatticeContext.EMPTY,own,other);
	}

	@Test public void twoForksConvergeUnderTheMergeFunction() {
		Root<ASet<CVMLong>> root=Root.create(Sets.<CVMLong>empty());
		ForkedCursor<ASet<CVMLong>> first=root.fork(ForkedCursorTest::union);
		ForkedCursor<ASet<CVMLong>> second=root.fork(ForkedCursorTest::union);
		first.updateAndGet(s -> s.include(CVMLong.ONE));
		second.updateAndGet(s -> s.include(CVMLong.TWO));
		assertEquals(Sets.empty(),root.get(),"edits stay private until sync");

		assertEquals(Sets.of(1),first.sync());
		assertEquals(Sets.of(1,2),second.sync(),"the second sync merges rather than overwrites");
		assertEquals(Sets.of(1,2),root.get());
		assertEquals(Sets.of(1,2),second.get(),"a fork adopts what it published");
	}

	@Test public void unchangedForkAdoptsMovedParent() {
		Root<ASet<CVMLong>> root=Root.create(Sets.of(CVMLong.ONE));
		AtomicInteger merges=new AtomicInteger();
		ForkedCursor<ASet<CVMLong>> fork=root.fork((own,other) -> {
			merges.incrementAndGet();
			return union(own,other);
		});
		root.set(Sets.of(CVMLong.TWO));

		assertEquals(Sets.of(2),fork.sync());
		assertEquals(Sets.of(2),root.get(),"an unchanged fork never writes");
		assertEquals(Sets.of(2),fork.get());
		assertEquals(0,merges.get(),"adopting a moved parent needs no merge");
	}

	/** A competing edit must never require a second, fallible merge after publication. */
	@Test public void workingEditIsOrderedAfterSyncWithoutAnotherMerge() throws Exception {
		var root=Root.<ASet<CVMLong>>create(Sets.empty());
		CountDownLatch merging=new CountDownLatch(1), editing=new CountDownLatch(1);
		AtomicInteger merges=new AtomicInteger();
		var fork=root.fork((own,other) -> {
			if (merges.incrementAndGet()>1) throw new IllegalStateException("Unexpected post-publication merge");
			merging.countDown();
			await(editing);
			return union(own,other);
		});
		fork.set(Sets.of(CVMLong.ONE));
		root.set(Sets.of(CVMLong.TWO));
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var writer=executor.submit(() -> {
				await(merging);
				fork.updateAndGet(value -> {
					editing.countDown();
					return value.include(CVMLong.create(3));
				});
			});
			assertEquals(Sets.of(1,2),fork.sync());
			writer.get(10,TimeUnit.SECONDS);
		}
		assertEquals(Sets.of(1,2),root.get());
		assertEquals(Sets.of(1,2,3),fork.get());
		assertEquals(1,merges.get());
		assertEquals(Sets.of(1,2,3),fork.sync());
		assertEquals(1,merges.get(),"The next sync sees an unchanged destination");
	}

	@Test public void failedPreparationLeavesWorkingValueDestinationAndBaselineUntouched() {
		var root=Root.<ASet<CVMLong>>create(Sets.empty());
		AtomicBoolean reject=new AtomicBoolean(true);
		AtomicInteger merges=new AtomicInteger();
		var fork=ForkedCursor.create(root,value -> value,value -> {
			if (reject.get()) throw new IllegalArgumentException("Signing failed");
			return value;
		},(own,other) -> { merges.incrementAndGet(); return union(own,other); });
		var local=Sets.<CVMLong>of(CVMLong.ONE);
		var remote=Sets.<CVMLong>of(CVMLong.TWO);
		fork.set(local);
		root.set(remote);
		assertThrows(IllegalArgumentException.class,fork::sync);
		assertSame(local,fork.get());
		assertSame(remote,root.get());
		reject.set(false);
		assertEquals(Sets.of(1,2),fork.sync());
		assertEquals(2,merges.get(),"Failure must not advance the baseline");
		assertSame(root.get(),fork.get());
	}

	@Test public void reentrantPreparationCannotWriteOrSyncTheFork() {
		var root=Root.create(CVMLong.ZERO);
		var reference=new java.util.concurrent.atomic.AtomicReference<ForkedCursor<CVMLong>>();
		var action=new java.util.concurrent.atomic.AtomicReference<Runnable>();
		var fork=ForkedCursor.create(root,value -> value,value -> {
			action.get().run();
			return value;
		},(own,other) -> own);
		reference.set(fork);
		fork.set(CVMLong.ONE);
		for (Runnable invalid:new Runnable[] {
			() -> reference.get().set(CVMLong.TWO), () -> reference.get().sync()
		}) {
			action.set(invalid);
			assertThrows(IllegalStateException.class,fork::sync);
			assertSame(CVMLong.ZERO,root.get());
			assertSame(CVMLong.ONE,fork.get());
		}
		action.set(() -> {});
		assertSame(CVMLong.ONE,fork.sync());
	}

	@Test public void failedReconciliationOnCASRetryDoesNotPublishOrAdvanceBaseline() {
		var root=new Root<ASet<CVMLong>>(Sets.empty()) {
			boolean conflict=true;
			@Override public boolean compareAndSet(ASet<CVMLong> expected,ASet<CVMLong> next) {
				if (conflict) {
					conflict=false;
					super.set(Sets.of(CVMLong.TWO));
					return false;
				}
				return super.compareAndSet(expected,next);
			}
		};
		var fork=root.fork((own,other) -> { throw new IllegalArgumentException("Conflict rejected"); });
		var local=Sets.<CVMLong>of(CVMLong.ONE);
		fork.set(local);
		assertThrows(IllegalArgumentException.class,fork::sync);
		assertSame(local,fork.get());
		assertEquals(Sets.of(2),root.get(),"Only the competing writer changed the destination");
		root.set(Sets.empty());
		assertSame(local,fork.sync(),"Returning to the original baseline needs no reconciliation");
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}

	@Test public void forkRequiresAMergeFunction() {
		Root<ASet<CVMLong>> root=Root.create(Sets.<CVMLong>empty());
		assertThrows(NullPointerException.class,() -> root.fork(null));
	}

	@Test public void workingStorageAlwaysSupportsExclusiveTransactions() {
		assertTrue(Root.<ASet<CVMLong>>create(Sets.empty()).fork(ForkedCursorTest::union)
			.supportsExclusiveTransactions());
		assertTrue(new TransactionalRootCursor<ASet<CVMLong>>(Sets.empty()).fork(ForkedCursorTest::union)
			.supportsExclusiveTransactions());
	}
}
