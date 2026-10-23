package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.function.BinaryOperator;
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

/** A fork is independent working storage; each sync supplies its reconciliation policy. */
public class ForkedCursorTest {

	private static final SetLattice<CVMLong> SETS=SetLattice.create();

	private static ASet<CVMLong> union(ASet<CVMLong> own,ASet<CVMLong> other) {
		return SETS.merge(LatticeContext.EMPTY,own,other);
	}

	@Test public void twoForksConvergeUnderTheMergeFunction() {
		Root<ASet<CVMLong>> root=Root.create(Sets.<CVMLong>empty());
		ForkedCursor<ASet<CVMLong>> first=root.fork();
		ForkedCursor<ASet<CVMLong>> second=root.fork();
		first.updateAndGet(s -> s.include(CVMLong.ONE));
		second.updateAndGet(s -> s.include(CVMLong.TWO));
		assertEquals(Sets.empty(),root.get(),"edits stay private until sync");

		assertEquals(Sets.of(1),first.sync(ForkedCursorTest::union));
		assertEquals(Sets.of(1,2),second.sync(ForkedCursorTest::union),"the second sync merges rather than overwrites");
		assertEquals(Sets.of(1,2),root.get());
		assertEquals(Sets.of(1,2),second.get(),"a fork adopts what it published");
	}

	@Test public void unchangedForkAdoptsMovedParent() {
		Root<ASet<CVMLong>> root=Root.create(Sets.of(CVMLong.ONE));
		AtomicInteger merges=new AtomicInteger();
		BinaryOperator<ASet<CVMLong>> merge=(own,other) -> {
			merges.incrementAndGet();
			return union(own,other);
		};
		var fork=root.fork();
		root.set(Sets.of(CVMLong.TWO));

		assertEquals(Sets.of(2),fork.sync(merge));
		assertEquals(Sets.of(2),root.get(),"an unchanged fork never writes");
		assertEquals(Sets.of(2),fork.get());
		assertEquals(0,merges.get(),"adopting a moved parent needs no merge");
	}

	/** A competing edit must never require a second, fallible merge after publication. */
	@Test public void workingEditIsOrderedAfterSyncWithoutAnotherMerge() throws Exception {
		var root=Root.<ASet<CVMLong>>create(Sets.empty());
		CountDownLatch merging=new CountDownLatch(1), editing=new CountDownLatch(1);
		AtomicInteger merges=new AtomicInteger();
		BinaryOperator<ASet<CVMLong>> merge=(own,other) -> {
			if (merges.incrementAndGet()>1) throw new IllegalStateException("Unexpected post-publication merge");
			merging.countDown();
			await(editing);
			return union(own,other);
		};
		var fork=root.fork();
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
			assertEquals(Sets.of(1,2),fork.sync(merge));
			writer.get(10,TimeUnit.SECONDS);
		}
		assertEquals(Sets.of(1,2),root.get());
		assertEquals(Sets.of(1,2,3),fork.get());
		assertEquals(1,merges.get());
		assertEquals(Sets.of(1,2,3),fork.sync(merge));
		assertEquals(1,merges.get(),"The next sync sees an unchanged destination");
	}

	@Test public void failedPreparationLeavesWorkingValueDestinationAndBaselineUntouched() {
		var root=Root.<ASet<CVMLong>>create(Sets.empty());
		AtomicBoolean reject=new AtomicBoolean(true);
		AtomicInteger merges=new AtomicInteger();
		var destination=new AUpdateCursor<ASet<CVMLong>,ASet<CVMLong>>(root) {
			@Override protected ASet<CVMLong> prepareWrite(ASet<CVMLong> value) {
				if (reject.get()) throw new IllegalArgumentException("Preparation failed");
				return value;
			}
		};
		BinaryOperator<ASet<CVMLong>> merge=(own,other) -> { merges.incrementAndGet(); return union(own,other); };
		var fork=destination.fork();
		var local=Sets.<CVMLong>of(CVMLong.ONE);
		var remote=Sets.<CVMLong>of(CVMLong.TWO);
		fork.set(local);
		root.set(remote);
		assertThrows(IllegalArgumentException.class,() -> fork.sync(merge));
		assertSame(local,fork.get());
		assertSame(remote,root.get());
		reject.set(false);
		assertEquals(Sets.of(1,2),fork.sync(merge));
		assertEquals(2,merges.get(),"Failure must not advance the baseline");
		assertSame(root.get(),fork.get());
	}

	@Test public void failedReconciliationOnCASRetryDoesNotPublishOrAdvanceBaseline() {
		var root=new Root<ASet<CVMLong>>(Sets.empty()) {
			boolean conflict=true;
			@Override public ASet<CVMLong> updateAndGet(java.util.function.UnaryOperator<ASet<CVMLong>> action) {
				return super.updateAndGet(current -> {
					ASet<CVMLong> next=action.apply(current);
					if (conflict) {
						conflict=false;
						super.set(Sets.of(CVMLong.TWO));
					}
					return next;
				});
			}
		};
		BinaryOperator<ASet<CVMLong>> merge=(own,other) -> { throw new IllegalArgumentException("Conflict rejected"); };
		var fork=root.fork();
		var local=Sets.<CVMLong>of(CVMLong.ONE);
		fork.set(local);
		assertThrows(IllegalArgumentException.class,() -> fork.sync(merge));
		assertSame(local,fork.get());
		assertEquals(Sets.of(2),root.get(),"Only the competing writer changed the destination");
		root.set(Sets.empty());
		assertSame(local,fork.sync(merge),"Returning to the original baseline needs no reconciliation");
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}

	@Test public void syncRequiresAMergeFunction() {
		Root<ASet<CVMLong>> root=Root.create(Sets.<CVMLong>empty());
		assertThrows(NullPointerException.class,() -> root.fork().sync(null));
	}

	@Test public void reconciliationPolicyBelongsToEachSync() {
		var root=Root.create(CVMLong.ZERO);
		var fork=root.fork();
		fork.set(CVMLong.ONE);
		root.set(CVMLong.TWO);
		assertSame(CVMLong.ONE,fork.sync((own,other) -> own));
		fork.set(CVMLong.create(3));
		root.set(CVMLong.create(4));
		assertEquals(CVMLong.create(4),fork.sync((own,other) -> other));
		assertSame(root.get(),fork.get());
	}

	@Test public void workingStorageAlwaysSupportsExclusiveTransactions() {
		assertTrue(Root.<ASet<CVMLong>>create(Sets.empty()).fork()
			.supportsExclusiveTransactions());
		assertTrue(new TransactionalRootCursor<ASet<CVMLong>>(Sets.empty()).fork()
			.supportsExclusiveTransactions());
	}
}
