package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.store.MemoryStore;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.generic.ADelegatingLattice;
import convex.lattice.generic.CompareLattice;
import convex.lattice.generic.KeyedLattice;
import convex.lattice.generic.SetLattice;

public class ComponentPublicationTest {

	private static final SetLattice<CVMLong> SETS=SetLattice.create();

	private static class Component<V extends ACell> extends LatticeComponent<V> {
		Component(ALattice<V> lattice, AForkableCursor<V> destination) {
			super(lattice,destination,LatticeContext.EMPTY);
		}
	}

	private static class InterleavingRoot<V extends ACell> extends Root<V> {
		Runnable beforeCAS;
		InterleavingRoot(V value) { super(value); }
		@Override public boolean compareAndSet(V expected,V value) {
			Runnable action=beforeCAS;
			beforeCAS=null;
			if (action!=null) action.run();
			return super.compareAndSet(expected,value);
		}
	}

	@Test
	public void testChildrenShareWorkingStateAndPublicationStopsAtDestination() throws Exception {
		try (MemoryStore store=new MemoryStore()) {
			var host=LatticeHost.create(SETS.zero(),store);
			AtomicInteger publications=new AtomicInteger();
			host.setPublicationPolicy(value -> { publications.incrementAndGet(); return value; });
			var component=new Component<>(SETS,host.cursor());
			component.cursor().updateAndGet(v -> v.include(CVMLong.ONE));
			assertEquals(SETS.zero(),host.cursor().get());
			component.sync();
			assertEquals(Sets.of(1),host.cursor().get());
			assertEquals(0,publications.get(),"Component publication stops at the host cursor");
			host.sync();
			assertEquals(1,publications.get());
		}
	}

	@Test
	public void testConcurrentDestinationChangeIsMerged() {
		var destination=new InterleavingRoot<>(SETS.zero());
		var component=new Component<>(SETS,destination);
		component.cursor().set(Sets.of(1));
		destination.beforeCAS=() -> destination.set(Sets.of(2));
		component.sync();
		assertEquals(Sets.of(1,2),destination.get());
		assertEquals(destination.get(),component.cursor().get());
	}

	@Test
	public void testEqualDestinationDoesNotRequireMerge() {
		AtomicInteger merges=new AtomicInteger();
		var lattice=new ADelegatingLattice<ASet<CVMLong>>(SETS) {
			@Override public ASet<CVMLong> merge(LatticeContext context,ASet<CVMLong> own,ASet<CVMLong> other) {
				merges.incrementAndGet();
				return super.merge(context,own,other);
			}
		};
		Keyword key=Keyword.intern("value");
		Keyword sibling=Keyword.intern("sibling");
		var root=new InterleavingRoot<>(Index.<Keyword,ACell>none().assoc(key,Sets.of(1)));
		var destination=root.<ASet<CVMLong>>path(key);
		var component=new Component<>(lattice,destination);
		component.cursor().set(Sets.of(1,2));
		root.beforeCAS=() -> {
			ASet<CVMLong> equal=Sets.of(1);
			assertNotSame(root.get().get(key),equal);
			root.set(root.get().assoc(key,equal).assoc(sibling,CVMLong.ONE));
		};
		component.sync();
		assertEquals(Sets.of(1,2),destination.get());
		assertEquals(CVMLong.ONE,root.get().get(sibling));
		assertEquals(0,merges.get());
	}

	/** Staging is the same at every level: a keyed copy publishes only the keys it declares. */
	@Test
	public void testPartialKeyedLatticePublishesOnlyDeclaredKeysAtAnyLevel() {
		Keyword record=Keyword.intern("record");
		Keyword a=Keyword.intern("a");
		Keyword b=Keyword.intern("b");
		KeyedLattice partial=KeyedLattice.create(a,SETS);
		var root=new Root<Index<Keyword,ACell>>(Index.<Keyword,ACell>none().assoc(record,
			Index.<Keyword,ACell>none().assoc(a,Sets.of(1)).assoc(b,Sets.of(2))));
		AForkableCursor<Index<Keyword,ACell>> destination=root.path(record);
		var component=new Component<>(partial,destination);
		component.cursor().updateAndGet(v -> v.assoc(a,Sets.of(1,3)));
		// Another writer advances the key this component does not declare.
		destination.updateAndGet(v -> v.assoc(b,Sets.of(2,4)));
		component.sync();
		assertEquals(Sets.of(1,3),destination.get().get(a),"the declared key merges");
		assertEquals(Sets.of(2,4),destination.get().get(b),"an undeclared key adopts the destination");
		assertEquals(destination.get(),component.cursor().get(),"the copy carries the destination's other keys");
	}

