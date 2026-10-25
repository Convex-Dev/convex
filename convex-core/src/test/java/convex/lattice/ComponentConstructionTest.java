package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ProjectionCursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.TransactionalRootCursor;
import convex.lattice.cursor.Transactions;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.MaxLattice;

/** Construction belongs to component write functions and commits with the edit. */
public class ComponentConstructionTest {

	private static final Keyword REGION=Keyword.intern("region");
	private static final KeyedLattice INNER=KeyedLattice.create(Keywords.FOO,MaxLattice.INSTANCE,Keywords.BAR,MaxLattice.INSTANCE);
	private static final KeyedLattice OUTER=KeyedLattice.create(REGION,INNER);

	@Test public void plainPathsNeverCreateContainers() {
		Root<Index<Keyword,ACell>> root=new Root<>();
		AForkableCursor<CVMLong> path=root.path(REGION,Keywords.FOO);
		assertNull(path.get());
		assertThrows(IllegalStateException.class,() -> path.set(CVMLong.ONE));
		assertThrows(IllegalStateException.class,() -> path.compareAndSet(null,CVMLong.ONE));
		assertThrows(IllegalStateException.class,() -> path.updateAndGet(v -> {
			assertNull(v);
			return CVMLong.ONE;
		}));
		assertNull(root.get());
		root.set(OUTER.zero());
		assertThrows(IllegalStateException.class,() -> path.set(CVMLong.ONE));
		assertSame(OUTER.zero(),root.get());
	}

	@Test public void componentViewsConstructOnlyOnWrite() {
		Root<Index<Keyword,ACell>> root=new Root<>();
		AForkableCursor<CVMLong> view=LatticeComponent.resolve(root,OUTER,REGION,Keywords.FOO);
		assertNull(view.get());
		assertNull(root.get());
		assertNull(view.updateAndGet(value -> value));
		assertTrue(view.compareAndSet(null,null));
		assertNull(root.get(),"No-op writes must not materialise empty ancestors");
		assertNull(view.getAndUpdate(value -> {
			assertNull(value);
			return CVMLong.ONE;
		}));
		assertEquals(OUTER.zero().assoc(REGION,INNER.zero().assoc(Keywords.FOO,CVMLong.ONE)),root.get());
		assertEquals(CVMLong.TWO,view.updateAndGet(value -> CVMLong.create(value.longValue()+1)));
	}

	@Test public void constructionFailureLeavesSourceUntouched() {
		Root<Index<Keyword,ACell>> root=new Root<>();
		var failing=ProjectionCursor.create(root,value -> value,(value,update) -> {
			Index<Keyword,ACell> constructed=OUTER.zero().assoc(REGION,INNER.zero());
			assertNotNull(constructed);
			throw new IllegalArgumentException("Rejected by component");
		});
		assertThrows(IllegalArgumentException.class,() -> failing.set(OUTER.zero()));
		assertNull(root.get());
	}

	@Test public void concurrentConstructionPreservesSiblingWrites() throws Exception {
		Root<Index<Keyword,ACell>> root=new Root<>();
		AForkableCursor<CVMLong> left=LatticeComponent.resolve(root,OUTER,REGION,Keywords.FOO);
		AForkableCursor<CVMLong> right=LatticeComponent.resolve(root,OUTER,REGION,Keywords.BAR);
		CountDownLatch entered=new CountDownLatch(1);
		CountDownLatch resume=new CountDownLatch(1);
		AtomicBoolean first=new AtomicBoolean(true);
		try (var executor=Executors.newSingleThreadExecutor()) {
			var write=executor.submit(() -> left.updateAndGet(value -> {
				if (first.getAndSet(false)) {
					entered.countDown();
					try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
				}
				return CVMLong.ONE;
			}));
			try {
				assertTrue(entered.await(10,TimeUnit.SECONDS));
				right.set(CVMLong.TWO);
			} finally { resume.countDown(); }
			assertEquals(CVMLong.ONE,write.get(10,TimeUnit.SECONDS));
		}
		assertEquals(CVMLong.ONE,left.get());
		assertEquals(CVMLong.TWO,right.get());
	}

	@Test public void projectionSupportsAtomicOperationsAndTransactions() {
		var root=new TransactionalRootCursor<Index<Keyword,ACell>>(null);
		AForkableCursor<CVMLong> view=LatticeComponent.resolve(root,OUTER,REGION,Keywords.FOO);
		assertTrue(view.supportsExclusiveTransactions());
		assertTrue(view.compareAndSet(null,CVMLong.ONE));
		assertFalse(view.compareAndSet(null,CVMLong.TWO));
		assertEquals(CVMLong.ONE,view.getAndSet(CVMLong.TWO));
		assertEquals(CVMLong.TWO,view.getAndAccumulate(CVMLong.ONE,(a,b) -> CVMLong.create(a.longValue()+b.longValue())));
		assertEquals(CVMLong.create(4),view.accumulateAndGet(CVMLong.ONE,(a,b) -> CVMLong.create(a.longValue()+b.longValue())));
		Transactions.transactLocked(view,draft -> draft.set(CVMLong.create(5)));
		assertEquals(CVMLong.create(5),view.get());
	}
}
