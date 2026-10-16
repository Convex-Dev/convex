package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test public void workingEditDuringSyncStaysPendingForTheNextSync() {
		var root=new Root<ASet<CVMLong>>(Sets.<CVMLong>empty()) {
			Runnable beforeCAS;
			@Override public boolean compareAndSet(ASet<CVMLong> expected,ASet<CVMLong> value) {
				Runnable action=beforeCAS;
				beforeCAS=null;
				if (action!=null) action.run();
				return super.compareAndSet(expected,value);
			}
		};
		ForkedCursor<ASet<CVMLong>> fork=root.fork(ForkedCursorTest::union);
		fork.set(Sets.of(CVMLong.ONE));
		root.beforeCAS=() -> fork.updateAndGet(s -> s.include(CVMLong.TWO));

		assertEquals(Sets.of(1),fork.sync());
		assertEquals(Sets.of(1),root.get(),"the racing edit is not part of this publication");
		assertEquals(Sets.of(1,2),fork.get(),"the racing edit survives in the working copy");
		assertEquals(Sets.of(1,2),fork.sync());
		assertEquals(Sets.of(1,2),root.get());
	}

	@Test public void forkRequiresAMergeFunction() {
		Root<ASet<CVMLong>> root=Root.create(Sets.<CVMLong>empty());
		assertThrows(NullPointerException.class,() -> root.fork(null));
	}

	@Test public void workingStorageFollowsTheDestinationTransactionCapability() {
		assertFalse(Root.<ASet<CVMLong>>create(Sets.empty()).fork(ForkedCursorTest::union)
			.supportsExclusiveTransactions());
		assertTrue(new TransactionalRootCursor<ASet<CVMLong>>(Sets.empty()).fork(ForkedCursorTest::union)
			.supportsExclusiveTransactions());
	}
}