	@Test
	public void testUneditedComponentAdoptsParentInsteadOfWinningATie() {
		var lattice=CompareLattice.<CVMLong>create((a,b) -> 0);
		var destination=new Root<>(CVMLong.ONE);
		var component=new Component<>(lattice,destination);
		destination.set(CVMLong.TWO);
		component.sync();
		assertEquals(CVMLong.TWO,destination.get());
		assertEquals(CVMLong.TWO,component.cursor().get());
		component.cursor().set(CVMLong.create(3));
		component.sync();
		assertEquals(CVMLong.create(3),destination.get(),"Successful sync must advance the baseline");
	}

	@Test
	public void testMergeUsesPublishingComponentContext() {
		LatticeContext context=LatticeContext.create(CVMLong.TWO,null);
		var lattice=new ADelegatingLattice<ASet<CVMLong>>(SETS) {
			@Override public ASet<CVMLong> merge(LatticeContext actual,ASet<CVMLong> own,ASet<CVMLong> other) {
				assertSame(context,actual);
				return super.merge(actual,own,other);
			}
		};
		var destination=new Root<>(SETS.zero());
		var component=new Component<>(lattice,destination);
		component.setContext(context);
		component.cursor().set(Sets.of(1));
		destination.set(Sets.of(2));
		component.sync();
		assertEquals(Sets.of(1,2),destination.get());
		assertSame(context,component.getContext());
	}

	@Test
	public void testFailedMergeLeavesWorkingStateAndDestinationUntouched() {
		var lattice=new ADelegatingLattice<ASet<CVMLong>>(SETS) {
			@Override public ASet<CVMLong> merge(LatticeContext context,ASet<CVMLong> own,ASet<CVMLong> other) {
				throw new IllegalArgumentException("merge rejected");
			}
		};
		var destination=new Root<>(SETS.zero());
		var component=new Component<>(lattice,destination);
		component.cursor().set(Sets.of(1));
		destination.set(Sets.of(2));
		assertThrows(IllegalArgumentException.class,component::sync);
		assertEquals(Sets.of(1),component.cursor().get());
		assertEquals(Sets.of(2),destination.get());
		destination.set(SETS.zero());
		component.sync();
		assertEquals(Sets.of(1),destination.get(),"Failure must not advance the publication baseline");
	}

	@Test
	public void testEditsDuringPublicationRemainPendingAndPublicationsAreOrdered() throws Exception {
		CountDownLatch publishing=new CountDownLatch(1);
		CountDownLatch resume=new CountDownLatch(1);
		List<ASet<CVMLong>> installed=new ArrayList<>();
		var destination=new InterleavingRoot<>(SETS.zero()) {
			@Override public boolean compareAndSet(ASet<CVMLong> expected,ASet<CVMLong> value) {
				boolean success=super.compareAndSet(expected,value);
				if (success) installed.add(value);
				return success;
			}
		};
		var component=new Component<>(SETS,destination);
		component.cursor().set(Sets.of(1));
		destination.beforeCAS=() -> {
			publishing.countDown();
			try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
		};
		try (var executor=Executors.newFixedThreadPool(2)) {
			var first=executor.submit(component::sync);
			try {
				assertTrue(publishing.await(10,TimeUnit.SECONDS));
				component.cursor().updateAndGet(v -> v.include(CVMLong.TWO));
				assertEquals(Sets.of(),destination.get());
				var second=executor.submit(component::sync);
				resume.countDown();
				first.get(10,TimeUnit.SECONDS);
				second.get(10,TimeUnit.SECONDS);
			} finally { resume.countDown(); }
		}
		assertEquals(List.of(Sets.of(1),Sets.of(1,2)),installed);
		assertEquals(Sets.of(1,2),destination.get());
		assertEquals(destination.get(),component.cursor().get());
	}
}
